package com.wayfare.trip;

import com.wayfare.dto.IntentDTO;
import com.wayfare.dto.TripDraftDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TripCopyPrompt} 的单测（P4-B）。
 *
 * <p>prompt 是这一步唯一带「业务规则」的产物，而它又没法靠集成测试断言 ——
 * 真跑一次大模型只能看到「文案出来了」，看不出「规则有没有进 prompt」。
 * 所以把规则逐条钉死在这里。
 *
 * <p>最要紧的一条是 {@link #distanceNeverLeaksIntoPrompt()}：距离与时长<b>绝不进 prompt</b>。
 * 这不是洁癖 —— P3-F 联调实测过模型写出「步行几分钟」而百度实测是 40 公里（驾车），
 * 根源就是 prompt 里给了数字、模型顺手复述。
 */
class TripCopyPromptTest {

    // ==================== estimatedMode ====================

    @Test
    @DisplayName("估算模式：一个距离都没有 = 估算模式")
    void estimatedWhenNoDistanceAtAll() {
        assertTrue(TripCopyPrompt.estimatedMode(draftWith(null)));
    }

    @Test
    @DisplayName("有任一段距离 = 有事实，不算估算模式")
    void notEstimatedWhenAnyDistancePresent() {
        assertFalse(TripCopyPrompt.estimatedMode(draftWith(40501)));
    }

    @Test
    @DisplayName("空行程 / null 一律按估算模式处理（没有事实可用）")
    void estimatedForEmptyDraft() {
        assertTrue(TripCopyPrompt.estimatedMode(null));
        assertTrue(TripCopyPrompt.estimatedMode(new TripDraftDTO()));
        assertTrue(TripCopyPrompt.estimatedMode(emptyDaysDraft()));
    }

    // ==================== systemPrompt ====================

    @Test
    @DisplayName("system prompt 覆盖手册六条写作规则")
    void systemPromptCoversSixRules() {
        String p = TripCopyPrompt.systemPrompt(true);

        assertTrue(p.contains("按天组织"), "缺「按天组织」");
        assertTrue(p.contains("为什么去"), "缺「为什么去」四要素");
        assertTrue(p.contains("看什么"), "缺「看什么」四要素");
        assertTrue(p.contains("大概花多久"), "缺「大概花多久」四要素");
        assertTrue(p.contains("要花多少钱"), "缺「要花多少钱」四要素");
        assertTrue(p.contains("【用户画像】"), "缺画像融入规则");
        assertTrue(p.contains("语气像朋友"), "缺语气规则");
        assertTrue(p.contains("150-250"), "缺单日字数区间");
        assertTrue(p.contains("800"), "缺全文上限");
        assertTrue(p.contains("Markdown"), "缺「不要用 Markdown」规则");
    }

    @Test
    @DisplayName("地图关闭（估算模式）：加上禁止写出距离与时间数字的强约束")
    void estimatedModeForbidsConcreteNumbers() {
        String p = TripCopyPrompt.systemPrompt(true);

        assertTrue(p.contains("绝对不要写出具体的距离或时间数字"), "缺禁止数字的强约束");
        // 手册点名的替代说法
        assertTrue(p.contains("顺路"), "缺「顺路」这类模糊说法");
        assertTrue(p.contains("不远"), "缺「不远」这类模糊说法");
        assertTrue(p.contains("慢慢走过去"), "缺「慢慢走过去」这类模糊说法");
    }

    @Test
    @DisplayName("地图开启：不下「禁止写数字」的强约束，但数字仍然不进文案")
    void verifiedModeDoesNotForbidButStillKeepsNumbersOut() {
        String p = TripCopyPrompt.systemPrompt(false);

        assertFalse(p.contains("绝对不要写出具体的距离或时间数字"),
                "地图开启时不该再下这条强约束（否则与「有实测数据」自相矛盾）");
        assertTrue(p.contains("不要把数字写进文案"), "数字仍应由界面展示，不进文案");
        assertTrue(p.contains("顺路"), "两种模式都保留自然说法");
    }

    @Test
    @DisplayName("票价允许写，但只能引用行程里给的数字，不许自己估")
    void priceMustBeQuotedNotGuessed() {
        String p = TripCopyPrompt.systemPrompt(true);

        assertTrue(p.contains("票价与花费可以写"), "缺票价规则");
        assertTrue(p.contains("不要自己估算"), "缺「不许自己估算」");
    }

    // ==================== userPrompt ====================

    @Test
    @DisplayName("行程简报：目的地 / 标题 / 天数 / 点位 / 停留 / 花费 / 理由 全在")
    void userPromptRendersDraft() {
        IntentDTO intent = new IntentDTO();
        intent.setDestination("寿阳");
        intent.setDays(1);

        String p = TripCopyPrompt.userPrompt(draftWith(null), intent, "");

        assertTrue(p.contains("目的地：寿阳"), p);
        assertTrue(p.contains("标题：寿阳古建两日慢行"), p);
        assertTrue(p.contains("共 1 天"), p);
        assertTrue(p.contains("第 1 天：古城寻塔"), p);
        assertTrue(p.contains("天外来客塔"), p);
        assertTrue(p.contains("停留 90 分钟"), p);
        assertTrue(p.contains("预算 30 元"), p);
        assertTrue(p.contains("安排理由：辽代砖塔，塔身倾斜，人少"), p);
    }

    @Test
    @DisplayName("标题缺失时用「（待定）」占位，不渲染成 null")
    void userPromptHandlesMissingTitle() {
        TripDraftDTO draft = draftWith(null);
        draft.setTitle(null);

        String p = TripCopyPrompt.userPrompt(draft, null, "");

        assertTrue(p.contains("（待定）"), p);
        assertFalse(p.contains("null"), p);
    }

    @Test
    @DisplayName("画像块：有内容就原样带上，空/ null 时整段不出现")
    void userPromptProfileBlock() {
        TripDraftDTO draft = draftWith(null);
        String block = "【用户画像】\n忌口过敏（硬约束，任何推荐都不得包含）：香菜";

        assertTrue(TripCopyPrompt.userPrompt(draft, null, block).contains("忌口过敏"));
        // 空画像时连标题都不该出现 —— 否则模型可能把「没有画像」读成「用户没有忌口」
        assertFalse(TripCopyPrompt.userPrompt(draft, null, "").contains("【用户画像】"));
        assertFalse(TripCopyPrompt.userPrompt(draft, null, null).contains("【用户画像】"));
    }

    @Test
    @DisplayName("★ 距离与时长数字绝不进 prompt —— 免得模型复述出与实测冲突的文案")
    void distanceNeverLeaksIntoPrompt() {
        // 这一段 40501 米 / 3589 秒（P3-F 联调里百度真实返回过的值）
        String user = TripCopyPrompt.userPrompt(draftWith(40501), null, "");
        String system = TripCopyPrompt.systemPrompt(false);

        assertFalse(user.contains("40501"), "距离数字漏进 user prompt：" + user);
        assertFalse(user.contains("3589"), "耗时数字漏进 user prompt：" + user);
        assertFalse(user.contains("米"), "不该出现距离单位");
        assertFalse(user.contains("公里"), "不该出现距离单位");
        assertFalse(system.contains("40501"), "距离数字漏进 system prompt");
    }

    @Test
    @DisplayName("常量与手册一致（150 / 250 / 800）")
    void constantsMatchHandbook() {
        assertEquals(150, TripCopyPrompt.MIN_CHARS_PER_DAY);
        assertEquals(250, TripCopyPrompt.MAX_CHARS_PER_DAY);
        assertEquals(800, TripCopyPrompt.MAX_TOTAL_CHARS);
        assertNotNull(TripCopyPrompt.systemPrompt(true));
    }

    // ==================== 构造辅助 ====================

    /** 一天一个点位的行程；{@code distanceMeters} 为 null 即「没有距离事实」的估算态 */
    private static TripDraftDTO draftWith(Integer distanceMeters) {
        TripDraftDTO draft = new TripDraftDTO();
        draft.setTitle("寿阳古建两日慢行");

        TripDraftDTO.ItemDraft item = new TripDraftDTO.ItemDraft();
        item.setPoiRef("天外来客塔");
        item.setPoiName("天外来客塔");
        item.setItemType("SCENIC");
        item.setStartTime("09:00");
        item.setEndTime("10:30");
        item.setStayMinutes(90);
        item.setCostEstimate(new BigDecimal("30"));
        item.setReason("辽代砖塔，塔身倾斜，人少");
        item.setDistanceMeters(distanceMeters);
        item.setDurationSeconds(distanceMeters == null ? null : 3589);
        item.setVerifyStatus(distanceMeters == null ? "ESTIMATED" : "VERIFIED");
        item.setDataSource(distanceMeters == null ? "LLM" : "BAIDU");

        TripDraftDTO.DayDraft day = new TripDraftDTO.DayDraft();
        day.setDayIndex(1);
        day.setTitle("古城寻塔");
        day.setItems(List.of(item));

        draft.setDays(List.of(day));
        return draft;
    }

    /** 有 days 列表但一天都没有的边界 */
    private static TripDraftDTO emptyDaysDraft() {
        TripDraftDTO draft = new TripDraftDTO();
        draft.setDays(List.of());
        return draft;
    }
}
