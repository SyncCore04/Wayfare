package com.wayfare.trip;

import com.wayfare.dto.IntentDTO;
import com.wayfare.entity.UserTravelProfile;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 忌口匹配（P3-B 与 P3-E 共用的唯一实现）。
 *
 * <p><b>为什么抽成一个独立类</b>：忌口在管线里要被用两次 ——
 * P3-B 检索候选时先剔一遍（第一道过滤），P3-E 校验行程时再查一遍（硬约束）。
 * 两处如果各写一套归一化逻辑，迟早会跑偏：一边认「不吃辣」、另一边只认「辣」，
 * 于是同一个忌口在一处生效、另一处漏过 —— 而**漏过忌口是安全问题，不是体验问题**。
 * 所以这里做唯一实现，两边都调它。
 *
 * <p><b>归一化是必须的</b>：用户填的是口语（「不吃辣」「海鲜过敏」），
 * 而菜名里写的是核心词（「麻辣火锅」）。不归一化的话
 * {@code name.contains("不吃辣")} 永远匹配不上任何餐厅名，忌口形同虚设。
 *
 * <p>⚠️ <b>这是字面匹配，不是语义匹配</b>（已知限制）：
 * 忌口「海鲜」拦得住「老张海鲜大排档」，但**拦不住「槐店王婆大虾」**这类只写具体菜名的。
 * 要真正做好需要一张同义词表（海鲜 → 虾/蟹/贝/鱼/蛤…），属可扩展项，
 * **没有用户认可不擅自加**（见 MEMORY.md）。
 */
public final class TabooMatcher {

    /** 口语前缀/后缀，归一化时去掉，留下核心食材词 */
    private static final List<String> NOISE = List.of(
            "过敏", "不能吃", "不可以吃", "不吃", "忌口", "忌", "免", "别吃", "少放", "不要");

    private TabooMatcher() {
    }

    /**
     * 汇总忌口词：长期画像的 {@code taboos} + 本次的 {@code dietaryOverrides}。
     *
     * <p>两者是<b>并集</b>（不是覆盖）：忌口是硬约束，多一条只会更安全，
     * 不存在「这次可以不忌口」这种诉求（与 {@code ProfileOverrides} 的合并语义一致）。
     */
    public static List<String> collect(UserTravelProfile profile, IntentDTO intent) {
        return collect(profile, intent == null ? null : intent.getDietaryOverrides());
    }

    /**
     * 汇总忌口词（另一入口：直接给本次临时忌口列表）。
     *
     * <p>P3-E 手里只有 {@code ProfileOverrides} 而不是整个 IntentDTO，
     * 所以需要这个重载 —— 但归一化逻辑只有下面这一份，两个入口共用。
     */
    public static List<String> collect(UserTravelProfile profile, List<String> overrideTaboos) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (profile != null && StringUtils.hasText(profile.getTaboos())) {
            for (String part : profile.getTaboos().split("[,，]")) {
                addTerm(terms, part);
            }
        }
        if (overrideTaboos != null) {
            for (String part : overrideTaboos) {
                addTerm(terms, part);
            }
        }
        return new ArrayList<>(terms);
    }

    /** 归一化单个忌口词并加进集合（空词忽略） */
    private static void addTerm(LinkedHashSet<String> terms, String raw) {
        if (!StringUtils.hasText(raw)) {
            return;
        }
        String term = raw.trim();
        for (String noise : NOISE) {
            term = term.replace(noise, "");
        }
        term = term.trim();
        if (!term.isEmpty()) {
            terms.add(term);
        }
    }

    /**
     * 文本里是否命中任一忌口词。
     *
     * @param text   要检查的文本（点位名 / 理由 / 备注）
     * @param taboos 忌口词列表，见 {@link #collect}
     */
    public static boolean hits(String text, List<String> taboos) {
        if (!StringUtils.hasText(text) || taboos == null || taboos.isEmpty()) {
            return false;
        }
        String normalized = normalize(text);
        for (String term : taboos) {
            String t = normalize(term);
            if (!t.isEmpty() && normalized.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /** 命中的是哪个忌口词（用于把违规信息写具体），没命中返回 null */
    public static String hitTerm(String text, List<String> taboos) {
        if (!StringUtils.hasText(text) || taboos == null) {
            return null;
        }
        String normalized = normalize(text);
        for (String term : taboos) {
            String t = normalize(term);
            if (!t.isEmpty() && normalized.contains(t)) {
                return term;
            }
        }
        return null;
    }

    /**
     * 文本归一化：去空白、全角转半角、统一小写。
     *
     * <p>与名称去重用的是同一套归一化思路 —— 模型与用户的输入里
     * 全角空格、大小写、括号差异非常常见，不做归一化会把同一个东西认成两个。
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (Character.isWhitespace(c) || c == '\u3000') {
                continue;
            }
            if (c >= 'Ａ' && c <= 'Ｚ') {
                sb.append((char) (c - 'Ａ' + 'a'));
            } else if (c >= '０' && c <= '９') {
                sb.append((char) (c - '０' + '0'));
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
