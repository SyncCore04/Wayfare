package com.wayfare.connector.llm;

import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.ResultCode;

/**
 * 大模型调用的统一异常。
 *
 * <p>存在的意义：把「HTTP 401 / 429 / 超时 / 5xx」这些厂商差异，统一收敛成项目自己的业务码
 * （{@link ResultCode#LLM_AUTH_FAIL} 等），上层据此就能判断<b>该不该降级</b>、
 * 以及<b>给用户什么提示</b>，而不必散落着写 HTTP 状态码判断。
 *
 * <p>额外携带 {@link #getProvider()}：降级链需要知道「是哪一家失败了」，
 * 才能正确地跳到下一家、并把原因写进 meta（P1-D/P3 会用到）。
 *
 * <p>继承 {@code BusinessException} 而不是另起炉灶：这样全局异常处理器无需改动，
 * 前端拿到的仍是统一的 {@code {code, message}} 结构。
 */
public class LlmException extends BusinessException {

    private final ResultCode resultCode;
    private final String provider;

    public LlmException(ResultCode resultCode, String provider, String detail) {
        super(resultCode.getCode(), buildMessage(resultCode, provider, detail));
        this.resultCode = resultCode;
        this.provider = provider;
    }

    public LlmException(ResultCode resultCode, String provider, String detail, Throwable cause) {
        super(resultCode.getCode(), buildMessage(resultCode, provider, detail));
        this.resultCode = resultCode;
        this.provider = provider;
        if (cause != null) {
            initCause(cause);
        }
    }

    public ResultCode getResultCode() { return resultCode; }

    /** 出问题的厂商名（glm / deepseek / mock） */
    public String getProvider() { return provider; }

    /**
     * 是否属于「这一家暂时不行、换一家可能行」的失败。
     * 认证失败也算 —— Key 配错了换一家确实可能成功。
     * 参数被拒绝（400）则通常是我们自己的 prompt/请求体有问题，换厂商意义不大，
     * 但为了让降级链尽可能兜住，仍按可降级处理，只是原因会写清楚。
     */
    public boolean isFallbackWorthy() {
        return resultCode != ResultCode.LLM_DISABLED;
    }

    private static String buildMessage(ResultCode resultCode, String provider, String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(provider != null ? provider : "unknown").append("] ")
                .append(resultCode.getMessage());
        if (detail != null && !detail.isBlank()) {
            sb.append("：").append(detail);
        }
        return sb.toString();
    }
}
