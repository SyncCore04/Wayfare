package com.wayfare.connector.governance;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 所有出站 HTTP 调用的统一入口。
 *
 * <p><b>为什么现在就要有它</b>：P1-B 需要调 GLM/DeepSeek，而手册要求
 * 「请求走 P1-D 的 ExternalHttpClient，不要自己 new HttpClient」。
 * 但 P1-D 排在 P1-B 之后 —— 所以这里先把<b>接缝</b>立起来（接口 + 一个朴素实现），
 * P1-B 只依赖这个接口；P1-D 再往实现里加东西，上层代码一行都不用改。
 * 这比「先自己 new，回头再重构」省事得多。
 *
 * <p><b>P1-D 会在这里补上</b>（当前实现都没有）：
 * 超时与重试（含指数退避、只重试 GET）、熔断降级、结果缓存、
 * 请求/响应脱敏、外部调用日志落库。所以实现类刻意保持「无状态、无策略」，
 * 把决策权留给 P1-D。
 */
public interface ExternalHttpClient {

    /**
     * GET 并返回响应体文本（百度地图这类把参数放在 query 上的接口要用）。
     *
     * <p>单独有这个方法的另一个原因：P1-D 的重试策略是「只重试 GET、POST 不重试」——
     * 因为 POST 可能是非幂等的（重复提交会重复扣费/重复下单），
     * 要区分重试资格，客户端就必须能从签名上分清 GET 与 POST。
     *
     * @param timeoutMs 单次请求超时（毫秒）；传 &lt;=0 表示用默认值
     * @throws ExternalHttpException 非 2xx，或连接失败/超时
     */
    String getJson(String url, Map<String, String> headers, int timeoutMs);

    /**
     * POST JSON，同步返回响应体文本。
     *
     * @param timeoutMs 单次请求超时（毫秒）；传 &lt;=0 表示用默认值
     * @throws ExternalHttpException 非 2xx，或连接失败/超时
     */
    String postJson(String url, Map<String, String> headers, String jsonBody, int timeoutMs);

    /**
     * POST JSON 并流式读取响应文本行（SSE 场景：逐行 {@code data: {...}}）。
     *
     * <p>失败不抛异常而是交给 {@code onError}：流式场景下「已经推了一部分又失败」
     * 比「一开始就失败」更常见，调用方需要在同一个回调里处理两种进度状态。
     */
    void postJsonStream(String url, Map<String, String> headers, String jsonBody, int timeoutMs,
                        Consumer<String> onLine, Runnable onDone, Consumer<Throwable> onError);
}
