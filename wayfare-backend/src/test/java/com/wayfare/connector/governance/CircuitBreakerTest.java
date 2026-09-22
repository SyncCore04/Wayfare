package com.wayfare.connector.governance;

import com.wayfare.service.SysConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CircuitBreaker} 的单测（P4 修复 · 熔断接入大模型时补）。
 *
 * <p>为什么要为它单独写测试：熔断是「不再白等一次 90 秒超时」的唯一机制。
 * 实测 qwen 的失败模式是长输出跑满 {@code llm.timeout-ms} 才判定超时，
 * 两次真实运行合计白等约 9 分钟 —— 如果熔断悄悄坏掉，这个浪费会立刻回来，
 * 而且不会有任何报错，只是「变慢了」，很难被发现。
 *
 * <p>真连 Redis：熔断状态本来就存在 Redis 里，用 mock 测不出「跨请求累积」这个关键语义。
 */
@SpringBootTest
class CircuitBreakerTest {

    private static final String LLM_PREFIX = "llm";
    private static final String MAP_PREFIX = "map";

    /** 测试用的独立名字，避免与真实运行的熔断状态互相干扰 */
    private static final String TEST_NAME = "test:cb:qwen";

    @Autowired private CircuitBreaker circuitBreaker;
    @Autowired private SysConfigService sysConfigService;
    @Autowired private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    void cleanUp() {
        circuitBreaker.reset(TEST_NAME);
        sysConfigService.clearCache();
    }

    @Test
    @DisplayName("连续失败达阈值后熔断打开（llm 前缀阈值默认 3）")
    void opensAfterThreshold() {
        assertFalse(circuitBreaker.isOpen(TEST_NAME), "初始不应是打开状态");

        // 阈值 3：前两次不该打开
        assertFalse(circuitBreaker.recordFailure(TEST_NAME, LLM_PREFIX), "第 1 次失败不该打开");
        assertFalse(circuitBreaker.isOpen(TEST_NAME));
        assertFalse(circuitBreaker.recordFailure(TEST_NAME, LLM_PREFIX), "第 2 次失败不该打开");
        assertFalse(circuitBreaker.isOpen(TEST_NAME));

        // 第 3 次达到阈值 → 打开
        assertTrue(circuitBreaker.recordFailure(TEST_NAME, LLM_PREFIX), "第 3 次失败应触发打开");
        assertTrue(circuitBreaker.isOpen(TEST_NAME), "阈值已到，应处于打开状态");
    }

    @Test
    @DisplayName("不同前缀读各自的阈值 —— 大模型阈值(3)比地图(5)低")
    void differentPrefixesUseDifferentThresholds() {
        // 用 map 前缀失败 3 次：map 阈值是 5，所以不该打开
        circuitBreaker.recordFailure(TEST_NAME, MAP_PREFIX);
        circuitBreaker.recordFailure(TEST_NAME, MAP_PREFIX);
        boolean opened = circuitBreaker.recordFailure(TEST_NAME, MAP_PREFIX);

        assertFalse(opened, "map 阈值 5，第 3 次失败不该打开");
        assertFalse(circuitBreaker.isOpen(TEST_NAME),
                "同一次失败计数在 llm 前缀下会打开、在 map 前缀下不会 —— 这正是分前缀的意义");
    }

    @Test
    @DisplayName("成功会清零计数并关闭熔断（半开试探成功即恢复的落点）")
    void successResetsEverything() {
        circuitBreaker.recordFailure(TEST_NAME, LLM_PREFIX);
        circuitBreaker.recordFailure(TEST_NAME, LLM_PREFIX);
        circuitBreaker.recordFailure(TEST_NAME, LLM_PREFIX);
        assertTrue(circuitBreaker.isOpen(TEST_NAME));

        circuitBreaker.recordSuccess(TEST_NAME);

        assertFalse(circuitBreaker.isOpen(TEST_NAME), "成功后应关闭熔断");
        // 计数也清零：再失败一次不该立刻又打开
        assertFalse(circuitBreaker.recordFailure(TEST_NAME, LLM_PREFIX),
                "计数已清零，这一次只是第 1 次失败");
    }

    @Test
    @DisplayName("诊断快照能读到状态与失败计数（P6 看板的数据源）")
    void stateIsReadable() {
        circuitBreaker.recordFailure(TEST_NAME, LLM_PREFIX);

        java.util.Map<String, Object> state = circuitBreaker.state(TEST_NAME);

        // 字段名以实现为准（实测快照含 breakerName / breakerOpen / failCount / failThreshold /
        // openSeconds / remainingOpenSeconds —— 我第一版按直觉写了 "open"，断言直接失败）
        assertTrue(state.containsKey("breakerOpen"), "快照应含 breakerOpen 字段，实际 = " + state.keySet());
        assertTrue(state.containsKey("failCount"), "快照应含 failCount 字段，实际 = " + state.keySet());
        assertFalse((Boolean) state.get("breakerOpen"));
    }

    @Test
    @DisplayName("isOpen 对没有失败记录的名字返回 false（不误伤正常厂商）")
    void unknownNameIsNotOpen() {
        assertFalse(circuitBreaker.isOpen("test:cb:never-failed"));
    }

    @Test
    @DisplayName("快照按前缀报阈值：llm 报 3 / map 报 5（P6-A 修 —— 原来一律写死 map 的 5）")
    void snapshotReportsPrefixAwareThreshold() {
        // 这条测试锁的是一个「诊断信息说谎」的 bug：快照原来把 map.breaker.* 写死，
        // 于是大模型熔断被读出「阈值 5」，而它实际第 3 次失败就跳闸 ——
        // 管理页显示 0/5 却在第 3 次变红，看的人只会以为是界面坏了。
        java.util.Map<String, Object> llmState = circuitBreaker.state(TEST_NAME, LLM_PREFIX);
        java.util.Map<String, Object> mapState = circuitBreaker.state(TEST_NAME, MAP_PREFIX);

        assertEquals(3, llmState.get("failThreshold"),
                "大模型快照应报 llm.breaker.fail-threshold（库未执行初始化脚本时取默认 3），实际 = " + llmState);
        assertEquals(5, mapState.get("failThreshold"),
                "地图快照应报 map.breaker.fail-threshold（库里有值 5），实际 = " + mapState);
        assertEquals(5, circuitBreaker.state(TEST_NAME).get("failThreshold"),
                "单参 state() 应保持「地图语境」，兼容既有诊断调用方");
    }

    @Test
    @DisplayName("Redis 里的键名带 cb: 前缀，与业务键不冲突")
    void keysAreNamespaced() {
        circuitBreaker.recordFailure(TEST_NAME, LLM_PREFIX);

        List<String> keys = new java.util.ArrayList<>();
        stringRedisTemplate.keys("cb:*").forEach(keys::add);

        assertTrue(keys.stream().anyMatch(k -> k.startsWith("cb:fail:")),
                "应存在 cb:fail:* 键，实际 = " + keys);
    }
}
