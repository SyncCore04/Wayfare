package com.wayfare.connector.governance;

/**
 * 出站 HTTP 异常，把「拿到了错误响应」与「压根没拿到响应」区分开。
 *
 * <p>为什么要把这个区分做出来：上层要据此映射成不同的业务码 ——
 * 401 是 Key 配错（要管理员动手），429 是限流（等一会儿就好），
 * 超时是网络问题（可以重试），5xx 是对面挂了（换一家）。
 * 如果只抛一个笼统的 RuntimeException，这四类就没法分辨了。
 *
 * <p>只提供静态工厂、不暴露公有构造器，避免出现 {@code (0, "失败")} 这种
 * 「状态码与语义对不上」的调用方式。
 */
public class ExternalHttpException extends RuntimeException {

    /** HTTP 状态码；0 表示没拿到响应（连接失败/超时/被中断） */
    private final int status;

    /** 响应体原文，只用于日志与排查；可能含敏感信息，不要直接回给前端 */
    private final String responseBody;

    private final boolean timeout;

    private ExternalHttpException(String message, int status, String responseBody,
                                  boolean timeout, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.responseBody = responseBody;
        this.timeout = timeout;
    }

    /** 拿到了 HTTP 响应，但状态码非 2xx */
    public static ExternalHttpException ofStatus(int status, String responseBody) {
        return new ExternalHttpException(
                "外部服务返回 HTTP " + status + brief(responseBody),
                status, responseBody, false, null);
    }

    /** 压根没拿到响应：连接失败 / 超时 / 线程被中断 */
    public static ExternalHttpException noResponse(String detail, boolean timeout, Throwable cause) {
        String message = "调用外部服务失败" + (timeout ? "（超时）" : "")
                + (detail == null || detail.isBlank() ? "" : "：" + detail);
        return new ExternalHttpException(message, 0, null, timeout, cause);
    }

    public int getStatus() { return status; }
    public String getResponseBody() { return responseBody; }
    public boolean isTimeout() { return timeout; }

    /** 是否拿到了 HTTP 响应（用于区分网络层失败与业务层失败） */
    public boolean hasResponse() { return status > 0; }

    private static String brief(String body) {
        if (body == null || body.isBlank()) return "";
        return "：" + (body.length() > 300 ? body.substring(0, 300) + "..." : body);
    }
}
