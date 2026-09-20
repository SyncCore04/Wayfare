package com.wayfare.connector.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link ExternalHttpClient} 的朴素实现，只负责「把请求发出去、把响应读回来」。
 *
 * <p><b>没有做的事（都属于 P1-D）</b>：重试、熔断、缓存、脱敏、调用日志落库。
 * 这里刻意保持无策略，避免 P1-D 实现时要在两层里改逻辑。
 *
 * <p>用 JDK 自带的 {@code java.net.http.HttpClient}（JDK 11+）而不是引新依赖：
 * 它支持 HTTP/2、连接池、异步与流式读取，够用；
 * 引 OkHttp/Apache HttpClient 只会增加依赖面，与「不新增同类库」的约束也冲突。
 */
@Component
public class SimpleExternalHttpClient implements ExternalHttpClient {

    private static final Logger log = LoggerFactory.getLogger(SimpleExternalHttpClient.class);

    /** 无参超时的兜底值；调用方一般都会显式传（来自配置的 llm.timeout-ms） */
    private static final int DEFAULT_TIMEOUT_MS = 30_000;

    private static final int CONNECT_TIMEOUT_SECONDS = 5;

    /**
     * HttpClient 是线程安全且建议复用的（内部维护连接池），
     * 所以建一次放字段里，不要每次请求 new 一个 —— 那会绕过连接复用，长连接全部退化成短连接。
     */
    private final HttpClient httpClient;

    public SimpleExternalHttpClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String getJson(String url, Map<String, String> headers, int timeoutMs) {
        return send(buildRequest(url, headers, null, timeoutMs, "GET"));
    }

    @Override
    public String postJson(String url, Map<String, String> headers, String jsonBody, int timeoutMs) {
        return send(buildRequest(url, headers, jsonBody, timeoutMs, "POST"));
    }

    /** 同步发送并统一处理超时/中断/IO 异常到 {@link ExternalHttpException} */
    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.debug("出站请求非 2xx: url={}, status={}", request.uri(), response.statusCode());
                throw ExternalHttpException.ofStatus(response.statusCode(), response.body());
            }
            return response.body();
        } catch (HttpTimeoutException e) {
            throw ExternalHttpException.noResponse("请求超时", true, e);
        } catch (InterruptedException e) {
            // 恢复中断标记，否则上层再也没机会感知到被中断（这是 Java 并发的基本纪律）
            Thread.currentThread().interrupt();
            throw ExternalHttpException.noResponse("请求被中断", false, e);
        } catch (java.io.IOException e) {
            throw ExternalHttpException.noResponse(e.getMessage(), false, e);
        }
    }

    @Override
    public void postJsonStream(String url, Map<String, String> headers, String jsonBody, int timeoutMs,
                               Consumer<String> onLine, Runnable onDone, Consumer<Throwable> onError) {
        HttpRequest request = buildRequest(url, headers, jsonBody, timeoutMs, "POST");
        try {
            // ofLines() 拿到的是惰性流：body 随读随到，所以这里确实是流式而不是等全量
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() / 100 != 2) {
                String body = response.body().collect(Collectors.joining("\n"));
                onError.accept(ExternalHttpException.ofStatus(response.statusCode(), body));
                return;
            }
            try (Stream<String> lines = response.body()) {
                lines.forEach(onLine);
            }
            onDone.run();
        } catch (HttpTimeoutException e) {
            onError.accept(ExternalHttpException.noResponse("流式请求超时 " + timeoutMs + "ms", true, e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onError.accept(ExternalHttpException.noResponse("流式请求被中断", false, e));
        } catch (Exception e) {
            onError.accept(e instanceof ExternalHttpException ? e
                    : ExternalHttpException.noResponse(e.getMessage(), false, e));
        }
    }

    private HttpRequest buildRequest(String url, Map<String, String> headers, String jsonBody,
                                     int timeoutMs, String method) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS))
                .header("Content-Type", "application/json; charset=utf-8")
                // 显式声明接受 SSE，部分网关据此才不缓冲响应（流式能否真流式，这一行很关键）
                .header("Accept", "text/event-stream, application/json");

        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody, StandardCharsets.UTF_8));
        }

        if (headers != null) {
            headers.forEach((k, v) -> {
                if (k != null && v != null) builder.header(k, v);
            });
        }
        return builder.build();
    }
}
