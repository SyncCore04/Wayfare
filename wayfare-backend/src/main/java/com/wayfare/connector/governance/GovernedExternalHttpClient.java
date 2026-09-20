package com.wayfare.connector.governance;

import com.wayfare.common.util.MaskUtil;
import com.wayfare.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 出站调用的统一治理层：<b>重试 + 调用日志</b>，然后委托给真正的传输实现。
 *
 * <p>这是 {@code @Primary} 的 {@link ExternalHttpClient} 实现 ——
 * GLM / DeepSeek / 百度地图拿到的都是它，所以「加治理」这件事对上层零改动。
 * 传输细节仍在 {@link SimpleExternalHttpClient} 里，两层职责分明：
 * <b>这层决定「重不重试、记什么日志」，下层只管「把请求发出去」。</b>
 *
 * <h3>重试策略（手册规定，每一条都有理由）</h3>
 * <ul>
 *   <li><b>仅 GET 重试，最多 2 次，退避 200ms / 600ms（指数）</b> ——
 *       GET 是幂等的，重试不会产生副作用；退避而非立刻重试，是为了给对端一点恢复时间。</li>
 *   <li><b>POST 默认不重试，唯一例外是 HTTP 429</b> —— 大模型与部分地图接口的 POST 可能产生费用或副作用，
 *       重复执行等于重复扣费/重复下单。但 <b>429 是「对端主动拒绝、请求根本没被处理」</b>：
 *       没有 token 消耗、没有费用、没有副作用，重试它是安全的
 *       （详见 {@link #executeWithRetryOnRateLimit}）。其余任何 POST 失败（含超时）一律不重试。</li>
 *   <li><b>跳过 4xx（429 除外）</b> —— 401/403/400 这类是「请求本身不对」，
 *       重试只会得到同样的错误，白等两次退避还污染日志。
 *       唯独 429（限流）值得等一会儿再试。</li>
 *   <li><b>没拿到响应（超时/连接失败）算可重试</b> —— 这类是典型的瞬时故障。</li>
 * </ul>
 *
 * <h3>日志</h3>
 * 每次尝试都落一条 {@code external_call_log}，所以「GET 首次失败第二次成功」会留下 2 条记录，
 * 而「POST 非 429 失败」只有 1 条 —— 这是手册验收第 2、3 条的判定依据。
 * <b>记录前一律过 {@link MaskUtil} 脱敏</b>，库里不允许出现完整密钥。
 */
@Component
@Primary
public class GovernedExternalHttpClient implements ExternalHttpClient {

    private static final Logger log = LoggerFactory.getLogger(GovernedExternalHttpClient.class);

    /** 最多重试次数（不含首次） */
    private static final int MAX_RETRIES = 2;

    /** 退避表（GET 用）：第 1 次重试等 200ms，第 2 次等 600ms */
    private static final long[] BACKOFF_MS = {200L, 600L};

    /**
     * 限流退避表（POST 的 429 专用）：第 1 次等 1s，第 2 次等 3s。
     *
     * <p><b>为什么比 GET 的退避长这么多</b>：429 表示对端已经过载，
     * 隔 200ms 再打过去基本还是 429 —— 实测免费大模型在百毫秒级退避下几乎必然连续失败，
     * 那样的重试只是把「1 次失败」变成「3 次失败」，白白拉长用户等待。
     * 限流要等出量级差才有意义。
     */
    private static final long[] RATE_LIMIT_BACKOFF_MS = {1000L, 3000L};

    private final SimpleExternalHttpClient transport;
    private final ExternalCallLogService callLogService;

    public GovernedExternalHttpClient(SimpleExternalHttpClient transport,
                                      ExternalCallLogService callLogService) {
        this.transport = transport;
        this.callLogService = callLogService;
    }

    @Override
    public String getJson(String url, Map<String, String> headers, int timeoutMs) {
        return executeWithRetry("GET", url, headers, null, timeoutMs);
    }

    @Override
    public String postJson(String url, Map<String, String> headers, String jsonBody, int timeoutMs) {
        // 只对 429 重试，其余失败一律不重试（见类注释与 executeWithRetryOnRateLimit）
        return executeWithRetryOnRateLimit("POST", url, headers, jsonBody, timeoutMs);
    }

    @Override
    public void postJsonStream(String url, Map<String, String> headers, String jsonBody, int timeoutMs,
                               Consumer<String> onLine, Runnable onDone, Consumer<Throwable> onError) {
        long start = System.currentTimeMillis();
        transport.postJsonStream(url, headers, jsonBody, timeoutMs, onLine,
                () -> {
                    // 流式的成功要在 onDone 里才敢记：传输层返回不代表流内没出错，
                    // 只有正常读完才算真成功
                    record("POST", url, jsonBody, 200, System.currentTimeMillis() - start, true, null);
                    onDone.run();
                },
                error -> {
                    // 流式失败也要留痕：这类「已经推了一半又断」最难排查，日志是唯一线索
                    record("POST", url, jsonBody, null, System.currentTimeMillis() - start,
                            false, error == null ? "stream error" : error.getMessage());
                    onError.accept(error);
                });
        // 流式同样不重试：内容已经往外推了，换个连接重来会产生重复内容
    }

    /** GET 专用：带重试 */
    private String executeWithRetry(String method, String url, Map<String, String> headers,
                                    String body, int timeoutMs) {
        int attempt = 0;
        while (true) {
            try {
                return doExecute(method, url, headers, body, timeoutMs);
            } catch (ExternalHttpException e) {
                if (attempt >= MAX_RETRIES || !isRetryable(e)) {
                    throw e;
                }
                long backoff = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
                log.warn("GET 调用失败将重试（第 {} 次，退避 {}ms）: {} —— {}",
                        attempt + 1, backoff, MaskUtil.maskUrlSecrets(url), e.getMessage());
                sleepQuietly(backoff);
                attempt++;
            }
        }
    }

    /**
     * POST 专用的窄口径重试：<b>只对 HTTP 429 重试</b>，其余任何失败一律不重试。
     *
     * <p><b>为什么这条不与「POST 不重试」冲突</b>：那条规则的理由是
     * 「重复执行等于重复扣费/重复下单」—— 说的是<b>请求已经被处理</b>的情形。
     * 而 429 是<b>对端主动拒绝、请求根本没被处理</b>：没有 token 消耗、没有费用、没有副作用。
     * 「重试一个被拒绝的请求」与「重试一个已执行的请求」风险完全不同，不能混为一谈。
     *
     * <p>类注释里「跳过 4xx（429 除外）」本来就已经表达了这层意图 ——
     * 原先的实现只是被「POST 直接走 doExecute、不进重试循环」这条更粗的规则盖住了。
     * 本方法把 429 这个例外补完整。
     *
     * <p><b>这不是可有可无的优化</b>：免费大模型过载时<b>只返回 429</b>
     * （实测 GLM 返回业务码 1305 → HTTP 429），而系统的降级链是<b>厂商粒度</b>
     * （glm/deepseek/mock），同一厂商换个模型不算降级 —— 所以没有这条重试，
     * 「选了个过载的免费模型」就等于「每次调用直接失败」。重试是唯一的自救手段。
     *
     * <p>流式（{@link #postJsonStream}）仍然完全不重试：内容已经往外推了，
     * 换个连接重来会产生重复内容，那是比失败更糟的结果。
     */
    private String executeWithRetryOnRateLimit(String method, String url, Map<String, String> headers,
                                               String body, int timeoutMs) {
        int attempt = 0;
        while (true) {
            try {
                return doExecute(method, url, headers, body, timeoutMs);
            } catch (ExternalHttpException e) {
                if (attempt >= MAX_RETRIES || !isRateLimited(e)) {
                    throw e;
                }
                long backoff = RATE_LIMIT_BACKOFF_MS[Math.min(attempt, RATE_LIMIT_BACKOFF_MS.length - 1)];
                log.warn("POST 被限流（HTTP 429），将重试（第 {} 次，退避 {}ms）: {}",
                        attempt + 1, backoff, MaskUtil.maskUrlSecrets(url));
                sleepQuietly(backoff);
                attempt++;
            }
        }
    }

    /**
     * 是否被限流（429）。<b>只有它值得对 POST 重试</b> —— 理由见 {@link #executeWithRetryOnRateLimit}。
     * 注意「没拿到响应」（超时/连接失败）对 POST 返回 false：那类无法确定请求是否已被处理，不能重试。
     */
    private boolean isRateLimited(ExternalHttpException e) {
        return e.hasResponse() && e.getStatus() == 429;
    }

    /** 真正发一次请求并记一条日志 */
    private String doExecute(String method, String url, Map<String, String> headers,
                             String body, int timeoutMs) {
        long start = System.currentTimeMillis();
        try {
            String response = "GET".equals(method)
                    ? transport.getJson(url, headers, timeoutMs)
                    : transport.postJson(url, headers, body, timeoutMs);
            // 传输层只对非 2xx 抛异常，所以能走到这里就是 2xx。
            // 这里记 200 是简化（没回传真实状态码），对统计「成功/失败」够用；
            // 要精确状态码的话需要在传输层回传，留给 P6-B 看板需要时再改。
            record(method, url, body, 200, System.currentTimeMillis() - start, true, null);
            return response;
        } catch (ExternalHttpException e) {
            record(method, url, body, e.hasResponse() ? e.getStatus() : null,
                    System.currentTimeMillis() - start, false, e.getMessage());
            throw e;
        } catch (Exception e) {
            record(method, url, body, null, System.currentTimeMillis() - start, false, e.getMessage());
            throw e;
        }
    }

    /**
     * 是否值得重试。
     * 没拿到响应（超时/连接失败）→ 值得；429 → 值得；其余 4xx → 不值得；5xx → 值得。
     */
    private boolean isRetryable(ExternalHttpException e) {
        if (!e.hasResponse()) {
            return true;
        }
        int status = e.getStatus();
        if (status == 429) {
            return true;
        }
        if (status >= 400 && status < 500) {
            return false;
        }
        return status >= 500;
    }

    /** 落一条日志（内部完成脱敏与截断） */
    private void record(String method, String url, String body, Integer httpStatus,
                        long durationMs, boolean success, String errorMsg) {
        try {
            StringBuilder summary = new StringBuilder(method).append(' ')
                    .append(MaskUtil.maskUrlSecrets(url));
            if (body != null && !body.isEmpty()) {
                summary.append(" body=").append(body);
            }
            callLogService.record(new ExternalCallRecord(
                    // 用户上下文可能为空（系统任务、启动期调用），此时 user_id 记 null
                    UserContext.getUserId(),
                    // trip_id 要到 P3 才有行程概念，P1 阶段一律 null
                    null,
                    inferConnector(url),
                    inferApiName(url),
                    // sanitize 内部会再跑一遍 URL/JSON/Bearer 脱敏并截断到 500
                    MaskUtil.sanitize(summary.toString()),
                    httpStatus,
                    (int) Math.min(durationMs, Integer.MAX_VALUE),
                    success,
                    errorMsg == null ? null : MaskUtil.sanitize(errorMsg, 400)));
        } catch (Exception e) {
            log.warn("构造外部调用日志失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * 从 URL 推断连接器类型。
     *
     * <p>为什么用推断而不是让调用方传：那会给所有 Provider 的方法签名都加一个参数，
     * 而域名与连接器其实是一一对应的（没有一家厂商的域名会同时服务两类连接器）。
     * 真出现推断不了的域名就记 OTHER，一眼能看出来要补规则。
     */
    private String inferConnector(String url) {
        if (url == null) return ExternalCallRecord.CONNECTOR_OTHER;
        String u = url.toLowerCase();
        if (u.contains("bigmodel.cn") || u.contains("deepseek.com")) {
            return ExternalCallRecord.CONNECTOR_LLM;
        }
        if (u.contains("map.baidu.com")) {
            return ExternalCallRecord.CONNECTOR_BAIDU_MAP;
        }
        return ExternalCallRecord.CONNECTOR_OTHER;
    }

    /** 取 URL 的 path 作为接口名，例如 place/v2/search、directionlite/v1/driving */
    private String inferApiName(String url) {
        if (url == null) return "";
        try {
            String path = java.net.URI.create(url).getPath();
            if (path == null) return "";
            return path.startsWith("/") ? path.substring(1) : path;
        } catch (Exception e) {
            return "";
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // 恢复中断标记：被中断时不该继续重试，直接把中断状态还回去
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重试等待被中断", e);
        }
    }
}
