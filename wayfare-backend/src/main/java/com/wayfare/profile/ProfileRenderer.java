package com.wayfare.profile;

import com.wayfare.entity.UserTravelProfile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户画像渲染器（P2-A）—— 把结构化画像翻译成一段给大模型看的中文文本块。
 *
 * <p><b>为什么需要这一层</b>：{@code user_travel_profile} 是给人填的（表单、下拉框），
 * 而 prompt 是给模型读的。把 TINYINT 的 {@code pace=1} 直接塞进 prompt，
 * 模型只会一头雾水；必须翻译成「节奏：慢（每天 2-3 个点）」。
 * 渲染规则集中在这一个类里，P3 只管把结果拼进 system prompt。
 *
 * <p><b>输出格式是 P3 的接口契约，改动需同步 P3。</b>形如：
 * <pre>
 * 【用户画像】
 * 菜系偏好：晋菜、面食
 * 口味偏好：偏咸、微辣
 * 忌口过敏（硬约束，任何推荐都不得包含）：香菜、花生
 * 旅行风格：古建探访、摄影旅拍
 * 节奏：慢（每天 2-3 个点）
 * 预算倾向：经济
 * 常同行人：情侣
 * 单日步行上限：8 公里
 * 住宿偏好：民宿
 * 补充说明：不喜欢人多的景区
 * </pre>
 *
 * <p><b>三条渲染规则（都有明确的理由，不是随手定的）</b>：
 * <ol>
 *   <li><b>值为空的行整行省略</b>，绝不输出「无」「未填写」之类的占位 ——
 *       那等于往 prompt 里灌噪音，模型可能把「未填写」理解成一个真实取值。</li>
 *   <li><b>唯独忌口永远占一行</b>：忌口是硬约束，它的「空」必须被显式声明为
 *       「没有忌口」，而不是靠「这一行不存在」让模型自己猜。
 *       行不存在既可能被读成「没有忌口」也可能被读成「不知道」，
 *       而这两种解读对餐饮推荐的后果完全不同。</li>
 *   <li><b>非法取值按未填写处理</b>（如 {@code pace=9}）：宁可少一行，
 *       也不能把「9」翻译成任何结论 —— 那是凭空捏造用户偏好。</li>
 * </ol>
 *
 * <p>返回值<b>不带结尾换行</b>，由调用方自行拼接分隔符。
 */
@Component
public class ProfileRenderer {

    /** 文本块标题。P3 依赖这个标记来定位画像段落 */
    private static final String HEADER = "【用户画像】";

    /** 列表分隔符，用顿号而非逗号 —— 中文列表读起来更自然 */
    private static final String SEP = "、";

    /** 字段标签。与 {@link #render} 的输出格式一一对应 */
    private static final String LABEL_CUISINES = "菜系偏好";
    private static final String LABEL_FLAVORS = "口味偏好";
    private static final String LABEL_TABOOS = "忌口过敏（硬约束，任何推荐都不得包含）";
    private static final String LABEL_TABOOS_EMPTY = "忌口过敏";
    private static final String LABEL_STYLES = "旅行风格";
    private static final String LABEL_PACE = "节奏";
    private static final String LABEL_BUDGET = "预算倾向";
    private static final String LABEL_COMPANIONS = "常同行人";
    private static final String LABEL_WALK_LIMIT = "单日步行上限";
    private static final String LABEL_HOTEL = "住宿偏好";
    private static final String LABEL_NOTES = "补充说明";

    /** 忌口为空时的固定文案：既说明「没有忌口」，又点出这带来的自由度 */
    private static final String TABOOS_NONE = "无（这点可以更自由地推荐餐饮）";

    /** 全角冒号：与手册给的中文样例保持一致 */
    private static final String COLON = "：";

    /**
     * 渲染画像文本块。
     *
     * @param profile   用户画像，可为 null（视作空画像）
     * @param overrides 本次临时条件，可为 null
     * @return 渲染结果；<b>画像与临时条件都为空时返回空字符串</b>（P3 据此跳过注入）
     */
    public String render(UserTravelProfile profile, ProfileOverrides overrides) {
        List<String> overrideTaboos = normalizeList(overrides == null ? null : overrides.getTaboos());
        Integer overridePace = overrides == null ? null : overrides.getPace();
        boolean overrideEmpty = overrideTaboos.isEmpty() && paceLabel(overridePace) == null;

        // 手册验收 2：画像全空且没有临时条件 → 空字符串，而不是「【用户画像】」这个空壳
        if (!hasAnyContent(profile) && overrideEmpty) {
            return "";
        }

        List<String> lines = new ArrayList<>();

        if (profile != null) {
            addIfPresent(lines, LABEL_CUISINES, joinList(splitCsv(profile.getCuisines())));
            addIfPresent(lines, LABEL_FLAVORS, joinList(splitCsv(profile.getFlavors())));
        }

        // ---- 忌口：与 overrides 合并（并集去重），且永远占一行 ----
        Set<String> taboos = new LinkedHashSet<>();
        if (profile != null) {
            taboos.addAll(splitCsv(profile.getTaboos()));
        }
        taboos.addAll(overrideTaboos);
        if (taboos.isEmpty()) {
            lines.add(LABEL_TABOOS_EMPTY + COLON + TABOOS_NONE);
        } else {
            lines.add(LABEL_TABOOS + COLON + String.join(SEP, taboos));
        }

        if (profile != null) {
            addIfPresent(lines, LABEL_STYLES, joinList(splitCsv(profile.getTravelStyles())));
            // 节奏：overrides 非空时覆盖画像（本次的「带长辈要慢」该盖过平时的「紧凑」）
            addIfPresent(lines, LABEL_PACE, paceLabel(overridePace != null ? overridePace : profile.getPace()));
            addIfPresent(lines, LABEL_BUDGET, budgetLabel(profile.getBudgetLevel()));
            addIfPresent(lines, LABEL_COMPANIONS, trimToNull(profile.getCompanions()));
            if (profile.getWalkLimitKm() != null) {
                addIfPresent(lines, LABEL_WALK_LIMIT, profile.getWalkLimitKm() + " 公里");
            }
            addIfPresent(lines, LABEL_HOTEL, trimToNull(profile.getHotelPref()));
            addIfPresent(lines, LABEL_NOTES, trimToNull(profile.getNotes()));
        }

        return HEADER + "\n" + String.join("\n", lines);
    }

    /**
     * 判断画像里有没有可注入的内容。
     *
     * <p>「有内容」的口径与 {@link #render} 完全一致：只有那些<b>真能渲染出一行</b>的字段
     * 才算数。例如 {@code pace=9} 这种非法值既渲染不出来、也不算内容，
     * 否则会出现「hasAnyContent=true 但 render 返回空串」的自相矛盾。
     *
     * <p><b>本方法只看内容，不看隐私开关。</b>要判断「这次该不该注入」，请用
     * {@link #shouldInject} —— 只调本方法会漏掉 {@code allowAiUse=0} 这道闸。
     */
    public boolean hasAnyContent(UserTravelProfile profile) {
        if (profile == null) {
            return false;
        }
        return StringUtils.hasText(profile.getCuisines())
                || StringUtils.hasText(profile.getFlavors())
                || StringUtils.hasText(profile.getTaboos())
                || StringUtils.hasText(profile.getTravelStyles())
                || paceLabel(profile.getPace()) != null
                || budgetLabel(profile.getBudgetLevel()) != null
                || StringUtils.hasText(profile.getCompanions())
                || profile.getWalkLimitKm() != null
                || StringUtils.hasText(profile.getHotelPref())
                || StringUtils.hasText(profile.getNotes());
    }

    /**
     * 判断这次规划到底该不该注入画像 —— <b>P3 请用这个方法，不要直接用 hasAnyContent</b>。
     *
     * <p>它把两道闸合成一处：① 画像确实有内容；② 用户没有关掉隐私开关。
     *
     * <p>为什么值得单独开一个方法（而不是让调用方自己 && 一下）：
     * 隐私开关是铁律 3 的落点，一旦有哪处调用忘了判 {@code allowAiUse}，
     * 用户「关掉 AI 使用我的画像」这个操作就形同虚设，且<b>不会有任何报错</b>。
     * 把安全的那条路做成默认路径，比在每个调用点写一遍 && 可靠。
     */
    public boolean shouldInject(UserTravelProfile profile) {
        if (profile == null || isAiUseDisallowed(profile)) {
            return false;
        }
        return hasAnyContent(profile);
    }

    // ==================== 内部 ====================

    /** 隐私开关是否被用户明确关闭（null 视作未设置，即允许） */
    private boolean isAiUseDisallowed(UserTravelProfile profile) {
        return profile.getAllowAiUse() != null && profile.getAllowAiUse() == 0;
    }

    /** 节奏码 → 中文。非法值返回 null，等价于「未填写」 */
    private static String paceLabel(Integer pace) {
        if (pace == null) {
            return null;
        }
        return switch (pace) {
            case 1 -> "慢（每天 2-3 个点）";
            case 2 -> "适中（每天 3-4 个点）";
            case 3 -> "紧凑（每天 4-5 个点）";
            default -> null;
        };
    }

    /** 预算码 → 中文。非法值返回 null，等价于「未填写」 */
    private static String budgetLabel(Integer budgetLevel) {
        if (budgetLevel == null) {
            return null;
        }
        return switch (budgetLevel) {
            case 1 -> "经济";
            case 2 -> "舒适";
            case 3 -> "品质";
            default -> null;
        };
    }

    /**
     * 拆分逗号列表：兼容中英文逗号，逐段去空白、丢弃空段。
     *
     * <p>用户在输入框里手打时很容易带出 "晋菜, ,面食" 这种串，
     * 不清理就会渲染出「晋菜、、面食」。
     */
    private static List<String> splitCsv(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String segment : raw.split("[,，]")) {
            String trimmed = segment.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    /** 拼成中文列表；空列表返回 null（让调用方走「整行省略」） */
    private static String joinList(List<String> parts) {
        return parts.isEmpty() ? null : String.join(SEP, parts);
    }

    /**
     * 归一化调用方传入的列表（如 {@link ProfileOverrides#getTaboos()}）：
     * 去空白、丢弃空项。
     *
     * <p>与 {@link #splitCsv} 的区别是入参已经是列表 —— overrides 来自代码调用方
     * 而不是用户手输的文本框，所以不需要按逗号再拆一次；
     * 但它同样可能是「前端传了 ["香菜", " ", ""]」这种带空项的数据，仍需清理。
     */
    private static List<String> normalizeList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>(raw.size());
        for (String item : raw) {
            if (StringUtils.hasText(item)) {
                parts.add(item.trim());
            }
        }
        return parts;
    }

    private static void addIfPresent(List<String> lines, String label, String value) {
        if (StringUtils.hasText(value)) {
            lines.add(label + COLON + value);
        }
    }

    private static String trimToNull(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
