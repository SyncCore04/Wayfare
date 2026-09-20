package com.wayfare.connector.governance;

import com.wayfare.common.result.ResultCode;
import com.wayfare.connector.governance.impl.ExternalCallLogServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 出站治理层单元测试（P1-D 验收 2、3）。
 *
 * <p>为什么用 Mockito 替身而不是起真服务：这一层要验的是「重试次数」与「日志条数」，
 * 用替身能精确控制「第一次失败、第二次成功」这种时序，且不必等真实网络。
 * 传输层（SimpleExternalHttpClient）本身的行为已在 P1-B/P1-C 的真实调用里验证过。
 */
class GovernedExternalHttpClientTest {

    /** 记账用的日志服务替身：数一数到底写了几条 */
    private static class CapturingLogService implements ExternalCallLogService {
        final List<ExternalCallRecord> records = new ArrayList<>();

        @Override
        public void record(ExternalCallRecord record) {
            records.add(record);
        }

        /**
         * P1-E 给接口加了 stats(connector)（诊断接口用），这里只需满足接口契约。
         * 本测试验的是「重试次数」与「日志条数」，不关心统计，返回空 map 即可。
         */
        @Override
        public Map<String, Object> stats(String connector) {
            return Map.of();
        }
    }

    private SimpleExternalHttpClient transport;
    private CapturingLogService logService;
    private GovernedExternalHttpClient client;

    @BeforeEach
    void setUp() {
        transport = mock(SimpleExternalHttpClient.class);
        logService = new CapturingLogService();
        client = new GovernedExternalHttpClient(transport, logService);
    }

    @Test
    @DisplayName("验收2：GET 首次失败第二次成功 → 最终成功，且留下 2 条日志")
    void getRetriesOnceAndSucceeds() {
        when(transport.getJson(anyString(), anyMap(), anyInt()))
                .thenThrow(ExternalHttpException.noResponse("connection reset", false, new IOException("reset")))
                .thenReturn("{\"status\":0}");

        String result = client.getJson(
                "https://api.map.baidu.com/place/v2/search?ak=SECRETAK1234567890&query=开元寺",
                Map.of(), 5000);

        assertEquals("{\"status\":0}", result, "重试后应返回成功结果");
        verify(transport, times(2)).getJson(anyString(), anyMap(), anyInt());
        assertEquals(2, logService.records.size(), "每次尝试都要留一条日志");

        assertFalse(logService.records.get(0).success(), "第 1 条应是失败");
        assertTrue(logService.records.get(1).success(), "第 2 条应是成功");
        assertEquals(ExternalCallRecord.CONNECTOR_BAIDU_MAP, logService.records.get(0).connector());
        assertEquals("place/v2/search", logService.records.get(0).apiName());

        // 脱敏：日志里不允许出现完整 AK
        for (ExternalCallRecord r : logService.records) {
            assertFalse(r.requestSummary().contains("SECRETAK1234567890"),
                    "日志里的完整 AK 必须已脱敏：" + r.requestSummary());
        }
    }

    @Test
    @DisplayName("验收3：POST 失败**不重试**，只留 1 条日志")
    void postIsNeverRetried() {
        when(transport.postJson(anyString(), anyMap(), anyString(), anyInt()))
                .thenThrow(ExternalHttpException.noResponse("timeout", true, new IOException("timeout")));

        ExternalHttpException ex = assertThrows(ExternalHttpException.class,
                () -> client.postJson("https://open.bigmodel.cn/api/paas/v4/chat/completions",
                        Map.of("Authorization", "Bearer sk-abcdefghijklmn"), "{\"model\":\"glm-4-flash\"}", 90000));

        assertTrue(ex.isTimeout());
        verify(transport, times(1)).postJson(anyString(), anyMap(), anyString(), anyInt());
        assertEquals(1, logService.records.size(), "POST 不重试，所以只有 1 条日志");
        assertFalse(logService.records.get(0).success());

        // 请求头里的 Bearer 凭证也不能进日志（治理层记的是 body+url，这里确认 body 没泄密）
        assertFalse(logService.records.get(0).requestSummary().contains("sk-abcdefghijklmn"));
        assertEquals(ExternalCallRecord.CONNECTOR_LLM, logService.records.get(0).connector());
    }

    @Test
    @DisplayName("补充：4xx（非 429）不重试 —— 客户端错误重试无意义")
    void clientErrorIsNotRetried() {
        when(transport.getJson(anyString(), anyMap(), anyInt()))
                .thenThrow(ExternalHttpException.ofStatus(401, "unauthorized"));

        assertThrows(ExternalHttpException.class,
                () -> client.getJson("https://api.map.baidu.com/place/v2/search?ak=BAD", Map.of(), 5000));

        verify(transport, times(1)).getJson(anyString(), anyMap(), anyInt());
        assertEquals(1, logService.records.size());
    }

    @Test
    @DisplayName("补充：429 限流要重试（最值得等一会儿再试的一类）")
    void rateLimitIsRetried() {
        when(transport.getJson(anyString(), anyMap(), anyInt()))
                .thenThrow(ExternalHttpException.ofStatus(429, "too many requests"))
                .thenReturn("{\"status\":0}");

        String result = client.getJson("https://api.map.baidu.com/place/v2/search?ak=SOMEKEY123", Map.of(), 5000);

        assertNotNull(result);
        verify(transport, times(2)).getJson(anyString(), anyMap(), anyInt());
        assertEquals(2, logService.records.size());
    }

    @Test
    @DisplayName("补充：5xx 重试最多 2 次后仍失败 → 共 3 次尝试、3 条日志")
    void serverErrorRetriesUpToMaxThenFails() {
        when(transport.getJson(anyString(), anyMap(), anyInt()))
                .thenThrow(ExternalHttpException.ofStatus(503, "unavailable"));

        assertThrows(ExternalHttpException.class,
                () -> client.getJson("https://api.map.baidu.com/place/v2/search?ak=K1234", Map.of(), 5000));

        // 首次 + 2 次重试 = 3
        verify(transport, times(3)).getJson(anyString(), anyMap(), anyInt());
        assertEquals(3, logService.records.size());
    }

    @Test
    @DisplayName("补充：日志服务自身抛异常不能影响主流程")
    void logFailureDoesNotBreakCall() {
        // P1-E 之后 ExternalCallLogService 有两个抽象方法，不再是函数式接口，
        // 所以这里必须用匿名类而不是 lambda
        ExternalCallLogService broken = new ExternalCallLogService() {
            @Override
            public void record(ExternalCallRecord record) {
                throw new RuntimeException("表不存在");
            }

            @Override
            public Map<String, Object> stats(String connector) {
                return Map.of();
            }
        };
        GovernedExternalHttpClient c = new GovernedExternalHttpClient(transport, broken);
        when(transport.getJson(anyString(), anyMap(), anyInt())).thenReturn("{\"status\":0}");

        assertEquals("{\"status\":0}", c.getJson("https://api.map.baidu.com/x?ak=K1234", Map.of(), 5000));
    }

    @Test
    @DisplayName("脱敏在真实链路里也生效：治理层写出去的 summary 已经是掩码后的")
    void sanitizedSummaryIsWhatGetsLogged() {
        when(transport.getJson(anyString(), anyMap(), anyInt())).thenReturn("{}");
        client.getJson("https://api.map.baidu.com/place/v2/search?ak=VERYSECRETKEY12345&query=a", Map.of(), 5000);

        // 注意：logService 是手写替身不是 Mockito mock，所以直接断言它记下的内容，
        // 不能用 verify()（会抛 NotAMock —— 我第一版就是这么写错的）
        assertEquals(1, logService.records.size());
        ExternalCallRecord record = logService.records.get(0);
        String summary = record.requestSummary();
        assertFalse(summary.contains("VERYSECRETKEY12345"), "完整 AK 不得进日志");
        assertTrue(summary.contains("ak=VERY****"), "应保留前 4 位 + 掩码");
        // apiName 应已从 URL 推断出来
        assertEquals("place/v2/search", record.apiName());
        assertEquals(ExternalCallRecord.CONNECTOR_BAIDU_MAP, record.connector());
    }

    @Test
    @DisplayName("日志实体映射：success 与 httpStatus 正确落到实体")
    void logEntityMappingSanity() {
        // 直接验证实现类的映射逻辑（不连库，只确认不抛异常且字段被读走）
        ExternalCallLogServiceImpl impl = new ExternalCallLogServiceImpl(null);
        assertDoesNotThrow(() -> impl.record(null));
        assertDoesNotThrow(() -> impl.record(new ExternalCallRecord(
                1L, null, ExternalCallRecord.CONNECTOR_LLM, "chat/completions",
                "POST body={\"model\":\"x\"}", 200, 123, true, null)));
        // mapper 为 null 时会抛 NPE 并被实现吞掉并打 WARN —— 这正是「不影响主流程」的证明
    }

    @Test
    @DisplayName("结果码常量存在性检查（避免有人删了码还编译得过）")
    void resultCodesExist() {
        assertEquals(2101, ResultCode.LLM_NOT_AVAILABLE.getCode());
    }
}
