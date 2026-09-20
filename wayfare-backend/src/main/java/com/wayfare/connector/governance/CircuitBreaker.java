package com.wayfare.connector.governance;

import com.wayfare.service.SysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 熔断器（基于 Redis 计数，不引 Resilience4j）。
 *
 * <p><b>为什么不引框架</b>：手册明确要求不引 Resilience4j。而且这里的需求很窄 ——
 * 只要「连续失败 N 次 → 打开 X 秒 → 半开放行一次」，
 * 用两个 Redis 键（失败计数 + 打开截止时间）就够，引框架反而多一层要理解的东西。
 *
 * <h3>状态机</h3>
 * <pre>
 *   CLOSED ──失败累计 ≥ 阈值──▶ OPEN（拒绝请求，直接走降级）
 *     ▲                          │
 *     │                     open_seconds 到期
 *     │                          ▼
 *     └──成功(清零)──── HALF_OPEN（放行一次试探）
 *                              │
 *                          失败 ──▶ 重新 OPEN（因为计数未清零，一次失败即再打开）
 * </pre>
 *
 * <p><b>半开是怎么实现的</b>：截止时间过期后，{@link #isOpen} 返回 false 并把截止键删掉，
 * 于是下一个请求会被放行；而<b>失败计数刻意保留</b>，所以这次试探一旦失败，
 * 计数仍 ≥ 阈值，立刻再次打开 —— 正好是「失败则重新计时」。
 * 只有真正成功（{@link #recordSuccess}）才会把计数清零、彻底关闭。
 *
 * <p><b>Redis 不可用时按「未熔断」处理</b>（fail-open）：熔断器本身出问题不该让地图功能全废，
 * 且此时请求失败会由调用方的超时与异常处理兜住。与 TokenBlacklist、SysConfigService 的取舍一致。
 */
@Component
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private static final String FAIL_KEY_PREFIX = "cb:fail:";
    private static final String OPEN_KEY_PREFIX = "cb:open:";

    /** 失败计数的存活时间：超过它没再失败就认为「历史上的连续失败」已经过去 */
    private static final long FAIL_COUNTER_TTL_SECONDS = 3600;

    private final StringRedisTemplate stringRedisTemplate;
    private final SysConfigService sysConfigService;

    public CircuitBreaker(StringRedisTemplate stringRedisTemplate, SysConfigService sysConfigService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.sysConfigService = sysConfigService;
    }

    /**
     * 熔断是否处于打开状态（打开期间应直接走降级，不要发请求）。
     * 截止时间已过则返回 false（相当于进入半开，放行一次）。
     */
    public boolean isOpen(String name) {
        try {
            String openUntil = stringRedisTemplate.opsForValue().get(OPEN_KEY_PREFIX + name);
            if (!StringUtils.hasText(openUntil)) return false;

            long deadline = Long.parseLong(openUntil);
            long now = System.currentTimeMillis();
            if (now < deadline) {
                return true;
            }
            // 已到期：删掉截止键进入半开。失败计数保留 → 试探失败会立刻再打开
            stringRedisTemplate.delete(OPEN_KEY_PREFIX + name);
            log.info("[{}] 熔断已到半开窗口，放行一次试探请求", name);
            return false;
        } catch (Exception e) {
            log.warn("查询熔断状态失败（Redis 不可用？），本次按未熔断处理: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 记一次失败：累计到阈值就打开熔断。
     *
     * @return 本次是否触发了打开（便于日志与诊断）
     */
    public boolean recordFailure(String name) {
        int threshold = sysConfigService.getInt("map.breaker.fail-threshold", 5);
        int openSeconds = sysConfigService.getInt("map.breaker.open-seconds", 300);
        try {
            String failKey = FAIL_KEY_PREFIX + name;
            Long count = stringRedisTemplate.opsForValue().increment(failKey);
            stringRedisTemplate.expire(failKey, FAIL_COUNTER_TTL_SECONDS, TimeUnit.SECONDS);

            if (count != null && count >= threshold) {
                stringRedisTemplate.opsForValue().set(OPEN_KEY_PREFIX + name,
                        String.valueOf(System.currentTimeMillis() + openSeconds * 1000L),
                        openSeconds + 60L, TimeUnit.SECONDS);
                log.warn("[{}] 连续失败 {} 次（阈值 {}），熔断打开 {} 秒", name, count, threshold, openSeconds);
                return true;
            }
            log.warn("[{}] 调用失败 {} 次（阈值 {}）", name, count, threshold);
            return false;
        } catch (Exception e) {
            log.warn("记录熔断失败次数时出错（Redis 不可用？）: {}", e.getMessage());
            return false;
        }
    }

    /** 记一次成功：清零计数并关闭熔断 */
    public void recordSuccess(String name) {
        try {
            stringRedisTemplate.delete(FAIL_KEY_PREFIX + name);
            stringRedisTemplate.delete(OPEN_KEY_PREFIX + name);
        } catch (Exception e) {
            log.warn("重置熔断状态时出错（Redis 不可用？）: {}", e.getMessage());
        }
    }

    /**
     * 诊断快照：状态、失败计数、剩余打开秒数。
     * 手册要求「状态要能被诊断接口读到」，这个方法是那个要求的出口。
     */
    public Map<String, Object> state(String name) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("breakerName", name);
        try {
            String failStr = stringRedisTemplate.opsForValue().get(FAIL_KEY_PREFIX + name);
            String openUntilStr = stringRedisTemplate.opsForValue().get(OPEN_KEY_PREFIX + name);
            int failCount = StringUtils.hasText(failStr) ? Integer.parseInt(failStr) : 0;
            long now = System.currentTimeMillis();
            long deadline = StringUtils.hasText(openUntilStr) ? Long.parseLong(openUntilStr) : 0L;

            boolean open = deadline > now;
            state.put("breakerOpen", open);
            state.put("failCount", failCount);
            state.put("failThreshold", sysConfigService.getInt("map.breaker.fail-threshold", 5));
            state.put("openSeconds", sysConfigService.getInt("map.breaker.open-seconds", 300));
            state.put("remainingOpenSeconds", open ? (deadline - now) / 1000 : 0);
        } catch (Exception e) {
            state.put("breakerOpen", false);
            state.put("error", "Redis 不可用：" + e.getMessage());
        }
        return state;
    }

    /** 手动重置（诊断用；调错 AK 修好后不必等 5 分钟） */
    public void reset(String name) {
        recordSuccess(name);
        log.info("[{}] 熔断状态已被手动重置", name);
    }
}
