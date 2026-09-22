package com.wayfare.trip;

import com.wayfare.dto.TripDraftDTO;

/**
 * 管线进度回调（P4-A）。
 *
 * <p>同步接口 {@code /plan/sync} 与单测都传 {@link #NOOP}，所以
 * {@link TripOrchestrator} 里不需要到处判空 —— 这是把「可选的进度上报」
 * 做成默认无害的空实现，而不是在编排逻辑里散落 if。
 *
 * <p><b>事件契约（与《开发文档》§7.1 一致）</b>：阶段名用 {@code AiStageRecord.STAGE_*}，
 * 状态用本类的 {@code STATUS_*}；SSE 层直接把这三个字段塞进 {@code event: stage} 的 data。
 *
 * <p><b>{@link #onItinerary} 是「降级出口」</b>：它在 Step6 事实补全（含补全后的重校验）
 * 完成后触发，此刻行程骨架已完整可用（前端可渲染时间轴 / 地图 / 费用表）。
 * 之后文案生成失败也不影响用户看到这份行程 —— 这正是把「骨架」与「文案」分开推的原因。
 */
public interface TripProgressListener {

    String STATUS_RUNNING = "RUNNING";
    String STATUS_DONE = "DONE";
    /** 能力降级（地图不可用转估算），不是故障，所以单独一个状态而不是 error */
    String STATUS_FALLBACK = "FALLBACK";

    /** 什么都不做的实现：同步调用与单测用它 */
    TripProgressListener NOOP = new TripProgressListener() { };

    /**
     * 某阶段的状态变化。
     *
     * @param stage   阶段名，取 {@code AiStageRecord.STAGE_*} 常量，不要手写字符串
     * @param status  {@link #STATUS_RUNNING} / {@link #STATUS_DONE} / {@link #STATUS_FALLBACK}
     * @param message 给用户看的一句话（面向非技术人员）
     */
    default void onStage(String stage, String status, String message) { }

    /**
     * Step6 事实补全完成 —— 行程骨架可用了。
     *
     * @param draft 完整的 {@link TripDraftDTO}，每个点位都带 verifyStatus 与 dataSource
     */
    default void onItinerary(TripDraftDTO draft) { }
}