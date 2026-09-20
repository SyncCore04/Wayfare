package com.wayfare.trip;

/**
 * 一次管线阶段的调用记录（P2-C）。
 *
 * <p>对应 {@code ai_generation_log} 的一行，但不含 {@code id} / {@code created_at}
 * —— 那两个由数据库负责，调用方不该设置。
 * 这与出站治理层的 {@code ExternalCallRecord} 是同一个思路：
 * <b>记录对象描述「发生了什么」，实体描述「怎么落库」</b>，两者分开，
 * 将来改表结构不必动调用方。
 *
 * <p><b>token 字段允许为 null，且 null 与 0 意义不同</b>：
 * 失败时可能压根没拿到 usage，null 表示「没有数据」，0 表示「确实是 0」。
 * 用 0 冒充「不知道」会让当日成本统计把「未计费」算成「没花钱」。
 *
 * @param userId           触发用户，系统任务传 null
 * @param tripId           关联行程，解析阶段可能还没有行程，传 null
 * @param stage            阶段名，用本类 {@code STAGE_*} 常量，别手写字符串
 * @param provider         厂商 glm / deepseek / mock / baidu
 * @param model            模型名，如 glm-4-flash
 * @param promptTokens     输入 token，拿不到传 null
 * @param completionTokens 输出 token，拿不到传 null
 * @param durationMs       本阶段耗时（毫秒）
 * @param success          是否成功
 * @param errorCode        失败归因码，成功时传 null
 * @param errorMsg         失败详情（写入前必须已脱敏）
 */
public record AiStageRecord(
        Long userId,
        Long tripId,
        String stage,
        String provider,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer durationMs,
        boolean success,
        AiErrorCode errorCode,
        String errorMsg) {

    // ---------- 阶段名（与《开发文档》§5 的七步管线一一对应）----------
    public static final String STAGE_PARSE = "PARSE";
    public static final String STAGE_CANDIDATE = "CANDIDATE";
    public static final String STAGE_PREORDER = "PREORDER";
    public static final String STAGE_COMPOSE = "COMPOSE";
    public static final String STAGE_VALIDATE = "VALIDATE";
    public static final String STAGE_ROUTE = "ROUTE";
    public static final String STAGE_COPY = "COPY";

    /**
     * 总 token = 输入 + 输出。
     *
     * <p><b>两边都拿不到时返回 null</b>（而不是 0）；只拿到一边时返回那一边的值，
     * 不做「另一边当 0」的处理 —— 那会把「不知道」算成「确实是 0」，
     * 直接污染成本统计。
     */
    public Integer totalTokens() {
        if (promptTokens == null && completionTokens == null) {
            return null;
        }
        return (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
    }
}
