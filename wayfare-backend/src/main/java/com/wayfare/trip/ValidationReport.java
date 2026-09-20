package com.wayfare.trip;

import java.util.ArrayList;
import java.util.List;

/**
 * 约束校验报告（P3-E）。
 *
 * <p><b>{@code passed} 由 {@code violations} 是否为空推导，不单独存一个字段</b> ——
 * 存两个字段就有「passed=true 但 violations 非空」这种自相矛盾的可能，
 * 那种状态一旦出现，排查起来比重算一遍贵得多。
 *
 * <p>报告同时承担两个职责：给用户看的「以下问题未能自动解决」清单，
 * 以及给模型看的纠正 prompt 素材。所以 {@link #toFeedbackText()} 的格式要稳定。
 */
public record ValidationReport(List<Violation> violations) {

    public ValidationReport {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public static ValidationReport pass() {
        return new ValidationReport(List.of());
    }

    public boolean passed() {
        return violations.isEmpty();
    }

    /** 有没有必须重排的 HIGH 违规 */
    public boolean hasHigh() {
        return violations.stream().anyMatch(Violation::isHigh);
    }

    public long countOf(String severity) {
        return violations.stream().filter(v -> severity.equals(v.severity())).count();
    }

    /**
     * 「严重度排序后的违规数」—— 用来比较两版行程哪个更好。
     *
     * <p>比较规则：<b>先比 HIGH 数，再比 MEDIUM 数，最后比总数</b>。
     * 不能只比总数：一版有 1 个 HIGH、另一版有 3 个 LOW，
     * 总数上前者少，但前者是「编造了景点」这种不能接受的错误，后者只是「多花了几十块」。
     */
    public List<Integer> severitySignature() {
        return List.of(
                (int) countOf(Violation.SEVERITY_HIGH),
                (int) countOf(Violation.SEVERITY_MEDIUM),
                violations.size());
    }

    /** 按严重度排序后的违规列表（HIGH 在前），供展示与回喂 */
    public List<Violation> sorted() {
        List<Violation> copy = new ArrayList<>(violations);
        copy.sort((a, b) -> Integer.compare(rank(b.severity()), rank(a.severity())));
        return copy;
    }

    private static int rank(String severity) {
        return switch (severity) {
            case Violation.SEVERITY_HIGH -> 3;
            case Violation.SEVERITY_MEDIUM -> 2;
            default -> 1;
        };
    }

    /**
     * 拼成回喂给模型的纠正正文（不含标题，标题由调用方加）。
     *
     * <p>逐条列出「第几天 / 哪一项 / 什么问题 / 怎么改」。<b>不许只给一句「行程有问题」</b> ——
     * 模型只能靠猜，大概率原样再错一遍。
     */
    public String toFeedbackText() {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Violation v : sorted()) {
            sb.append(i++).append(". [").append(v.severity()).append("] ")
              .append(v.toFeedbackLine()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 给用户看的风险提示（达上限仍不通过时展示） */
    public String toUserHint() {
        return "有 " + countOf(Violation.SEVERITY_HIGH) + " 个严重问题、"
                + countOf(Violation.SEVERITY_MEDIUM) + " 个一般问题未能自动解决";
    }
}
