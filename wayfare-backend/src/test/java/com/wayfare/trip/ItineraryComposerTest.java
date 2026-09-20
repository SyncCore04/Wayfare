package com.wayfare.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wayfare.connector.llm.LlmCallContext;
import com.wayfare.connector.llm.LlmCapabilityResolver;
import com.wayfare.connector.llm.LlmException;
import com.wayfare.connector.llm.LlmInfo;
import com.wayfare.connector.llm.LlmProvider;
import com.wayfare.connector.llm.LlmUsage;
import com.wayfare.connector.llm.ResolvedLlm;
import com.wayfare.connector.map.MapMode;
import com.wayfare.connector.map.ResolvedMap;
import com.wayfare.dto.CandidateDTO;
import com.wayfare.dto.IntentDTO;
import com.wayfare.dto.TripDraftDTO;
import com.wayfare.entity.TripItem;
import com.wayfare.entity.UserTravelProfile;
import com.wayfare.profile.ProfileOverrides;
import com.wayfare.profile.ProfileRenderer;
import com.wayfare.service.AiLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 行程编排单元测试（P3-D）。
 *
 * <p>用假 Provider 钉住行为，验的是<b>服务端的校验、重试、失败降级、以及提示词里那 8 条硬约束
 * 是否真的写进去了</b>。真实模型的表现另见 {@link ItineraryComposerLiveTest}（默认跳过）。
 */
class ItineraryComposerTest {

    private LlmCapabilityResolver llmResolver;
    private AiLogService aiLogService;
    private ItineraryComposer composer;

    @BeforeEach
    void setUp() {
        llmResolver = mock(LlmCapabilityResolver.class);
        aiLogService = mock(AiLogService.class);
        composer = new ItineraryComposer(llmResolver, new ProfileRenderer(),
                new CandidateSerializer(), aiLogService, new ObjectMapper());
    }

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("合法输出 → 解析成功，字段完整落进 TripDraftDTO")
    void parsesValidDraft() {
        stubLlm(validDraft());

        ComposeResult result = compose(poolWithFood(), 2, IntentDTO.PACE_NORMAL);

        assertTrue(result.success());
        assertNotNull(result.draft());
        TripDraftDTO draft = result.draft();
        assertEquals("寿阳古建两日慢行", draft.getTitle());
        assertEquals(2, draft.getDays().size());

        TripDraftDTO.DayDraft day1 = draft.getDays().get(0);
        assertEquals(1, day1.getDayIndex());
        assertEquals(2, day1.getItems().size());
        assertEquals("冷泉寺", day1.getItems().get(0).getPoiRef());
        assertEquals(TripItem.TYPE_SCENIC, day1.getItems().get(0).getItemType());
        assertEquals("09:00", day1.getItems().get(0).getStartTime());
        assertEquals(90, day1.getItems().get(0).getStayMinutes());
        assertNotNull(day1.getItems().get(0).getReason());
    }

    @Test
    @DisplayName("重试：第一次天数不对 → 回喂错误 → 第二次修正后成功")
    void retriesOnceWithViolationFeedback() {
        FakeLlmProvider llm = stubLlm(
                """
                {"title":"x","days":[{"dayIndex":1,"title":"t","summary":"s","items":[
                  {"poiRef":"冷泉寺","itemType":"SCENIC","startTime":"09:00","endTime":"10:30","stayMinutes":90,"reason":"r"},
                  {"poiRef":"老张面馆","itemType":"FOOD","startTime":"11:30","endTime":"12:30","stayMinutes":60,"reason":"r"}]}]}
                """,
                validDraft());

        ComposeResult result = compose(poolWithFood(), 2, IntentDTO.PACE_NORMAL);

        assertTrue(result.success(), "重试后应当成功");
        assertEquals(2, llm.callCount(), "应当恰好重试 1 次");
        assertTrue(llm.userPromptAt(1).contains("天数不对"),
                "第二次的 prompt 必须把「天数不对」这个具体原因回喂，否则模型只能靠猜");
    }

    // ==================== 校验失败 → 不抛异常，返回失败结果 ====================

    @Test
    @DisplayName("两次都天数不对 → 返回失败结果，**不抛异常**（候选池要留给前端手选）")
    void returnsFailureWithoutThrowingWhenDaysWrong() {
        stubLlm(oneDayDraft(), oneDayDraft());

        ComposeResult result = compose(poolWithFood(), 2, IntentDTO.PACE_NORMAL);

        assertFalse(result.success());
        assertNull(result.draft());
        assertEquals(AiErrorCode.LLM_PARSE_FAIL, result.errorCode());
        assertTrue(result.errorMessage().contains("天数"), "错误信息要说清原因：" + result.errorMessage());
    }

    @Test
    @DisplayName("itemType 非法 → 失败")
    void rejectsInvalidItemType() {
        String bad = validDraft().replace("\"itemType\":\"SCENIC\"", "\"itemType\":\"SHOPPING\"");
        stubLlm(bad, bad);

        ComposeResult result = compose(poolWithFood(), 2, IntentDTO.PACE_NORMAL);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("itemType"));
    }

    @Test
    @DisplayName("时间格式不是 HH:mm → 失败")
    void rejectsBadTimeFormat() {
        String bad = validDraft().replace("\"startTime\":\"09:00\"", "\"startTime\":\"9点\"");
        stubLlm(bad, bad);

        ComposeResult result = compose(poolWithFood(), 2, IntentDTO.PACE_NORMAL);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("startTime"));
    }

    @Test
    @DisplayName("poiRef 为空 → 失败（它是防幻觉的落点，不能空）")
    void rejectsEmptyPoiRef() {
        String bad = validDraft().replace("\"poiRef\":\"冷泉寺\"", "\"poiRef\":\"\"");
        stubLlm(bad, bad);

        ComposeResult result = compose(poolWithFood(), 2, IntentDTO.PACE_NORMAL);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("poiRef"));
    }

    @Test
    @DisplayName("reason 为空 → 失败（每个 item 必须写理由）")
    void rejectsEmptyReason() {
        String bad = validDraft().replace("\"reason\":\"上午光线好\"", "\"reason\":\"\"");
        stubLlm(bad, bad);

        ComposeResult result = compose(poolWithFood(), 2, IntentDTO.PACE_NORMAL);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("reason"));
    }

    @Test
    @DisplayName("候选池里有餐饮，但某天没有 FOOD → 失败")
    void rejectsDayWithoutFood() {
        String bad = validDraft()
                .replace("\"itemType\":\"FOOD\"", "\"itemType\":\"SCENIC\"");
        stubLlm(bad, bad);

        ComposeResult result = compose(poolWithFood(), 2, IntentDTO.PACE_NORMAL);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("FOOD"), result.errorMessage());
    }

    @Test
    @DisplayName("候选池里**没有**餐饮候选时，不强制每天有 FOOD（池里没有就不该逼模型编）")
    void doesNotRequireFoodWhenPoolHasNone() {
        String noFood = validDraft().replace("\"itemType\":\"FOOD\"", "\"itemType\":\"SCENIC\"");
        stubLlm(noFood);

        ComposeResult result = compose(poolWithoutFood(), 2, IntentDTO.PACE_NORMAL);

        assertTrue(result.success(), "池里没有餐饮却要求有 FOOD，会逼模型编造：" + result.errorMessage());
    }

    // ==================== 铁律一：距离与时长不采信 ====================

    @Test
    @DisplayName("模型输出 distanceMeters/durationSeconds → 一律丢弃，字段保持 null（事实只能来自地图）")
    void discardsModelProvidedDistanceAndDuration() {
        String withDistance = validDraft()
                .replace("\"reason\":\"上午光线好\"",
                        "\"reason\":\"上午光线好\",\"distanceMeters\":1234,\"durationSeconds\":900");
        stubLlm(withDistance);

        ComposeResult result = compose(poolWithFood(), 2, IntentDTO.PACE_NORMAL);

        assertTrue(result.success());
        TripDraftDTO.ItemDraft item = result.draft().getDays().get(0).getItems().get(0);
        assertNull(item.getDistanceMeters(), "模型编的距离被写进结果了");
        assertNull(item.getDurationSeconds(), "模型编的时长被写进结果了");
    }

    // ==================== 前置条件 ====================

    @Test
    @DisplayName("候选池为空 → 返回失败，不抛异常")
    void failsWhenPoolEmpty() {
        ComposeResult result = composer.compose(intent(2, IntentDTO.PACE_NORMAL),
                new CandidatePool(), null, null, null, estimatedMap());

        assertFalse(result.success());
        assertEquals(AiErrorCode.CANDIDATE_SHORTAGE, result.errorCode());
    }

    @Test
    @DisplayName("天数还没确定 → 返回失败，不抛异常、不硬编一个天数")
    void failsWhenDaysMissing() {
        ComposeResult result = composer.compose(intent(null, IntentDTO.PACE_NORMAL),
                poolWithFood(), null, null, null, estimatedMap());

        assertFalse(result.success());
        assertEquals(AiErrorCode.SCHEMA_INVALID, result.errorCode());
    }

    @Test
    @DisplayName("大模型不可用 → 返回失败结果（不是抛异常），并把原因说清楚")
    void failsWhenLlmUnavailable() {
        when(llmResolver.execute(any())).thenThrow(new LlmException(
                com.wayfare.common.result.ResultCode.LLM_TIMEOUT, "qwen", "超时"));

        ComposeResult result = compose(poolWithFood(), 2, IntentDTO.PACE_NORMAL);

        assertFalse(result.success());
        assertEquals(AiErrorCode.LLM_TIMEOUT, result.errorCode());
    }

    // ==================== 提示词里的硬约束（防止有人删掉某条）====================

    @Test
    @DisplayName("system prompt 必须包含手册的 8 条硬约束（逐条断言，漏一条就会放出一类幻觉）")
    void systemPromptContainsAllHardConstraints() {
        FakeLlmProvider llm = stubLlm(validDraft());

        compose(poolWithFood(), 2, IntentDTO.PACE_SLOW);

        String system = llm.systemPromptAt(0);
        assertTrue(system.contains("恰好是 2 天"), "约束1（天数恰好）缺失");
        assertTrue(system.contains("午餐排在 11:30–13:00"), "约束2（时段/午餐/晚餐）缺失");
        assertTrue(system.contains("只能从下面给出的候选池里选点"), "约束3（只能从候选池选点）缺失");
        assertTrue(system.contains("必须写 reason"), "约束4（必须写理由）缺失");
        assertTrue(system.contains("每天 2–3 个点"), "约束5（节奏）缺失，慢节奏应写 2–3 个");
        assertTrue(system.contains("itemType=FOOD"), "约束6（每天至少 1 个餐饮）缺失");
        assertTrue(system.contains("忌口过敏是硬约束"), "约束7（画像硬约束）缺失");
        assertTrue(system.contains("禁止输出精确的距离与时长"), "约束8（地图关闭时的模糊表述）缺失");
    }

    @Test
    @DisplayName("prompt 必须要求 poiRef 写「名称」而不是「编号」—— P3-E 的 CLOSURE 是按名称匹配的")
    void promptRequiresNameNotIndexInPoiRef() {
        FakeLlmProvider llm = stubLlm(validDraft());

        compose(poolWithFood(), 2, IntentDTO.PACE_NORMAL);

        String system = llm.systemPromptAt(0);
        assertTrue(system.contains("照抄候选池里的名称"),
                "没要求照抄名称。实测放开「名称或编号」时模型会全填编号，而 CLOSURE 按名称匹配 → 全被判池外");
        assertTrue(system.contains("不要写编号"), "没明确禁止写编号");
    }

    @Test
    @DisplayName("节奏为「紧凑」时 prompt 里应写 4–5 个点")
    void paceRuleReflectsIntent() {
        FakeLlmProvider llm = stubLlm(validDraft());

        compose(poolWithFood(), 2, IntentDTO.PACE_PACKED);

        assertTrue(llm.systemPromptAt(0).contains("每天 4–5 个点"));
    }

    @Test
    @DisplayName("地图可用时不该出现「禁止输出精确距离」那一段（避免给出矛盾的指令）")
    void omitsMapClosedClauseWhenMapAvailable() {
        FakeLlmProvider llm = stubLlm(validDraft());

        composer.compose(intent(2, IntentDTO.PACE_NORMAL), poolWithFood(), null, null, null, verifiedMap());

        assertFalse(llm.systemPromptAt(0).contains("禁止输出精确的距离与时长"),
                "地图开着却让模型别输出距离，是自相矛盾的指令");
    }

    @Test
    @DisplayName("候选池按 P3-C 的顺序序列化 —— 编号本身就体现空间顺序")
    void serializesPoolInPreOrderSequence() {
        FakeLlmProvider llm = stubLlm(validDraft());
        CandidatePool pool = poolWithFood();
        // 把池子顺序反过来当作「预排结果」
        List<CandidateDTO> reversed = new ArrayList<>(pool.getItems());
        java.util.Collections.reverse(reversed);
        PreOrderResult preOrder = new PreOrderResult(reversed, 0, 0, 0, false, 0);

        composer.compose(intent(2, IntentDTO.PACE_NORMAL), pool, preOrder, null, null, estimatedMap());

        String userPrompt = llm.userPromptAt(0);
        String firstName = reversed.get(0).getName();
        assertTrue(userPrompt.contains("[1] " + firstName),
                "候选池应当按预排顺序编号，实际第一条不是 " + firstName);
    }

    // ==================== 日志 ====================

    @Test
    @DisplayName("写 COMPOSE 阶段日志，token 是多次尝试的累加")
    void recordsComposeStage() {
        stubLlm(oneDayDraft(), validDraft());

        compose(poolWithFood(), 2, IntentDTO.PACE_NORMAL);

        ArgumentCaptor<AiStageRecord> captor = ArgumentCaptor.forClass(AiStageRecord.class);
        verify(aiLogService).recordStage(captor.capture());
        AiStageRecord record = captor.getValue();

        assertEquals(AiStageRecord.STAGE_COMPOSE, record.stage());
        assertTrue(record.success());
        assertEquals(200, record.promptTokens(), "两次尝试的 token 应当累加（100×2）");
        assertEquals(40, record.completionTokens());
    }

    // ==================== 替身与工具 ====================

    private ComposeResult compose(CandidatePool pool, int days, Integer pace) {
        return composer.compose(intent(days, pace), pool, null, null, null, estimatedMap());
    }

    private IntentDTO intent(Integer days, Integer pace) {
        IntentDTO intent = new IntentDTO();
        intent.setDestination("寿阳");
        intent.setDays(days);
        intent.setPace(pace);
        return intent;
    }

    private CandidatePool poolWithFood() {
        return new CandidatePool(List.of(
                cand("冷泉寺", TripItem.TYPE_SCENIC, 113.073119, 37.755288),
                cand("福田寺", TripItem.TYPE_SCENIC, 112.870834, 37.946223),
                cand("老张面馆", TripItem.TYPE_FOOD, 113.17, 37.89)), MapMode.ESTIMATED);
    }

    private CandidatePool poolWithoutFood() {
        return new CandidatePool(List.of(
                cand("冷泉寺", TripItem.TYPE_SCENIC, 113.073119, 37.755288),
                cand("福田寺", TripItem.TYPE_SCENIC, 112.870834, 37.946223)), MapMode.ESTIMATED);
    }

    private CandidateDTO cand(String name, String type, Double lng, Double lat) {
        CandidateDTO dto = new CandidateDTO();
        dto.setName(name);
        dto.setItemType(type);
        dto.setLng(lng);
        dto.setLat(lat);
        return dto;
    }

    private ResolvedMap estimatedMap() {
        return new ResolvedMap(MapMode.ESTIMATED, null, "地图连接器已关闭");
    }

    private ResolvedMap verifiedMap() {
        return new ResolvedMap(MapMode.VERIFIED, null, null);
    }

    /** 一份合法的两天草稿（每天 2 个点、其中 1 个餐饮） */
    private String validDraft() {
        return """
                {"title":"寿阳古建两日慢行",
                 "days":[
                   {"dayIndex":1,"title":"古城寻塔","summary":"避开香菜，选了几家本地面食馆",
                    "items":[
                      {"poiRef":"冷泉寺","itemType":"SCENIC","startTime":"09:00","endTime":"10:30","stayMinutes":90,"costEstimate":30,"reason":"上午光线好"},
                      {"poiRef":"老张面馆","itemType":"FOOD","startTime":"11:30","endTime":"12:30","stayMinutes":60,"costEstimate":40,"reason":"就在上一站附近"}]},
                   {"dayIndex":2,"title":"山寺寻幽","summary":"节奏放慢，留足休息时间",
                    "items":[
                      {"poiRef":"福田寺","itemType":"SCENIC","startTime":"09:30","endTime":"11:30","stayMinutes":120,"costEstimate":20,"reason":"上午人少"},
                      {"poiRef":"老张面馆","itemType":"FOOD","startTime":"12:00","endTime":"13:00","stayMinutes":60,"costEstimate":35,"reason":"口味清淡"}]}
                 ]}
                """;
    }

    private String oneDayDraft() {
        return """
                {"title":"x","days":[
                  {"dayIndex":1,"title":"t","summary":"s","items":[
                    {"poiRef":"冷泉寺","itemType":"SCENIC","startTime":"09:00","endTime":"10:30","stayMinutes":90,"reason":"r"},
                    {"poiRef":"老张面馆","itemType":"FOOD","startTime":"11:30","endTime":"12:30","stayMinutes":60,"reason":"r"}]}]}
                """;
    }

    private FakeLlmProvider stubLlm(String... replies) {
        FakeLlmProvider provider = new FakeLlmProvider(List.of(replies));
        when(llmResolver.execute(any())).thenAnswer(invocation -> {
            Function<LlmProvider, String> action = invocation.getArgument(0);
            String value = action.apply(provider);
            return new LlmCapabilityResolver.LlmCallResult<>(value, ResolvedLlm.primary(provider), List.of());
        });
        return provider;
    }

    /** 假 Provider：按序吐预设回复，并记录收到的 prompt */
    private static final class FakeLlmProvider implements LlmProvider {
        private final List<String> replies;
        private final List<String> systemPrompts = new ArrayList<>();
        private final List<String> userPrompts = new ArrayList<>();
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
            return next(systemPrompt, userPrompt, context);
        }

        @Override
        public String chatJson(String systemPrompt, String userPrompt, String hint, LlmCallContext context) {
            return next(systemPrompt, userPrompt, context);
        }

        @Override
        public void chatStream(String systemPrompt, String userPrompt,
                               Consumer<String> onDelta, Runnable onDone, Consumer<Throwable> onError) {
            throw new UnsupportedOperationException("本测试不需要流式");
        }

        private String next(String systemPrompt, String userPrompt, LlmCallContext context) {
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

        String systemPromptAt(int i) {
            return systemPrompts.get(i);
        }

        String userPromptAt(int i) {
            return userPrompts.get(i);
        }
    }
}
