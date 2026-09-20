package com.wayfare.connector.governance;

/**
 * 一条外部调用记录（治理层产出，交给 {@link ExternalCallLogService} 落库）。
 *
 * @param requestSummary <b>传进来之前必须已脱敏</b>（用 MaskUtil.sanitize）。
 *                       这里不再二次脱敏，是为了让「脱敏」这件事只发生在出口一处可追溯。
 */
public record ExternalCallRecord(
        Long userId,
        Long tripId,
        String connector,
        String apiName,
        String requestSummary,
        Integer httpStatus,
        Integer durationMs,
        boolean success,
        String errorMsg
) {
    /** 连接器类型常量，与 external_call_log.connector 的取值一致 */
    public static final String CONNECTOR_LLM = "LLM";
    public static final String CONNECTOR_BAIDU_MAP = "BAIDU_MAP";
    public static final String CONNECTOR_OTHER = "OTHER";
}
