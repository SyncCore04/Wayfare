package com.wayfare.connector.map;

import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.ResultCode;

/**
 * 地图服务调用的统一异常。
 *
 * <p>与 {@code LlmException} 同构：把厂商差异（百度返回的 status 码、HTTP 层失败）
 * 收敛成项目自己的业务码，并带上「哪家出的问题」与「厂商原始状态码」，
 * 便于降级决策、熔断计数与排查。
 */
public class MapProviderException extends BusinessException {

    private final ResultCode resultCode;
    private final String provider;

    /** 厂商原始状态码（百度响应里的 status；HTTP 层失败时为空） */
    private final Integer vendorStatus;

    public MapProviderException(ResultCode resultCode, String provider, String detail) {
        this(resultCode, provider, detail, null, null);
    }

    public MapProviderException(ResultCode resultCode, String provider, String detail, Integer vendorStatus) {
        this(resultCode, provider, detail, vendorStatus, null);
    }

    public MapProviderException(ResultCode resultCode, String provider, String detail,
                               Integer vendorStatus, Throwable cause) {
        super(resultCode.getCode(), buildMessage(resultCode, provider, detail));
        this.resultCode = resultCode;
        this.provider = provider;
        this.vendorStatus = vendorStatus;
        if (cause != null) {
            initCause(cause);
        }
    }

    public ResultCode getResultCode() { return resultCode; }
    public String getProvider() { return provider; }
    public Integer getVendorStatus() { return vendorStatus; }

    private static String buildMessage(ResultCode code, String provider, String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(provider != null ? provider : "unknown").append("] ").append(code.getMessage());
        if (detail != null && !detail.isBlank()) {
            sb.append("：").append(detail);
        }
        return sb.toString();
    }
}
