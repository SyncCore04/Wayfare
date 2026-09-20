package com.wayfare.trip;

import com.wayfare.connector.map.MapMode;
import com.wayfare.dto.CandidateDTO;
import com.wayfare.dto.IntentDTO;
import com.wayfare.dto.TripDraftDTO;
import com.wayfare.entity.TripItem;
import com.wayfare.entity.UserTravelProfile;
import com.wayfare.profile.ProfileOverrides;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 约束校验单元测试（P3-E · Step 5）。直接调 {@link ItineraryValidator} 的包级规则方法，
 * 七条规则各一个正例 + 一个反例（手册验收 1），外加 CLOSURE / TABOO 专项。
 */
class ItineraryValidatorTest {

    private final ItineraryValidator validator = new ItineraryValidator();

    // ==================== 1) CLOSURE 闭包 ====================

    @Test
    @DisplayName("CLOSURE 正例：poiRef 与候选池名称精确一致 → 无违规")
    void closurePassesWhenNameMatches() {
        TripDraftDTO draft = draft(day(1, item("寿阳文庙", "SCENIC", "09:00", "10:30", "顺路")));
        assertTrue(validator.checkClosure(draft, pool()).isEmpty());
    }

    @Test
    @DisplayName("CLOSURE 正例：poiRef 按 uid 匹配候选池 → 无违规")
    void closurePassesByUid() {
        CandidatePool pool = pool();
        pool.getItems().get(0).setPoiUid("uid-001");
        TripDraftDTO draft = draft(day(1, item("uid-001", "SCENIC", "09:00", "10:30", "顺路")));
        assertTrue(validator.checkClosure(draft, pool).isEmpty());
    }

    @Test
    @DisplayName("CLOSURE 正例：poiRef 含候选名称（容忍括号/全半角差异）→ 无违规")
    void closureToleratesNameVariant() {
        TripDraftDTO draft = draft(day(1, item("寿阳文庙（正门）", "SCENIC", "09:00", "10:30", "顺路")));
        assertTrue(validator.checkClosure(draft, pool()).isEmpty());
    }

    @Test
    @DisplayName("CLOSURE 反例（手册验收 2）：手工塞池外景点「寿阳天外来客塔」→ HIGH")
    void closureFlagsHallucinatedPoi() {
        TripDraftDTO draft = draft(day(1, item("寿阳天外来客塔", "SCENIC", "09:00", "10:30", "顺路")));
        List<Violation> vs = validator.checkClosure(draft, pool());
        assertEquals(1, vs.size());
        assertEquals(ItineraryValidator.RULE_CLOSURE, vs.get(0).code());
        assertEquals(Violation.SEVERITY_HIGH, vs.get(0).severity());
        assertEquals("寿阳天外来客塔", vs.get(0).itemRef());
    }

    // ==================== 2) TIME_OVERLAP 时序 ====================

    @Test
    @DisplayName("TIME_OVERLAP 正例：同一天时间严格递增、不重叠、在窗口内 → 无违规")
    void timePassesWhenWellFormed() {
        TripDraftDTO draft = draft(day(1,
                item("寿阳文庙", "SCENIC", "09:00", "10:30", "顺路"),
                item("老张面馆", "FOOD", "11:30", "12:30", "吃午饭")));
        assertTrue(validator.checkTimeOverlap(draft).isEmpty());
    }

    @Test
    @DisplayName("TIME_OVERLAP 反例：下一项开始时间与上一项重叠 → MEDIUM")
    void timeFlagsOverlap() {
        TripDraftDTO draft = draft(day(1,
                item("寿阳文庙", "SCENIC", "09:00", "10:30", "顺路"),
                item("老张面馆", "FOOD", "10:15", "11:30", "吃午饭")));
        List<Violation> vs = validator.checkTimeOverlap(draft);
        assertEquals(Violation.SEVERITY_MEDIUM, vs.get(0).severity());
        assertTrue(vs.stream().anyMatch(v -> v.message().contains("重叠")));
    }

    @Test
    @DisplayName("TIME_OVERLAP 反例：开始时间早于窗口 09:00 → MEDIUM")
    void timeFlagsBeforeWindow() {
        TripDraftDTO draft = draft(day(1, item("寿阳文庙", "SCENIC", "08:00", "10:00", "顺路")));
        List<Violation> vs = validator.checkTimeOverlap(draft);
        assertTrue(vs.stream().anyMatch(v -> v.message().contains("09:00")));
    }

    @Test
    @DisplayName("TIME_OVERLAP 反例：非晚餐项结束时间超过 18:00 → MEDIUM")
    void timeFlagsAfterWindowForNonDinner() {
        TripDraftDTO draft = draft(day(1, item("寿阳文庙", "SCENIC", "16:00", "19:00", "顺路")));
        List<Violation> vs = validator.checkTimeOverlap(draft);
        assertTrue(vs.stream().anyMatch(v -> v.message().contains("18:00")));
    }

    @Test
    @DisplayName("TIME_OVERLAP 豁免：晚餐（FOOD，18:00 后开始）不受窗口上限约束")
    void dinnerExemptFromEndWindow() {
        TripDraftDTO draft = draft(day(1, item("老张面馆", "FOOD", "19:00", "20:00", "晚饭")));
        List<Violation> vs = validator.checkTimeOverlap(draft);
        assertTrue(vs.stream().noneMatch(v -> v.message().contains("18:00") && v.message().contains("结束")));
    }

    // ==================== 3) BACKTRACK 折返 ====================

    @Test
    @DisplayName("BACKTRACK 正例：相邻两段距离在自驾阈值内 → 无违规")
    void backtrackPassesWhenNear() {
        TripDraftDTO draft = draft(day(1,
                item("寿阳文庙", "SCENIC", "09:00", "10:00", "顺路"),
                item("老张面馆", "FOOD", "11:00", "12:00", "就在旁边")));
        assertTrue(validator.checkBacktrack(draft, intent(null, IntentDTO.BUDGET_MODE_TOTAL, IntentDTO.TRANSPORT_DRIVE), pool()).isEmpty());
    }

    @Test
    @DisplayName("BACKTRACK 反例：相邻两点相距约 170 公里，超过自驾 40km 阈值 → HIGH")
    void backtrackFlagsLongSegment() {
        TripDraftDTO draft = draft(day(1,
                item("寿阳文庙", "SCENIC", "09:00", "10:00", "顺路"),
                item("大同古城", "SCENIC", "12:00", "13:00", "顺路")));
        List<Violation> vs = validator.checkBacktrack(draft, intent(null, IntentDTO.BUDGET_MODE_TOTAL, IntentDTO.TRANSPORT_DRIVE), pool());
        assertEquals(1, vs.size());
        assertEquals(Violation.SEVERITY_HIGH, vs.get(0).severity());
        assertEquals(ItineraryValidator.RULE_BACKTRACK, vs.get(0).code());
    }

    // ==================== 4) DETOUR 单日通勤总量 ====================

    @Test
    @DisplayName("DETOUR 正例：单日累计通勤时长在自驾 120min 内 → 无违规")
    void detourPassesWhenWithinLimit() {
        TripDraftDTO draft = draft(day(1,
                item("寿阳文庙", "SCENIC", "09:00", "10:00", "顺路"),
                item("老张面馆", "FOOD", "11:00", "12:00", "顺路")));
        assertTrue(validator.checkDetour(draft, intent(null, IntentDTO.BUDGET_MODE_TOTAL, IntentDTO.TRANSPORT_DRIVE), pool()).isEmpty());
    }

    @Test
    @DisplayName("DETOUR 反例：单日通勤约 255 分钟，超过自驾 120min → MEDIUM")
    void detourFlagsOversizedMove() {
        TripDraftDTO draft = draft(day(1,
                item("寿阳文庙", "SCENIC", "09:00", "10:00", "顺路"),
                item("大同古城", "SCENIC", "12:00", "13:00", "顺路")));
        List<Violation> vs = validator.checkDetour(draft, intent(null, IntentDTO.BUDGET_MODE_TOTAL, IntentDTO.TRANSPORT_DRIVE), pool());
        assertTrue(vs.stream().anyMatch(v ->
                ItineraryValidator.RULE_DETOUR.equals(v.code()) && Violation.SEVERITY_MEDIUM.equals(v.severity())));
    }

    // ==================== 5) BUDGET_EXCEED 预算 ====================

    @Test
    @DisplayName("BUDGET 正例：花费未超预算 → 无违规")
    void budgetPassesWhenWithinBudget() {
        TripDraftDTO draft = draft(day(1,
                item("寿阳文庙", "SCENIC", "09:00", "10:00", "顺路", "30"),
                item("老张面馆", "FOOD", "11:00", "12:00", "顺路", "40")));
        IntentDTO intent = intent(new BigDecimal("100"), IntentDTO.BUDGET_MODE_TOTAL, IntentDTO.TRANSPORT_MIX);
        assertTrue(validator.checkBudget(draft, intent).isEmpty());
    }

    @Test
    @DisplayName("BUDGET 反例：超支约 50% → MEDIUM")
    void budgetFlagsOverBudget() {
        TripDraftDTO draft = draft(day(1,
                item("寿阳文庙", "SCENIC", "09:00", "10:00", "顺路", "50"),
                item("大同古城", "SCENIC", "12:00", "13:00", "顺路", "100")));
        IntentDTO intent = intent(new BigDecimal("100"), IntentDTO.BUDGET_MODE_TOTAL, IntentDTO.TRANSPORT_MIX);
        List<Violation> vs = validator.checkBudget(draft, intent);
        assertEquals(1, vs.size());
        assertTrue(Violation.SEVERITY_MEDIUM.equals(vs.get(0).severity()), vs.get(0).toString());
    }

    @Test
    @DisplayName("BUDGET 反例：略超预算 5%（容差内）→ LOW 提示而非 MEDIUM")
    void budgetLowWhenSlightOver() {
        TripDraftDTO draft = draft(day(1, item("寿阳文庙", "SCENIC", "09:00", "10:00", "顺路", "105")));
        IntentDTO intent = intent(new BigDecimal("100"), IntentDTO.BUDGET_MODE_TOTAL, IntentDTO.TRANSPORT_MIX);
        List<Violation> vs = validator.checkBudget(draft, intent);
        assertEquals(Violation.SEVERITY_LOW, vs.get(0).severity());
    }

    @Test
    @DisplayName("BUDGET 人均口径：预算 100 × 爸妈 3 人 = 300，花费 250 不超支")
    void budgetPerPersonScalesByPartySize() {
        TripDraftDTO draft = draft(day(1,
                item("寿阳文庙", "SCENIC", "09:00", "10:00", "顺路", "100"),
                item("大同古城", "SCENIC", "12:00", "13:00", "顺路", "150")));
        IntentDTO intent = intent(new BigDecimal("100"), IntentDTO.BUDGET_MODE_PER_PERSON, IntentDTO.TRANSPORT_MIX);
        intent.setCompanion("带爸妈");
        assertTrue(validator.checkBudget(draft, intent).isEmpty(), "250 应小于人均口径 300");
    }

    // ==================== 6) TABOO 忌口（★硬约束）====================

    @Test
    @DisplayName("TABOO 正例：没有忌口 → 无违规")
    void tabooPassesWithoutProfile() {
        TripDraftDTO draft = draft(day(1, item("寿阳文庙", "SCENIC", "09:00", "10:00", "顺路")));
        assertTrue(validator.checkTaboo(draft, null, null).isEmpty());
    }

    @Test
    @DisplayName("TABOO 反例（手册验收 3）：画像忌口「香菜」，poiName 含「香菜拌面」→ HIGH")
    void tabooFlagsCilantroInName() {
        TripDraftDTO draft = draft(day(1, item("香菜拌面", "FOOD", "11:00", "12:00", "顺路")));
        UserTravelProfile profile = new UserTravelProfile();
        profile.setTaboos("香菜");
        List<Violation> vs = validator.checkTaboo(draft, profile, null);
        assertEquals(1, vs.size());
        assertEquals(Violation.SEVERITY_HIGH, vs.get(0).severity());
        assertTrue(vs.get(0).message().contains("香菜"));
    }

    @Test
    @DisplayName("TABOO 连 reason 也查：名字没忌口，但理由里出现「香菜牛肉面」→ HIGH")
    void tabooScansReasonToo() {
        TripDraftDTO draft = draft(day(1, item("老张面馆", "FOOD", "11:00", "12:00", "这家的香菜牛肉面很有名")));
        UserTravelProfile profile = new UserTravelProfile();
        profile.setTaboos("香菜");
        List<Violation> vs = validator.checkTaboo(draft, profile, null);
        assertEquals(1, vs.size());
        assertEquals(Violation.SEVERITY_HIGH, vs.get(0).severity());
    }

    @Test
    @DisplayName("TABOO 与 overrides 取并集：临时忌口「花生」同样生效")
    void tabooMergesOverrides() {
        TripDraftDTO draft = draft(day(1, item("花生酥", "FOOD", "11:00", "12:00", "顺路")));
        ProfileOverrides overrides = new ProfileOverrides(List.of("花生"), null);
        List<Violation> vs = validator.checkTaboo(draft, null, overrides);
        assertEquals(1, vs.size());
        assertEquals(Violation.SEVERITY_HIGH, vs.get(0).severity());
    }

    // ==================== 7) TOO_DENSE 点位密度 ====================

    @Test
    @DisplayName("DENSITY 正例：单日移动量在步行上限内 → 无违规")
    void densityPassesWhenWithinWalkLimit() {
        TripDraftDTO draft = draft(day(1,
                item("寿阳文庙", "SCENIC", "09:00", "10:00", "顺路"),
                item("老张面馆", "FOOD", "11:00", "12:00", "顺路")));
        UserTravelProfile profile = new UserTravelProfile();
        profile.setWalkLimitKm(5);
        assertTrue(validator.checkDensity(draft, profile, pool()).isEmpty());
    }

    @Test
    @DisplayName("DENSITY 反例：单日移动约 170 公里，超过 5 公里上限 → MEDIUM")
    void densityFlagsOversizedMove() {
        TripDraftDTO draft = draft(day(1,
                item("寿阳文庙", "SCENIC", "09:00", "10:00", "顺路"),
                item("大同古城", "SCENIC", "12:00", "13:00", "顺路")));
        UserTravelProfile profile = new UserTravelProfile();
        profile.setWalkLimitKm(5);
        List<Violation> vs = validator.checkDensity(draft, profile, pool());
        assertEquals(1, vs.size());
        assertTrue(Violation.SEVERITY_MEDIUM.equals(vs.get(0).severity()));
    }

    @Test
    @DisplayName("DENSITY 无上限或画像为空时整条规则跳过，不误报")
    void densitySkippedWhenNoLimit() {
        TripDraftDTO draft = draft(day(1,
                item("寿阳文庙", "SCENIC", "09:00", "10:00", "顺路"),
                item("大同古城", "SCENIC", "12:00", "13:00", "顺路")));
        assertTrue(validator.checkDensity(draft, null, pool()).isEmpty());
        UserTravelProfile profile = new UserTravelProfile(); // walkLimitKm = null
        assertTrue(validator.checkDensity(draft, profile, pool()).isEmpty());
    }

    // ==================== 入口 validate() 整合 ====================

    @Test
    @DisplayName("validate：空草稿 → 报 HIGH 的 CLOSURE，不抛异常")
    void validateFlagsEmptyDraft() {
        TripDraftDTO draft = new TripDraftDTO();
        draft.setDays(new ArrayList<>());
        ValidationReport report = validator.validate(draft, intent(null, IntentDTO.BUDGET_MODE_TOTAL, IntentDTO.TRANSPORT_MIX), pool(), null, null);
        assertFalse(report.passed());
        assertTrue(report.hasHigh());
    }

    @Test
    @DisplayName("validate：干净的行程 → passed=true 且违规为空")
    void validatePassesForCleanDraft() {
        TripDraftDTO draft = draft(day(1,
                item("寿阳文庙", "SCENIC", "09:00", "10:30", "顺路"),
                item("老张面馆", "FOOD", "11:30", "12:30", "就在旁边")));
        ValidationReport report = validator.validate(draft, intent(new BigDecimal("200"), IntentDTO.BUDGET_MODE_TOTAL,
                IntentDTO.TRANSPORT_WALK), pool(), null, null);
        assertTrue(report.passed(), report.toFeedbackText());
    }

    @Test
    @DisplayName("校验永不返回 null；全通过时 toUserHint 说清了无违规")
    void validateNeverReturnsNullAndHintStable() {
        CandidatePool empty = new CandidatePool(List.of(), MapMode.ESTIMATED);
        ValidationReport report = validator.validate(null, intent(null, IntentDTO.BUDGET_MODE_TOTAL, IntentDTO.TRANSPORT_MIX), empty, null, null);
        assertFalse(report.passed());
        assertTrue(report.toUserHint().contains("个严重问题"));
    }

    // ==================== 工具 ====================

    private TripDraftDTO draft(TripDraftDTO.DayDraft... days) {
        TripDraftDTO d = new TripDraftDTO();
        d.setTitle("测试行程");
        d.setDays(List.of(days));
        return d;
    }

    private TripDraftDTO.DayDraft day(int index, TripDraftDTO.ItemDraft... items) {
        TripDraftDTO.DayDraft day = new TripDraftDTO.DayDraft();
        day.setDayIndex(index);
        day.setTitle("第" + index + "天");
        day.setItems(new ArrayList<>(List.of(items)));
        return day;
    }

    private TripDraftDTO.ItemDraft item(String poiRef, String type, String start, String end, String reason) {
        return item(poiRef, type, start, end, reason, null);
    }

    private TripDraftDTO.ItemDraft item(String poiRef, String type, String start, String end, String reason, String cost) {
        TripDraftDTO.ItemDraft it = new TripDraftDTO.ItemDraft();
        it.setPoiRef(poiRef);
        it.setItemType(type.equals("FOOD") ? TripItem.TYPE_FOOD : TripItem.TYPE_SCENIC);
        it.setStartTime(start);
        it.setEndTime(end);
        it.setReason(reason);
        if (cost != null) {
            it.setCostEstimate(new BigDecimal(cost));
        }
        return it;
    }

    private IntentDTO intent(BigDecimal budget, String mode, String transport) {
        IntentDTO in = new IntentDTO();
        in.setDestination("寿阳");
        in.setBudgetTotal(budget);
        in.setBudgetMode(mode);
        in.setTransport(transport);
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
}