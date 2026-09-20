package com.wayfare.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.ResultCode;
import com.wayfare.connector.llm.LlmCallContext;
import com.wayfare.connector.llm.LlmCapabilityResolver;
import com.wayfare.connector.llm.LlmInfo;
import com.wayfare.connector.llm.LlmProvider;
import com.wayfare.connector.llm.LlmUsage;
import com.wayfare.connector.llm.ResolvedLlm;
import com.wayfare.connector.map.MapCapabilityResolver;
import com.wayfare.connector.map.MapMode;
import com.wayfare.connector.map.PoiDTO;
import com.wayfare.connector.map.ResolvedMap;
import com.wayfare.dto.IntentDTO;
import com.wayfare.profile.ProfileOverrides;
import com.wayfare.profile.ProfileRenderer;
import com.wayfare.service.AiLogService;
import com.wayfare.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 意图解析单元测试（P3-A 验收 2/3/4 的可重复版本）。
 *
 * <p><b>为什么这一块用 mock 而不是连真模型</b>：本类要验的是<b>服务端的校验与重试逻辑</b> ——
 * 「模型返回非法枚举时会不会重试」「重试还不行会不会抛异常而不是兜底」。
 * 这些恰恰是真实模型<b>不会稳定复现</b>的（你没法命令 GLM 每次都在 days 上填 9），
 * 所以必须用可编排的假 Provider 来钉住行为。真实模型的验收另见
 * {@link IntentParserLiveTest}（默认跳过，需显式开启）。
 *
 * <p>刻意手工 {@code mock(...)} 而不是 {@code @Mock} + MockitoExtension：
 * 后者的严格模式会因为「某些分支没走到导致 stub 未被使用」而失败，
 * 而本类每个测试只走一条分支，属于正常现象。
 */
class IntentParserTest {

    private static final String KEY_MAX_DAYS = "trip.max-days";

    private LlmCapabilityResolver llmResolver;
    private MapCapabilityResolver mapResolver;
    private AiLogService aiLogService;
    private SysConfigService sysConfigService;

    private IntentParser parser;

    @BeforeEach
    void setUp() {
        llmResolver = mock(LlmCapabilityResolver.class);
        mapResolver = mock(MapCapabilityResolver.class);
        aiLogService = mock(AiLogService.class);
        sysConfigService = mock(SysConfigService.class);

        // ProfileRenderer 不 mock：它是纯函数组件（无依赖），mock 掉反而验不到真实的画像渲染
        parser = new IntentParser(llmResolver, mapResolver, new ProfileRenderer(),
                aiLogService, sysConfigService, new ObjectMapper());

        when(sysConfigService.getInt(anyString(), anyInt())).thenReturn(5);
    }

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("完整合法 json → 全字段正确落进 IntentDTO")
    void parsesFullySpecifiedIntent() {
        FakeLlmProvider llm = stubLlm("""
                {"destination":"大同","days":3,"startDate":"2026-10-01","budgetTotal":1500,
                 "budgetMode":"TOTAL","transport":"PUBLIC","companion":"一个人",
                 "preferenceTags":["古建筑","博物馆"],"pace":2,"dietaryOverrides":["不吃辣"],
                 "needConfirm":[],"confidence":0.9}
                """);

        IntentDTO intent = parser.parseIntent("三天，想去大同看古建和博物馆，不吃辣，一个人，预算 1500",
                null, null, estimatedMap());

        assertEquals("大同", intent.getDestination());
        assertEquals(3, intent.getDays());
        assertEquals(LocalDate.of(2026, 10, 1), intent.getStartDate());
        assertEquals(0, new BigDecimal("1500").compareTo(intent.getBudgetTotal()));
        assertEquals(IntentDTO.BUDGET_MODE_TOTAL, intent.getBudgetMode());
        assertEquals(IntentDTO.TRANSPORT_PUBLIC, intent.getTransport());
        assertEquals("一个人", intent.getCompanion());
        assertEquals(List.of("古建筑", "博物馆"), intent.getPreferenceTags());
        assertEquals(IntentDTO.PACE_NORMAL, intent.getPace());
        assertEquals(List.of("不吃辣"), intent.getDietaryOverrides());
        assertEquals(0.9, intent.getConfidence());
        assertEquals(1, llm.callCount());
    }

    @Test
    @DisplayName("模型返回的 needConfirm 原样保留，不被覆盖")
    void keepsModelNeedConfirm() {
        stubLlm("""
                {"destination":"寿阳","days":2,"preferenceTags":["古建筑"],"budgetTotal":500,
                 "budgetMode":"TOTAL","needConfirm":["companion"],"confidence":0.6}
                """);

        IntentDTO intent = parser.parseIntent("周末想去寿阳玩两天，喜欢古建筑，预算 500",
                null, null, estimatedMap());

        // 模型标的 companion 必须还在；同时服务端补的 budgetMode 也要在（见下一个测试）
        assertNotNull(intent.getNeedConfirm());
        assertTrue(intent.getNeedConfirm().contains("companion"), "模型标的字段被弄丢了");
    }

    @Test
    @DisplayName("验收 2：填了预算却没标口径 → 服务端补进 needConfirm")
    void addsBudgetModeToNeedConfirmWhenBudgetPresent() {
        stubLlm("""
                {"destination":"寿阳","days":2,"preferenceTags":["古建筑"],"budgetTotal":500,
                 "budgetMode":"TOTAL","needConfirm":[],"confidence":0.6}
                """);

        IntentDTO intent = parser.parseIntent("周末想去寿阳玩两天，喜欢古建筑，预算 500",
                null, null, estimatedMap());

        assertTrue(intent.getNeedConfirm().contains(IntentDTO.FIELD_BUDGET_MODE),
                "预算口径没被要求确认 —— 人均/总计会让 P3-E 的预算校验得出相反结论");
    }

    @Test
    @DisplayName("没有预算时不无中生有地要求确认口径")
    void doesNotAskBudgetModeWhenNoBudget() {
        stubLlm("""
                {"destination":"寿阳","days":2,"preferenceTags":["古建筑"],"needConfirm":[]}
                """);

        IntentDTO intent = parser.parseIntent("想去寿阳玩两天", null, null, estimatedMap());

        assertNull(intent.getNeedConfirm(), "没填预算却要求确认口径，是凭空制造的打扰");
    }

    @Test
    @DisplayName("模型用 markdown 代码块包 JSON → 容忍，不浪费一次重试")
    void toleratesMarkdownFence() {
        FakeLlmProvider llm = stubLlm("""
                ```json
                {"destination":"寿阳","days":2,"needConfirm":[]}
                ```
                """);

        IntentDTO intent = parser.parseIntent("寿阳两天", null, null, estimatedMap());

        assertEquals("寿阳", intent.getDestination());
        assertEquals(1, llm.callCount(), "仅仅因为包了代码块就重试，是白花钱");
    }

    @Test
    @DisplayName("模型用 budgetTotal=0 当「不知道」的占位符 → 按未提供处理，不当成零预算")
    void treatsZeroBudgetAsAbsent() {
        stubLlm("{\"destination\":\"寿阳\",\"days\":2,\"budgetTotal\":0,\"needConfirm\":[]}");

        IntentDTO intent = parser.parseIntent("寿阳两天", null, null, estimatedMap());

        assertNull(intent.getBudgetTotal(), "0 被当成真预算 → P3-E 会判定任何花费都超支");
        assertNull(intent.getNeedConfirm(), "没有真预算就不该要求确认口径");
    }

    // ==================== 校验与重试 ====================

    @Test
    @DisplayName("枚举越界 → 重试 1 次（错误信息回喂）→ 第二次修好了就通过")
    void retriesOnceWithViolationFeedback() {
        FakeLlmProvider llm = stubLlm(
                """
                {"destination":"寿阳","days":2,"transport":"飞机","needConfirm":[]}
                """,
                """
                {"destination":"寿阳","days":2,"transport":"PUBLIC","needConfirm":[]}
                """);

        IntentDTO intent = parser.parseIntent("寿阳两天", null, null, estimatedMap());

        assertEquals(IntentDTO.TRANSPORT_PUBLIC, intent.getTransport());
        assertEquals(2, llm.callCount(), "应当恰好重试 1 次");

        String secondPrompt = llm.userPromptAt(1);
        assertTrue(secondPrompt.contains("transport"), "第二次的 prompt 里必须点名出错的字段");
        assertTrue(secondPrompt.contains("飞机"), "必须把模型原来填的非法值回喂，否则它只能靠猜");
    }

    @Test
    @DisplayName("验收 3：两次都非法 → 抛 SCHEMA_INVALID，而不是返回一个错误的 IntentDTO")
    void throwsSchemaInvalidAfterRetryExhausted() {
        FakeLlmProvider llm = stubLlm(
                "{\"destination\":\"寿阳\",\"days\":2,\"budgetMode\":\"person\",\"needConfirm\":[]}",
                "{\"destination\":\"寿阳\",\"days\":2,\"budgetMode\":\"person\",\"needConfirm\":[]}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> parser.parseIntent("寿阳两天", null, null, estimatedMap()));

        assertEquals(ResultCode.SCHEMA_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("budgetMode"), "异常信息要能指出到底哪个字段不合法");
        assertEquals(2, llm.callCount(), "重试 1 次 = 总共 2 次，不许无限重试");
    }

    @Test
    @DisplayName("用户没说目的地 → 解析成功，destination 记入 needConfirm（不逼模型编一个地名）")
    void marksMissingDestinationForConfirm() {
        FakeLlmProvider llm = stubLlm("{\"days\":2,\"pace\":1,\"needConfirm\":[]}");

        IntentDTO intent = parser.parseIntent("两天，带爸妈，走不动，想轻松点", null, null, estimatedMap());

        assertNull(intent.getDestination());
        assertEquals(2, intent.getDays());
        assertTrue(intent.getNeedConfirm().contains(IntentDTO.FIELD_DESTINATION));
        assertEquals(1, llm.callCount(),
                "目的地缺失不该触发重试 —— 重试只会逼模型编一个地名出来（违反铁律一）");
    }

    @Test
    @DisplayName("destination 与 days 都没解析出来 → 判为解析失败，不伪装成「成功但全空」")
    void rejectsCompletelyEmptyIntent() {
        stubLlm("{}", "{}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> parser.parseIntent("随便玩玩", null, null, estimatedMap()));

        assertEquals(ResultCode.SCHEMA_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("days 超过 trip.max-days → 重试后仍超 → SCHEMA_INVALID（不是悄悄截断成 5）")
    void rejectsDaysExceedingMax() {
        stubLlm("{\"destination\":\"大同\",\"days\":9}", "{\"destination\":\"大同\",\"days\":9}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> parser.parseIntent("大同玩九天", null, null, estimatedMap()));

        assertEquals(ResultCode.SCHEMA_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("1 ~ 5"), "错误信息要说明允许范围，实际是：" + ex.getMessage());
    }

    @Test
    @DisplayName("days 写成「两天」这种中文 → 判为类型错误，不接受猜测")
    void rejectsNonNumericDays() {
        stubLlm("{\"destination\":\"寿阳\",\"days\":\"两天\"}", "{\"destination\":\"寿阳\",\"days\":\"两天\"}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> parser.parseIntent("寿阳两天", null, null, estimatedMap()));

        assertEquals(ResultCode.SCHEMA_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("必须是数字"));
    }

    @Test
    @DisplayName("days 写成 \"2\"（带引号的数字）→ 容忍，不折腾模型")
    void acceptsQuotedNumericDays() {
        stubLlm("{\"destination\":\"寿阳\",\"days\":\"2\",\"needConfirm\":[]}");

        IntentDTO intent = parser.parseIntent("寿阳两天", null, null, estimatedMap());

        assertEquals(2, intent.getDays());
    }

    @Test
    @DisplayName("完全不是 JSON → 重试后抛 SCHEMA_INVALID")
    void rejectsNonJsonOutput() {
        stubLlm("抱歉，我无法理解这个需求。", "抱歉，我无法理解这个需求。");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> parser.parseIntent("寿阳两天", null, null, estimatedMap()));

        assertEquals(ResultCode.SCHEMA_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("confidence 越界（1.5）→ 判为非法")
    void rejectsOutOfRangeConfidence() {
        stubLlm("{\"destination\":\"寿阳\",\"days\":2,\"confidence\":1.5}",
                "{\"destination\":\"寿阳\",\"days\":2,\"confidence\":1.5}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> parser.parseIntent("寿阳两天", null, null, estimatedMap()));

        assertEquals(ResultCode.SCHEMA_INVALID.getCode(), ex.getCode());
    }

    // ==================== 地图联动（铁律一） ====================

    @Test
    @DisplayName("地图 VERIFIED → 用地图检索结果补上目的地坐标")
    void fillsDestinationCoordinatesFromMap() {
        stubLlm("{\"destination\":\"寿阳\",\"days\":2,\"needConfirm\":[]}");
        stubMapVerified(List.of(poi("寿阳县", 113.177, 37.895)));

        IntentDTO intent = parser.parseIntent("寿阳两天", null, null, verifiedMap());

        assertEquals(113.177, intent.getDestLng());
        assertEquals(37.895, intent.getDestLat());
        assertTrue(intent.hasDestinationLocation());
    }

    @Test
    @DisplayName("地图关闭（ESTIMATED）→ 坐标保持 null，绝不填假坐标")
    void leavesCoordinatesNullWhenMapDisabled() {
        stubLlm("{\"destination\":\"寿阳\",\"days\":2,\"needConfirm\":[]}");

        IntentDTO intent = parser.parseIntent("寿阳两天", null, null, estimatedMap());

        assertNull(intent.getDestLng());
        assertNull(intent.getDestLat());
        assertFalse(intent.hasDestinationLocation());
        verify(mapResolver, never()).call(any(), any());
    }

    @Test
    @DisplayName("地图 VERIFIED 但检索不到目的地 → 记入 needConfirm，而不是编一个坐标")
    void marksDestinationForConfirmWhenNotFound() {
        stubLlm("{\"destination\":\"不存在的地方\",\"days\":2,\"needConfirm\":[]}");
        stubMapVerified(List.of());

        IntentDTO intent = parser.parseIntent("去不存在的地方两天", null, null, verifiedMap());

        assertNull(intent.getDestLng());
        assertTrue(intent.getNeedConfirm().contains(IntentDTO.FIELD_DESTINATION));
    }

    // ==================== 大模型侧故障 ====================

    @Test
    @DisplayName("大模型超时 → 如实抛超时，不伪装成 SCHEMA_INVALID，也不重试")
    void propagatesLlmFailureWithoutMasking() {
        when(llmResolver.execute(any())).thenThrow(
                new com.wayfare.connector.llm.LlmException(ResultCode.LLM_TIMEOUT, "glm", "调用超时"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> parser.parseIntent("寿阳两天", null, null, estimatedMap()));

        assertEquals(ResultCode.LLM_TIMEOUT.getCode(), ex.getCode(),
                "把「大模型超时」报成「需求不合法」会让用户以为是自己的输入有问题");
        verify(llmResolver, times(1)).execute(any());
    }

    // ==================== 日志 ====================

    @Test
    @DisplayName("写 PARSE 阶段日志，token 是两次尝试的累加（成本要按真实花费记）")
    void recordsParseStageWithAccumulatedTokens() {
        stubLlm(
                "{\"destination\":\"寿阳\",\"days\":2,\"transport\":\"飞机\"}",
                "{\"destination\":\"寿阳\",\"days\":2,\"transport\":\"WALK\"}");

        parser.parseIntent("寿阳两天", null, null, estimatedMap());

        ArgumentCaptor<AiStageRecord> captor = ArgumentCaptor.forClass(AiStageRecord.class);
        verify(aiLogService).recordStage(captor.capture());
        AiStageRecord record = captor.getValue();

        assertEquals(AiStageRecord.STAGE_PARSE, record.stage());
        assertTrue(record.success());
        assertNull(record.errorCode());
        // FakeLlmProvider 每次报 100/20，两次 → 200/40
        assertEquals(200, record.promptTokens());
        assertEquals(40, record.completionTokens());
        assertEquals(240, record.totalTokens());
        assertNotNull(record.durationMs());
    }

    @Test
    @DisplayName("解析失败时日志里记 LLM_PARSE_FAIL 与具体原因")
    void recordsFailureStageWithErrorCode() {
        stubLlm("{}", "{}");

        assertThrows(BusinessException.class,
                () -> parser.parseIntent("寿阳两天", null, null, estimatedMap()));

        ArgumentCaptor<AiStageRecord> captor = ArgumentCaptor.forClass(AiStageRecord.class);
        verify(aiLogService).recordStage(captor.capture());
        AiStageRecord record = captor.getValue();

        assertFalse(record.success());
        assertEquals(AiErrorCode.LLM_PARSE_FAIL, record.errorCode());
        assertTrue(record.errorMsg().contains("destination"));
    }

    // ==================== 画像注入 ====================

    @Test
    @DisplayName("有画像时把画像块拼进 system prompt")
    void injectsProfileBlock() {
        FakeLlmProvider llm = stubLlm("{\"destination\":\"寿阳\",\"days\":2,\"needConfirm\":[]}");

        com.wayfare.entity.UserTravelProfile profile = new com.wayfare.entity.UserTravelProfile();
        profile.setTaboos("香菜");
        profile.setPace(1);
        profile.setAllowAiUse(1);

        parser.parseIntent("寿阳两天", profile, null, estimatedMap());

        String systemPrompt = llm.systemPromptAt(0);
        assertTrue(systemPrompt.contains("【用户画像】"));
        assertTrue(systemPrompt.contains("香菜"));
    }

    @Test
    @DisplayName("用户关掉 AI 使用画像（allowAiUse=0）→ 画像不进 prompt（铁律三）")
    void respectsPrivacySwitch() {
        FakeLlmProvider llm = stubLlm("{\"destination\":\"寿阳\",\"days\":2,\"needConfirm\":[]}");

        com.wayfare.entity.UserTravelProfile profile = new com.wayfare.entity.UserTravelProfile();
        profile.setTaboos("香菜");
        profile.setAllowAiUse(0);

        parser.parseIntent("寿阳两天", profile, null, estimatedMap());

        String systemPrompt = llm.systemPromptAt(0);
        assertFalse(systemPrompt.contains("香菜"), "隐私开关被绕过了 —— 用户关掉画像却仍被注入");
        assertFalse(systemPrompt.contains("【用户画像】"));
    }

    @Test
    @DisplayName("隐私开关关掉时，本次临时条件仍要注入（那是用户这次亲口说的）")
    void stillInjectsOverridesWhenPrivacyOff() {
        FakeLlmProvider llm = stubLlm("{\"destination\":\"寿阳\",\"days\":2,\"needConfirm\":[]}");

        com.wayfare.entity.UserTravelProfile profile = new com.wayfare.entity.UserTravelProfile();
        profile.setTaboos("香菜");
        profile.setAllowAiUse(0);

        parser.parseIntent("寿阳两天", profile, new ProfileOverrides(List.of("花生"), null), estimatedMap());

        String systemPrompt = llm.systemPromptAt(0);
        assertFalse(systemPrompt.contains("香菜"), "长期画像不该被注入");
        assertTrue(systemPrompt.contains("花生"), "本次临时忌口是用户这次说的，必须注入");
    }

    // ==================== 测试替身 ====================

    /** 用一批预设回复喂给解析器；回复用完后重复最后一条 */
    private FakeLlmProvider stubLlm(String... replies) {
        FakeLlmProvider provider = new FakeLlmProvider(List.of(replies));
        when(llmResolver.execute(any())).thenAnswer(invocation -> {
            Function<LlmProvider, String> action = invocation.getArgument(0);
            String value = action.apply(provider);
            return new LlmCapabilityResolver.LlmCallResult<>(value, ResolvedLlm.primary(provider), List.of());
        });
        return provider;
    }

    /** 地图可用时的调用结果（不走真实检索逻辑 —— 那是 P1-C 的测试范围） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubMapVerified(List<PoiDTO> pois) {
        when(mapResolver.call(any(), any())).thenReturn(
                (MapCapabilityResolver.MapCallResult) new MapCapabilityResolver.MapCallResult<>(
                        pois, MapMode.VERIFIED, "baidu", null));
    }

    private ResolvedMap estimatedMap() {
        return new ResolvedMap(MapMode.ESTIMATED, null, "地图连接器已关闭");
    }

    private ResolvedMap verifiedMap() {
        return new ResolvedMap(MapMode.VERIFIED, null, null);
    }

    private PoiDTO poi(String name, Double lng, Double lat) {
        return new PoiDTO("uid-" + name, name, "地址", lng, lat, "旅游景点", null, null, null, null);
    }

    /**
     * 假的大模型 Provider：按序吐出预设回复，并记录每次收到的 prompt。
     * 不继承任何真实实现，避免把 P1-B 的行为混进来。
     */
    private static final class FakeLlmProvider implements LlmProvider {

        private final List<String> replies;
        private final List<String> userPrompts = new ArrayList<>();
        private final List<String> systemPrompts = new ArrayList<>();
        private int cursor = 0;

        FakeLlmProvider(List<String> replies) {
            this.replies = replies;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public LlmInfo info() {
            return LlmInfo.available("fake", "fake-model");
        }

        @Override
        public String chat(String systemPrompt, String userPrompt, LlmCallContext context) {
            return nextReply(systemPrompt, userPrompt, context);
        }

        @Override
        public String chatJson(String systemPrompt, String userPrompt, String jsonSchemaHint, LlmCallContext context) {
            return nextReply(systemPrompt, userPrompt, context);
        }

        @Override
        public void chatStream(String systemPrompt, String userPrompt,
                               Consumer<String> onDelta, Runnable onDone, Consumer<Throwable> onError) {
            throw new UnsupportedOperationException("本测试不需要流式");
        }

        private String nextReply(String systemPrompt, String userPrompt, LlmCallContext context) {
            systemPrompts.add(systemPrompt);
            userPrompts.add(userPrompt);
            if (context != null) {
                context.reportUsage(new LlmUsage(100, 20, 120));
            }
            String reply = replies.get(Math.min(cursor, replies.size() - 1));
            cursor++;
            return reply;
        }

        int callCount() {
            return cursor;
        }

        String userPromptAt(int index) {
            return userPrompts.get(index);
        }

        String systemPromptAt(int index) {
            return systemPrompts.get(index);
        }
    }
}
