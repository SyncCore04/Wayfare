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

    /**
     * 配置前缀：决定读 {@code llm.breaker.*} 还是 {@code map.breaker.*}。
     * 地图是历史默认值（P1-D 时只有它一个接入方），大模型在 P4 修复时接进来。
     */
    private static final String BREAKER_PREFIX_MAP = "map";
    private static final String BREAKER_PREFIX_LLM = "llm";

    /**
     * 前缀对应的默认阈值。
     *
     * <p><b>必须与 db/schema-trip.sql 的初始值一致</b>：库没执行过初始化脚本时靠它兜底，
     * 两边不一致会让「未初始化」的部署静默跑偏（且不会有任何报错）。
     * 大模型阈值更低是因为它一次失败要白等一整个 {@code llm.timeout-ms}（90 秒），
     * 而地图一次失败只损失几百毫秒。
     */
    private static int defaultThreshold(String configPrefix) {
        return BREAKER_PREFIX_LLM.equals(configPrefix) ? 3 : 5;
    }

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
        // 默认按地图的配置前缀 —— 保持既有调用方（BaiduMapProvider）的行为不变
        return recordFailure(name, "map");
    }

    /**
     * 记一次失败（P4 修复 · 支持按前缀取阈值）。
     *
     * <p><b>为什么要分前缀</b>：地图与大模型的失败特征完全不同 ——
     * 地图是「请求被拒 / 秒级超时」，一次失败只损失几百毫秒；
     * 大模型是「长输出跑满 90 秒才判定超时」，<b>一次白等就抵得上几十次地图调用</b>。
     * 用同一套阈值会让两边的行为都不可控：地图该早点打开、大模型该更早打开。
     *
     * @param configPrefix 配置前缀：地图传 {@code map}（读 {@code map.breaker.*}），
     *                     大模型传 {@code llm}（读 {@code llm.breaker.*}）
     */
    public boolean recordFailure(String name, String configPrefix) {
        // 默认值必须与 db/schema-trip.sql 的初始值一致 ——
        // 否则「库还没执行过初始化脚本」的部署会跟配置好的部署行为不同，
        // 而且这种差异不会有任何报错（本次写测试时正是踩在这个默认值上）
        int fallbackThreshold = defaultThreshold(configPrefix);
        int threshold = sysConfigService.getInt(configPrefix + ".breaker.fail-threshold", fallbackThreshold);
        int openSeconds = sysConfigService.getInt(configPrefix + ".breaker.open-seconds", 300);
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
     *
     * <p>等价于 {@code state(name, "map")} —— 地图是既有调用方的默认语境，
     * 保留这个签名免得改动波及既有诊断代码。
     */
    public Map<String, Object> state(String name) {
        return state(name, BREAKER_PREFIX_MAP);
    }

    /**
     * 诊断快照（P6-A 加的重载）：按前缀读对应阈值。
     *
     * <p><b>为什么必须带前缀</b>：原实现把 {@code map.breaker.*} 写死在快照里，
     * 于是大模型熔断被诊断页读出「阈值 5」—— 而它实际生效的门槛是
     * {@code llm.breaker.fail-threshold}（默认 3）。管理页上「0/5」与「第 3 次就跳闸」
     * 互相矛盾，属于**诊断信息说谎**，比没有信息更坏。
     *
     * @param configPrefix {@code map} 或 {@code llm}
     */
    public Map<String, Object> state(String name, String configPrefix) {
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
            state.put("failThreshold", sysConfigService.getInt(
                    configPrefix + ".breaker.fail-threshold", defaultThreshold(configPrefix)));
            state.put("openSeconds", sysConfigService.getInt(configPrefix + ".breaker.open-seconds", 300));
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
