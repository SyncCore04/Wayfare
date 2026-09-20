package com.wayfare.trip;

import com.wayfare.connector.map.MapMode;
import com.wayfare.dto.CandidateDTO;
import com.wayfare.dto.IntentDTO;
import com.wayfare.dto.TripDraftDTO;
import com.wayfare.entity.TripItem;
import com.wayfare.service.AiLogService;
import com.wayfare.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 约束校验 + 回喂重排循环的单元测试（P3-E 编排部分）。
 *
 * <p>{@link ItineraryComposer} 被 mock —— 本测试只负责验证 {@link ItineraryReplanner}
 * 自己的循环纪律：轮次上限、保留最优、不死循环、撞上限返回风险提示而非抛异常。
 * 校验用真实的 {@link ItineraryValidator}（它无外部依赖）。
 */
class ItineraryReplannerTest {

    private ItineraryComposer composer;
    private SysConfigService sysConfig;
    private AiLogService aiLog;
    private ItineraryReplanner replanner;

    private TripDraftDTO initialDraft;
    private final List<TripDraftDTO> replanDrafts = new ArrayList<>();
    private int replanCallCount;

    @BeforeEach
    void setUp() {
        composer = mock(ItineraryComposer.class);
        sysConfig = mock(SysConfigService.class);
        aiLog = mock(AiLogService.class);
        replanner = new ItineraryReplanner(new ItineraryValidator(), composer, sysConfig, aiLog);
        when(sysConfig.getInt(anyString(), anyInt())).thenReturn(2);
    }

    /** 钉住编排结果：第 1 次调用返回 initial，第 2 次起依次返回 replanDrafts。 */
    private void stubCompose(TripDraftDTO initial, TripDraftDTO... replans) {
        initialDraft = initial;
        replanDrafts.clear();
        replanDrafts.addAll(List.of(replans));
        replanCallCount = 0;
        when(composer.compose(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> ComposeResult.ok(initialDraft));
        when(composer.compose(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    if (replanDrafts.isEmpty()) {
                        return ComposeResult.ok(initialDraft);   // 正常路径不会走到这里
                    }
                    TripDraftDTO d = replanDrafts.get(Math.min(replanCallCount, replanDrafts.size() - 1));
                    replanCallCount++;
                    return ComposeResult.ok(d);
                });
    }

    @Test
    @DisplayName("第一版就通过 → 0 轮重排，直接 resolved")
    void stopsImmediatelyWhenDraftPasses() {
        stubCompose(cleanDay());
        ReplanResult r = enforce();
        assertTrue(r.resolved());
        assertEquals(0, r.replanRounds());
        assertNotNull(r.draft());
        assertEquals(0, r.replanRounds());
    }

    @Test
    @DisplayName("池外景点触发 CLOSURE(HIGH) → 重排 1 轮后得到干净版本")
    void replansOnceWhenClosureViolation() {
        stubCompose(dayWith("寿阳天外来客塔"), cleanDay());
        ReplanResult r = enforce();
        assertTrue(r.resolved(), r.userHint());
        assertEquals(1, r.replanRounds());
    }

    @Test
    @DisplayName("手册验收 4：2 轮都无法通过 → 不抛异常，返回当前最优 + 风险提示 + 轮次=2")
    void capsRoundsAndReturnsBestWithHintInsteadOfThrowing() {
        stubCompose(dayWith("寿阳天外来客塔"), dayWith("编造的探险塔"), dayWith("凭空生成的高塔"));
        ReplanResult r = enforce();
        assertFalse(r.resolved());
        assertEquals(2, r.replanRounds());
        assertNotNull(r.draft(), "撞上限也要把当前最优返回，不能丢");
        assertNotNull(r.userHint(), "要给出『未能自动解决』清单");
        assertTrue(r.report().hasHigh());
    }

    @Test
    @DisplayName("重排反而更差时保留历史最优：HIGH 数不上升")
    void keepsBestWhenReplanIsWorse() {
        when(sysConfig.getInt(anyString(), anyInt())).thenReturn(1);   // 只给 1 轮
        // 初始 1 个 HIGH，重排版 2 个 HIGH —— 新版更差，应保留初始
        stubCompose(dayWith("寿阳天外来客塔"), twoDayWith("编造的探险塔", "凭空生成的高塔"));
        ReplanResult r = enforce();
        assertFalse(r.resolved());
        assertEquals(1, r.replanRounds());
        assertEquals(1, r.report().countOf(Violation.SEVERITY_HIGH),
                "应保留只有 1 个 HIGH 的初始版，而不是更差的 2 个 HIGH 版");
    }

    @Test
    @DisplayName("手册验收 5：纠正 prompt 必须带 CLOSURE 详情并重新贴出候选池名单")
    void feedbackContainsClosureDetailAndPool() {
        stubCompose(dayWith("寿阳天外来客塔"), cleanDay());
        enforce();

        ArgumentCaptor<String> feedback = ArgumentCaptor.forClass(String.class);
        verify(composer).compose(any(), any(), any(), any(), any(), any(), feedback.capture());
        String fb = feedback.getValue();
        assertTrue(fb.contains("CLOSURE"), "要明确点名 CLOSURE 违规：" + fb);
        assertTrue(fb.contains("候选池"), "CLOSURE 违规时必须把候选池名单再贴一遍：" + fb);
        assertTrue(fb.contains("寿阳文庙"), "候选池名单要包含真实点位：" + fb);
        String poolList = fb.substring(fb.indexOf("候选池是："));
        assertFalse(poolList.contains("天外来客塔"), "候选池名单里不能出现编造的点位：" + poolList);
    }

    @Test
    @DisplayName("每轮校验都写一条 VALIDATE 阶段日志；fail 与 success 至少各一条")
    void recordsValidateStageLogs() {
        stubCompose(dayWith("寿阳天外来客塔"), cleanDay());
        enforce();

        ArgumentCaptor<AiStageRecord> captor = ArgumentCaptor.forClass(AiStageRecord.class);
        verify(aiLog, atLeast(2)).recordStage(captor.capture());
        List<AiStageRecord> records = captor.getAllValues();
        assertTrue(records.stream().allMatch(r -> AiStageRecord.STAGE_VALIDATE.equals(r.stage())),
                "全部应为 VALIDATE 阶段日志");
        assertTrue(records.stream().anyMatch(r -> r.success()), "应有一条通过的 VALIDATE 日志");
        assertTrue(records.stream().anyMatch(r -> !r.success()), "应有一条未通过的 VALIDATE 日志");
    }

    @Test
    @DisplayName("编排首次就失败 → 返回 composeFailed（draft=null），不抛异常")
    void returnsComposeFailedWhenFirstComposeFails() {
        when(composer.compose(any(), any(), any(), any(), any(), any()))
                .thenReturn(ComposeResult.fail(AiErrorCode.LLM_PARSE_FAIL, "天数不对"));
        ReplanResult r = enforce();
        assertFalse(r.resolved());
        assertEquals(0, r.replanRounds());
        assertNotNull(r.composeError());
        assertFalse(r.hasDraft());
    }

    // ==================== 工具 ====================

    private ReplanResult enforce() {
        return replanner.enforce(intent(), pool(), null, null, null, null);
    }

    private IntentDTO intent() {
        IntentDTO in = new IntentDTO();
        in.setDestination("寿阳");
        in.setDays(1);
        in.setTransport(IntentDTO.TRANSPORT_MIX);
        return in;
    }

    private CandidatePool pool() {
        return new CandidatePool(List.of(
                cand("寿阳文庙", "SCENIC", 113.07, 37.75),
                cand("大同古城", "SCENIC", 115.00, 37.75),
                cand("老张面馆", "FOOD", 113.08, 37.76),
                cand("冷泉寺", "SCENIC", 113.073119, 37.755288)), MapMode.ESTIMATED);
    }

    private CandidateDTO cand(String name, String type, Double lng, Double lat) {
        CandidateDTO c = new CandidateDTO();
        c.setName(name);
        c.setItemType(type.equals("FOOD") ? TripItem.TYPE_FOOD : TripItem.TYPE_SCENIC);
        c.setLng(lng);
        c.setLat(lat);
        return c;
    }

    /** 干净的单日单点行程（不受预算/时序/密度规则干扰，仅验 CLOSURE） */
    private TripDraftDTO cleanDay() {
        return dayWith("寿阳文庙");
    }

    private TripDraftDTO dayWith(String poiRef) {
        return draft(day(ItemDraft.of(poiRef)));
    }

    private TripDraftDTO twoDayWith(String poiRefA, String poiRefB) {
        return draft(day(ItemDraft.of(poiRefA), ItemDraft.of(poiRefB)));
    }

    private TripDraftDTO draft(TripDraftDTO.DayDraft... days) {
        TripDraftDTO d = new TripDraftDTO();
        d.setTitle("测试行程");
        d.setDays(List.of(days));
        return d;
    }

    private TripDraftDTO.DayDraft day(TripDraftDTO.ItemDraft... items) {
        TripDraftDTO.DayDraft d = new TripDraftDTO.DayDraft();
        d.setDayIndex(1);
        d.setTitle("第1天");
        d.setItems(new ArrayList<>(List.of(items)));
        return d;
    }

    /** 简化构建器，字段从简不影响校验断言 */
    private static final class ItemDraft {
        static TripDraftDTO.ItemDraft of(String poiRef) {
            TripDraftDTO.ItemDraft it = new TripDraftDTO.ItemDraft();
            it.setPoiRef(poiRef);
            it.setItemType(TripItem.TYPE_SCENIC);
            it.setStartTime("09:00");
            it.setEndTime("10:00");
            it.setReason("顺路");
            return it;
        }
    }
}