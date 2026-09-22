package com.wayfare.trip;

import com.wayfare.dto.IntentDTO;
import com.wayfare.dto.TripDraftDTO;
import org.springframework.util.StringUtils;

/**
 * 攻略文案的 prompt 构造器（P4-B）。
 *
 * <p><b>为什么单独开一个类</b>：prompt 是这一步唯一有「业务规则」的东西 ——
 * 六条写作约束、画像怎么融、地图关闭时怎么措辞，全在这里。
 * 把它们从 {@code TripStreamService}（通道职责：线程池、事件、断开检测）里拆出来，
 * 一是这个类是<b>零依赖纯函数</b>（可直接 {@code new} 出来单测，与 {@code PreOrderService} 同风格），
 * 二是改文案规则时不必碰通道代码。
 *
 * <p><b>两处刻意的设计决定</b>：
 * <ol>
 *   <li><b>距离与时长一律不写进 prompt</b>（两种模式都不写）。文案的价值在「为什么去、看什么」，
 *       距离/时长由前端用结构化数据渲染（带 {@code VERIFIED/CACHED/ESTIMATED} 角标）。
 *       把数字塞进 prompt 只会诱使模型复述，而模型复述出的数字与实测值一旦不一致，
 *       就正好落进 P3-F 联调记录的那个坑：<i>模型写「步行几分钟」而百度实测 40 公里（驾车）</i>。</li>
 *   <li><b>「地图关闭」用 {@code distanceMeters} 有没有值来判断，而不是读 {@code mapMode}</b>。
 *       因为文案要回答的问题是「我手里有没有可靠的距离事实」，
 *       而这正是「有没有一个非 null 的 distanceMeters」的定义；
 *       CACHED（命中缓存）与 VERIFIED（实测）都算「有事实」，ESTIMATED 才是「没有」。</li>
 * </ol>
 */
public final class TripCopyPrompt {

    /** 全文上限（字）。手册硬指标 */
    public static final int MAX_TOTAL_CHARS = 800;

    /** 单日字数下限（字）。手册硬指标 */
    public static final int MIN_CHARS_PER_DAY = 150;

    /** 单日字数上限（字）。手册硬指标 */
    public static final int MAX_CHARS_PER_DAY = 250;

    /** 画像段落的标题标记，与 {@code ProfileRenderer.HEADER} 一致 */
    private static final String PROFILE_HEADER = "【用户画像】";

    private TripCopyPrompt() {
    }

    /**
     * 构造 system prompt。
     *
     * @param estimatedMode 是否处于「没有实测距离/时长」的估算模式。
     *                      为 true 时会加上<b>禁止出现任何距离与时间数字</b>的强约束 ——
     *                      这是铁律一在文案环节的落点：模型没有事实，就不许在文字里伪造事实感。
     */
    public static String systemPrompt(boolean estimatedMode) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个给朋友写旅行攻略的人，不是官方宣传稿的写手。\n")
                .append("把给定的行程写成给人读的攻略文案，要求：\n")
                .append("1. 按天组织，每天一整段。每天开头用一句话点出当天的主题。\n")
                .append("2. 每个点位都要说清楚四件事：为什么去、看什么、大概花多久、要花多少钱。\n")
                .append("3. 如果给了").append(PROFILE_HEADER).append("，就把里面的偏好自然地融进文案里 ——")
                .append("例如用户忌口香菜，就在提到那家面馆时提醒一句「点单时说一声不要香菜」。")
                .append("画像里没写过的偏好不要凭空假设。\n")
                .append("4. 语气像朋友给的建议：用「你」，说人话。")
                .append("不要「位于」「享有盛誉」「不容错过」这类宣传语。\n")
                .append("5. 长度：每天 ").append(MIN_CHARS_PER_DAY).append('-').append(MAX_CHARS_PER_DAY)
                .append(" 字，全文不超过 ").append(MAX_TOTAL_CHARS)
                // 手册这两条在「天数多」时会互相冲突（5 天 × 150 字 = 750 已逼近上限），
                // 与其让模型自己瞎猜谁优先，不如明确告诉它按全文上限反推
                .append(" 字（天数较多时，按全文上限反推每天的字数）。宁可短一点，不要凑字数。\n")
                .append("6. 不要使用 Markdown 标题、编号或列表符号，直接写成连续的段落。\n");

        sb.append("\n【关于距离与时间】\n")
                .append("行程的距离与耗时由界面单独展示，你不需要在文案里报数字。\n");
        if (estimatedMode) {
            sb.append("本次没有任何实测的距离与时长，所以**绝对不要写出具体的距离或时间数字**")
                    .append("（「步行 800 米」「开车 20 分钟」这类都算违规）。")
                    .append("需要表达远近时，用「顺路」「不远」「走一会儿就到」「慢慢走过去」这种模糊说法。\n");
        } else {
            sb.append("本次有实测的距离与时长，但它们仍然由界面单独展示，")
                    .append("你同样不要把数字写进文案，用「顺路」「不远」「走一会儿就到」这类自然说法即可。\n");
        }
        sb.append("票价与花费可以写，但只能引用行程里给出的数字，不要自己估算。\n");
        return sb.toString();
    }

    /**
     * 构造 user prompt：行程简报 + 画像块。
     *
     * @param draft        结构化行程（Step6 事实补全之后的那份）
     * @param intent       意图解析结果，只用它的 {@code destination} 点明地点
     * @param profileBlock {@code ProfileRenderer.render()} 的输出；为空或 null 时整段省略
     */
    public static String userPrompt(TripDraftDTO draft, IntentDTO intent, String profileBlock) {
        StringBuilder sb = new StringBuilder();
        sb.append("【行程】\n");
        if (intent != null && StringUtils.hasText(intent.getDestination())) {
            sb.append("目的地：").append(intent.getDestination()).append('\n');
        }
        sb.append("标题：")
                .append(draft != null && StringUtils.hasText(draft.getTitle()) ? draft.getTitle() : "（待定）")
                .append('\n');
        int dayCount = draft == null || draft.getDays() == null ? 0 : draft.getDays().size();
        sb.append("共 ").append(dayCount).append(" 天\n");

        if (draft != null && draft.getDays() != null) {
            for (TripDraftDTO.DayDraft day : draft.getDays()) {
                sb.append("\n第 ").append(day.getDayIndex()).append(" 天");
                if (StringUtils.hasText(day.getTitle())) {
                    sb.append('：').append(day.getTitle());
                }
                sb.append('\n');
                if (day.getItems() == null) {
                    continue;
                }
                for (TripDraftDTO.ItemDraft item : day.getItems()) {
                    appendItem(sb, item);
                }
            }
        }

        if (StringUtils.hasText(profileBlock)) {
            sb.append('\n').append(profileBlock.trim()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 单个点位一行。<b>只写「名称 / 时段 / 停留 / 花费 / 理由」</b>，
     * 不带距离与时长 —— 见类注释的第一条设计决定。
     */
    private static void appendItem(StringBuilder sb, TripDraftDTO.ItemDraft item) {
        sb.append("  - ");
        if (StringUtils.hasText(item.getStartTime()) || StringUtils.hasText(item.getEndTime())) {
            sb.append(item.getStartTime()).append('-').append(item.getEndTime()).append(' ');
        }
        sb.append(StringUtils.hasText(item.getPoiName()) ? item.getPoiName() : item.getPoiRef());
        if (item.getStayMinutes() != null) {
            sb.append("（停留 ").append(item.getStayMinutes()).append(" 分钟）");
        }
        if (item.getCostEstimate() != null) {
            sb.append(" 预算 ").append(item.getCostEstimate().stripTrailingZeros().toPlainString()).append(" 元");
        }
        sb.append('\n');
        if (StringUtils.hasText(item.getReason())) {
            sb.append("    安排理由：").append(item.getReason()).append('\n');
        }
    }

    /**
     * 判断这份行程是不是「没有可靠距离事实」的估算模式。
     *
     * <p>只要存在<b>任意一个</b>非 null 的 {@code distanceMeters}，就说明地图真的给过事实
     * （VERIFIED 实测或 CACHED 缓存命中），返回 false；一个都没有（含空行程）返回 true。
     */
    public static boolean estimatedMode(TripDraftDTO draft) {
        if (draft == null || draft.getDays() == null) {
            return true;
        }
        for (TripDraftDTO.DayDraft day : draft.getDays()) {
            if (day.getItems() == null) {
                continue;
            }
            for (TripDraftDTO.ItemDraft item : day.getItems()) {
                if (item.getDistanceMeters() != null) {
                    return false;
                }
            }
        }
        return true;
    }
}
