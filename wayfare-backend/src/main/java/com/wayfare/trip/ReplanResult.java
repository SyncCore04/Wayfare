package com.wayfare.trip;

import com.wayfare.dto.TripDraftDTO;

/**
 * 约束校验 + 回喂重排的最终结果（P3-E）。
 *
 * <p><b>它同时承担「成功」与「没完全成功」两种结局</b>：
 * 重排到轮次上限仍不通过时，<b>不抛异常</b>，而是把<b>当前最优版本</b>连同
 * {@link #report()} 一起返回，由前端展示「以下问题未能自动解决」清单。
 * 手册明确要求这么做 —— 一份「有 1 个点位偏远的行程」对用户仍然有用，
 * 而抛异常会把它整份丢掉（能力降级被做成功能降级）。
 *
 * @param draft      当前最优的行程草稿；编排彻底失败时为 null
 * @param report     对 {@link #draft()} 的校验报告；无草稿时为 null
 * @param replanRounds 实际发生的重排轮数。<b>要累加进 {@code trip.generation_rounds}</b>（P3-F 落库）
 * @param resolved   是否已完全通过校验（{@link #report()} 为空违规）
 * @param userHint   未完全通过时给用户看的一句话风险提示；已通过时为 null
 * @param composeError 编排彻底失败时的原因（如「候选池为空」「天数未确定」）；成功时为 null
 */
public record ReplanResult(
        TripDraftDTO draft,
        ValidationReport report,
        int replanRounds,
        boolean resolved,
        String userHint,
        String composeError) {

    public boolean hasDraft() {
        return draft != null;
    }

    /** 编排阶段就失败了（连草稿都没有），调用方应把候选池交给前端手选 */
    public static ReplanResult composeFailed(String error) {
        return new ReplanResult(null, null, 0, false, null, error);
    }

    public static ReplanResult of(TripDraftDTO draft, ValidationReport report, int rounds) {
        boolean ok = report != null && report.passed();
        return new ReplanResult(draft, report, rounds, ok, ok ? null : report.toUserHint(), null);
    }
}
