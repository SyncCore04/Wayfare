package com.wayfare.connector.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wayfare.common.result.ResultCode;
import com.wayfare.connector.governance.ExternalHttpClient;
import com.wayfare.connector.governance.ExternalHttpException;
import com.wayfare.service.SysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 兼容协议厂商的公共实现基类。
 *
 * <p>GLM 与 DeepSeek 都遵循 OpenAI 的 Chat Completions 协议，差异只在少数几个字段上。
 * 把 90% 的公共逻辑（拼请求体、解析响应、解析流、异常映射、token 统计）收在这里，
 * <b>子类只覆盖 {@link #customizeRequestBody} 与 {@link #buildHeaders} 这类钩子</b>，
 * 这样新增第三家厂商的成本就是「写一个几十行的子类」。
 *
 * <p>所有出站请求都走 {@link ExternalHttpClient}（P1-D 会往里面加熔断与重试），
 * 本类<b>不直接创建 HttpClient</b>。
 */
public abstract class AbstractOpenAiCompatibleProvider implements LlmProvider {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final ObjectMapper objectMapper;
    protected final ExternalHttpClient httpClient;

    /** 该厂商的 L1 配置（baseUrl / apiKey / model / temperature / maxTokens） */
    private final LlmProperties.LlmProviderConfig config;

    /** 单次调用超时的 L1 兜底值（application.yml 的 llm.timeout-ms） */
    private final int timeoutMs;

    /**
     * L2 覆盖入口（`sys_config` 的 `llm.timeout-ms`）。
     *
     * <p>🔴 <b>2026-09-23 补：这个键以前是「假可调」的</b> —— 它列在 sys_config 里、
     * 也出现在后台「连接器管理」页的参数表上，但 Provider 只在<b>构造时</b>读一次 application.yml，
     * 于是改库改界面<b>都不生效</b>。实测代价：把 `llm.timeout-ms` 从 90000 改成 180000 后重启都没有，
     * 请求依然在 90 秒被掐断（CANDIDATE 阶段稳定失败），排查时极容易被带偏。
     *
     * <p>用 setter 注入而不是构造参数：四个 Provider 子类的构造函数已经很长，
     * 而这里只需要「能读到 L2」这一件事，不必为此改所有子类签名。
     * 单测里直接 new 出来的 Provider 拿不到它，自动回落到 L1 值 —— 行为不变。
     */
    private SysConfigService sysConfigService;

    @Autowired(required = false)
    public void setSysConfigService(SysConfigService sysConfigService) {
        this.sysConfigService = sysConfigService;
    }

    protected AbstractOpenAiCompatibleProvider(ObjectMapper objectMapper,
                                               ExternalHttpClient httpClient,
                                               LlmProperties.LlmProviderConfig config,
                                               int timeoutMs) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.config = config;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 本次调用真正生效的超时：L2（sys_config）优先，读不到才用 L1（application.yml）。
     *
     * <p>每次调用都读一次，所以改完后台参数<b>不用重启</b>就生效 —— 这才是 P1-A 分层设计里
     * 「运行期可调」应该有的样子。
     */
    protected int effectiveTimeoutMs() {
        if (sysConfigService == null) {
            return timeoutMs;
        }
        Integer l2 = sysConfigService.getInt(TIMEOUT_CONFIG_KEY, timeoutMs);
        return l2 == null || l2 <= 0 ? timeoutMs : l2;
    }

    /** sys_config 里的超时键名（与 schema-trip.sql 的初始行、后台参数表一致） */
    public static final String TIMEOUT_CONFIG_KEY = "llm.timeout-ms";

    // ==================== 子类差异 ====================

    /** 请求地址：{baseUrl}/chat/completions */
    protected String endpointUrl() {
        String base = config.getBaseUrl();
        if (!StringUtils.hasText(base)) {
            throw new LlmException(ResultCode.LLM_NOT_AVAILABLE, name(), "baseUrl 未配置");
        }
        return base.endsWith("/") ? base + "chat/completions" : base + "/chat/completions";
    }

    /** 认证头。两家都是 Bearer，仍留给子类覆盖以应对未来的差异 */
    protected Map<String, String> buildHeaders() {
        return Map.of("Authorization", "Bearer " + config.getApiKey());
    }

    /**
     * 往请求体里塞厂商特有字段。
     *
     * @param stream  是否流式请求
     * @param jsonMode 是否要求 JSON 输出（GLM 需要额外的 response_format）
     */
    protected void customizeRequestBody(ObjectNode body, boolean stream, boolean jsonMode) {
        // 默认什么都不加，两家厂商的公共部分由基类负责
    }

    // ==================== 能力判断 ====================

    @Override
    public LlmInfo info() {
        if (!config.isConfigured()) {
            return LlmInfo.unavailable(name(), config.getModel(), "未配置 API Key（检查 .env.properties 或环境变量）");
        }
        return LlmInfo.available(name(), config.getModel());
    }

    protected LlmProperties.LlmProviderConfig config() {
        return config;
    }

    // ==================== 非流式 ====================

    @Override
    public String chat(String systemPrompt, String userPrompt, LlmCallContext context) {
        return doChat(systemPrompt, userPrompt, context, false);
    }

    @Override
    public String chatJson(String systemPrompt, String userPrompt, String jsonSchemaHint, LlmCallContext context) {
        // 基类默认只用「prompt 强约束」来要 JSON；
        // 需要 response_format 的厂商（GLM）自行覆盖本方法，以便处理 400 重试
        return doChat(buildJsonSystemPrompt(systemPrompt, jsonSchemaHint), userPrompt, context, false);
    }

    /**
     * @param jsonMode 为 true 时会把 jsonMode 传给 {@link #customizeRequestBody}，
     *                 用于加 response_format 这类「要求 JSON」的厂商参数
     */
    protected String doChat(String systemPrompt, String userPrompt, LlmCallContext context, boolean jsonMode) {
        requireConfigured();

        String body = buildRequestBody(systemPrompt, userPrompt, false, jsonMode);
        String response;
        try {
            response = httpClient.postJson(endpointUrl(), buildHeaders(), body, effectiveTimeoutMs());
        } catch (ExternalHttpException e) {
            throw mapException(e);
        }

        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            String content = contentNode.isMissingNode() || contentNode.isNull() ? "" : contentNode.asText();

            // token 统计：非流式响应一定有 usage（流式才需要额外开关）
            if (context != null) {
                context.reportUsage(parseUsage(root.path("usage")));
            }
            if (!StringUtils.hasText(content)) {
                // 空内容通常意味着被内容策略拦了或模型异常，明确报出来而不是返回空串让上层猜
                throw new LlmException(ResultCode.LLM_PARSE_ERROR, name(),
                        "响应里 choices[0].message.content 为空");
            }
            return content;
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException(ResultCode.LLM_PARSE_ERROR, name(), e.getMessage(), e);
        }
    }

    // ==================== 流式 ====================

    @Override
    public void chatStream(String systemPrompt, String userPrompt,
                           Consumer<String> onDelta, Runnable onDone, Consumer<Throwable> onError) {
        chatStream(systemPrompt, userPrompt, LlmCallContext.empty(), onDelta, onDone, onError);
    }

    @Override
    public void chatStream(String systemPrompt, String userPrompt, LlmCallContext context,
                           Consumer<String> onDelta, Runnable onDone, Consumer<Throwable> onError) {
        String body;
        try {
            requireConfigured();
            body = buildRequestBody(systemPrompt, userPrompt, true, false);
        } catch (Throwable t) {
            onError.accept(t);
            return;
        }

        final boolean[] finished = {false};
        httpClient.postJsonStream(endpointUrl(), buildHeaders(), body, effectiveTimeoutMs(),
                line -> handleStreamLine(line, context, onDelta, finished),
                () -> {
                    if (!finished[0]) {
                        // 有些实现对端不发 [DONE] 直接断流，这种情况按正常结束处理，
                        // 否则调用方会永远等不到 onDone
                        finished[0] = true;
                        log.debug("[{}] 流结束但未收到 [DONE]，按正常结束处理", name());
                    }
                    onDone.run();
                },
                onError);
    }

    /** 解析一行 SSE：可能是 "data: {...}"、空行、或 "data: [DONE]" */
    private void handleStreamLine(String line, LlmCallContext context,
                                  Consumer<String> onDelta, boolean[] finished) {
        if (!StringUtils.hasText(line)) return;
        String payload = line.trim();
        if (!payload.startsWith("data:")) {
            return; // 注释行(: 开头)或其他非数据行，忽略
        }
        payload = payload.substring("data:".length()).trim();
        if ("[DONE]".equals(payload)) {
            finished[0] = true;
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode delta = root.path("choices").path(0).path("delta");

            // 只取 content：DeepSeek 的 reasoner 会在 delta 里多一个 reasoning_content，
            // 那是思维链，混进正文会让攻略文案变成一段自言自语。这里压根不读它。
            JsonNode contentNode = delta.path("content");
            if (!contentNode.isMissingNode() && !contentNode.isNull()) {
                String piece = contentNode.asText();
                // 容忍空串：GLM 个别版本最后一个 chunk 只带 usage 不带内容
                if (!piece.isEmpty()) {
                    onDelta.accept(piece);
                }
            }

            // 流式的 usage 通常在最后一个 chunk（DeepSeek 需 stream_options 才会给）
            if (context != null && root.has("usage")) {
                context.reportUsage(parseUsage(root.path("usage")));
            }
        } catch (Exception e) {
            // 单行解析失败不中断整个流：记录后跳过，避免因一个坏 chunk 丢掉已经生成的内容
            log.warn("[{}] 跳过无法解析的流式数据行: {}", name(),
                    payload.length() > 200 ? payload.substring(0, 200) + "..." : payload);
        }
    }

    // ==================== 公共工具 ====================

    protected String buildRequestBody(String systemPrompt, String userPrompt, boolean stream, boolean jsonMode) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.getModel());

        ArrayNode messages = body.putArray("messages");
        if (StringUtils.hasText(systemPrompt)) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt == null ? "" : userPrompt);

        if (config.getTemperature() != null) {
            body.put("temperature", config.getTemperature());
        }
        if (config.getMaxTokens() != null) {
            body.put("max_tokens", config.getMaxTokens());
        }
        body.put("stream", stream);

        customizeRequestBody(body, stream, jsonMode);
        return body.toString();
    }

    /**
     * 拼「要 JSON」的系统提示词。
     *
     * <p><b>必须出现 "json" 字样</b>：GLM 的 {@code response_format: {"type":"json_object"}}
     * 有这个硬性前提，否则参数不生效（手册明确点出的坑）。这里把要求写死在提示里，
     * 无论调用方传不传 hint 都能满足。
     */
    protected String buildJsonSystemPrompt(String systemPrompt, String jsonSchemaHint) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(systemPrompt)) {
            sb.append(systemPrompt).append("\n\n");
        }
        sb.append("严格只输出一个合法的 json 对象，不要输出任何解释文字，也不要用 markdown 代码块包裹。");
        if (StringUtils.hasText(jsonSchemaHint)) {
            sb.append("\n期望的 json 结构如下：\n").append(jsonSchemaHint);
        }
        return sb.toString();
    }

    protected LlmUsage parseUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return LlmUsage.empty();
        }
        Integer prompt = usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
        Integer completion = usage.hasNonNull("completion_tokens") ? usage.get("completion_tokens").asInt() : null;
        Integer total = usage.hasNonNull("total_tokens") ? usage.get("total_tokens").asInt() : null;
        if (total == null) {
            return LlmUsage.of(prompt, completion);
        }
        return new LlmUsage(prompt, completion, total);
    }

    /**
     * 把「HTTP 层失败」统一翻译成项目业务码。
     *
     * <p>这张映射表是连接器层最值钱的部分之一：上层从此不必知道
     * 「401 该提示管理员查 Key、429 该提示稍后重试」这种厂商细节。
     */
    protected LlmException mapException(ExternalHttpException e) {
        if (!e.hasResponse()) {
            return new LlmException(e.isTimeout() ? ResultCode.LLM_TIMEOUT : ResultCode.LLM_SERVER_ERROR,
                    name(), e.getMessage(), e);
        }
        ResultCode code = switch (e.getStatus()) {
            case 401, 403 -> ResultCode.LLM_AUTH_FAIL;
            case 429 -> ResultCode.LLM_RATE_LIMIT;
            case 400, 422 -> ResultCode.LLM_BAD_REQUEST;
            default -> e.getStatus() >= 500 ? ResultCode.LLM_SERVER_ERROR : ResultCode.LLM_BAD_REQUEST;
        };
        return new LlmException(code, name(), e.getMessage(), e);
    }

    protected void requireConfigured() {
        if (!config.isConfigured()) {
            throw new LlmException(ResultCode.LLM_NOT_AVAILABLE, name(),
                    "未配置 API Key 或 baseUrl");
        }
    }
}
