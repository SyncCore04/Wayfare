package com.wayfare.trip;

/**
 * 一次生成运行的上下文 —— 让管线里各阶段都能拿到本次的 {@code tripId}。
 *
 * <p><b>为什么需要它（这是一个真 bug 的修复）</b>：
 * P4-C 的 {@code AiLogService.breakdownByTrip(tripId)} 按 {@code trip_id} 归集成本拆解，
 * 但 2026-09-22 的真实联调发现：{@code PARSE} / {@code CANDIDATE} / {@code COMPOSE} / {@code VALIDATE}
 * 四个阶段的 {@code ai_generation_log.trip_id} <b>全是 NULL</b>，只有 {@code ROUTE} / {@code COPY} 带得上。
 * 后果是拆解表<b>严重漏算</b> —— 一次真实生成烧了约 12963 tokens，拆解表只统计得到 3545（只有 COPY 那条）。
 *
 * <p>根因是那几处的 {@code new AiStageRecord(..., null, ...)} 写死了 null，
 * 其中 {@code IntentParser} 那行的注释写着「解析阶段还没有 tripId」——
 * <b>这句注释是错的</b>：{@code TripOrchestrator.orchestrate} 在跑 Step1 <b>之前</b>
 * 就已经 {@code createDraft} 拿到了 tripId（P3-F 的设计就是「先落草稿，后面任何一步失败都能续作」）。
 *
 * <p><b>为什么用 ThreadLocal 而不是加方法参数</b>：
 * 给 {@code parseIntent} / {@code searchCandidates} / {@code compose} 各加一个 tripId 参数，
 * 会波及四个组件及其全部调用方与 74 个已有单测 —— 为了补一个日志字段付这个代价不值。
 * 项目里 {@code UserContext} 已经是同一模式（这些组件本来就在用它取 userId），
 * 语义上也一致：<b>「本次运行」与「当前登录用户」一样，都是贯穿管线的环境事实</b>。
 *
 * <p><b>不 set 时 {@code getTripId()} 返回 null</b>，行为与修复前完全一致 ——
 * 所以单测不 set 也照常通过，不会因为引入本类而变红。
 *
 * <p>⚠️ <b>用完必须 {@link #clear()}</b>：生成跑在线程池里、线程会被复用，
 * 残留的 tripId 会让下一个任务的日志挂到别人的行程上（与 {@code UserContext} 同一个坑）。
 */
public final class TripRunContext {

    private static final ThreadLocal<Long> CURRENT_TRIP_ID = new ThreadLocal<>();

    private TripRunContext() {
    }

    /** 开始一次生成运行。传 null 表示「本次运行还没有落草稿」（如只跑解析的 {@code /trip/parse}） */
    public static void set(Long tripId) {
        CURRENT_TRIP_ID.set(tripId);
    }

    /** 本次运行的行程 ID；未设置时为 null */
    public static Long getTripId() {
        return CURRENT_TRIP_ID.get();
    }

    /** 结束一次生成运行。<b>必须在 finally 里调用</b>（线程池复用，残留会串数据） */
    public static void clear() {
        CURRENT_TRIP_ID.remove();
    }
}
