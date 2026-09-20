package com.wayfare.trip;

/**
 * 一条约束违规（P3-E）。
 *
 * <p><b>{@code severity} 决定要不要重排，{@code message} + {@code suggestion} 决定怎么重排</b> ——
 * 这两个字段不是给人看的装饰，它们会被拼进纠正 prompt 回喂给模型。
 * 所以 {@code message} 必须说清「哪一天、哪一项、错在哪」，
 * {@code suggestion} 必须给出「该怎么改」（例如「从候选池里换一个点」）。
 * 写一句「行程有问题」等于没写：模型只能靠猜，大概率原样再错一遍。
 *
 * @param code       规则名，取值见 {@link ItineraryValidator} 的 {@code RULE_*} 常量
 * @param severity   严重度，见 {@link #SEVERITY_HIGH} / {@link #SEVERITY_MEDIUM} / {@link #SEVERITY_LOW}
 * @param dayIndex   第几天（从 1 起）；与整天无关的违规（如预算）为 null
 * @param itemRef    涉及的条目引用（通常是 poiRef）；与具体条目无关时为 null
 * @param message    人话描述，会说清「第 N 天第 M 项 XXX 怎么了」
 * @param suggestion 修改建议，直接告诉模型该怎么改
 */
public record Violation(
        String code,
        String severity,
        Integer dayIndex,
        String itemRef,
        String message,
        String suggestion) {

    /** 必须重排（识破编造景点、违反硬约束、空间上明显不合理） */
    public static final String SEVERITY_HIGH = "HIGH";
    /** 建议重排（时序冲突、通勤过量、超支较多） */
    public static final String SEVERITY_MEDIUM = "MEDIUM";
    /** 只提示不重排（轻微超支） */
    public static final String SEVERITY_LOW = "LOW";

    public static Violation high(String code, Integer dayIndex, String itemRef,
                                 String message, String suggestion) {
        return new Violation(code, SEVERITY_HIGH, dayIndex, itemRef, message, suggestion);
    }

    public static Violation medium(String code, Integer dayIndex, String itemRef,
                                   String message, String suggestion) {
        return new Violation(code, SEVERITY_MEDIUM, dayIndex, itemRef, message, suggestion);
    }

    public static Violation low(String code, Integer dayIndex, String itemRef,
                                String message, String suggestion) {
        return new Violation(code, SEVERITY_LOW, dayIndex, itemRef, message, suggestion);
    }

    public boolean isHigh() {
        return SEVERITY_HIGH.equals(severity);
    }

    /**
     * 回喂给模型时的一行文本。
     *
     * <p>刻意带上「第几天 / 哪一项」的前缀：模型对着结构化文本比对着自然语言更容易定位到要改的地方。
     */
    public String toFeedbackLine() {
        StringBuilder sb = new StringBuilder();
        if (dayIndex != null) {
            sb.append("第 ").append(dayIndex).append(" 天");
        }
        if (itemRef != null && !itemRef.isBlank()) {
            sb.append(dayIndex != null ? " 的「" : "「").append(itemRef).append('」');
        }
        sb.append(dayIndex != null || (itemRef != null && !itemRef.isBlank()) ? "：" : "");
        sb.append(message);
        if (suggestion != null && !suggestion.isBlank()) {
            sb.append(" → ").append(suggestion);
        }
        return sb.toString();
    }
}
