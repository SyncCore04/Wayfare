package com.wayfare.connector.llm.glm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wayfare.common.result.ResultCode;
import com.wayfare.connector.governance.ExternalHttpClient;
import com.wayfare.connector.llm.AbstractOpenAiCompatibleProvider;
import com.wayfare.connector.llm.LlmCallContext;
import com.wayfare.connector.llm.LlmException;
import com.wayfare.connector.llm.LlmInfo;
import com.wayfare.connector.llm.LlmProperties;
import org.springframework.stereotype.Component;

/**
 * 智谱 GLM Provider。
 *
 * <p><b>baseUrl</b>：{@code https://open.bigmodel.cn/api/paas/v4}（来自配置，不硬编码）
 * <br><b>认证</b>：{@code Authorization: Bearer {apiKey}}
 * <br><b>模型</b>：glm-4.5-flash（免费，<b>推理模型</b>，当前默认） / glm-4.7-flash（免费，推理模型，但实测严重过载）
 * / glm-4-flash（免费，快，非推理） / glm-4-plus（效果好、收费） / glm-4-air
 *
 * <h3>四个必须记住的坑（都写在下面的实现里）</h3>
 * <ol>
 *   <li><b>{@code response_format} 要求 prompt 里出现 "json" 字样</b>，否则该参数不生效。
 *       基类的 {@code buildJsonSystemPrompt} 已保证这一点；这里的
 *       {@link #customizeRequestBody} 负责在需要时真的加上 response_format。</li>
 *   <li><b>遇到 400 且提示不支持 response_format 时要自动降级重试一次</b>
 *       （见 {@link #chatJson}）—— 不同版本/不同模型对这个参数的支持度不一致，
 *       直接失败会让「要 JSON」这个能力在部分模型上完全不可用。</li>
 *   <li><b>流式最后一个 chunk 可能只带 usage 不带内容</b>，content 会是 null 或空串。
 *       基类解析时已容忍（空串不回调 delta），这里不必重复处理。</li>
 *   <li><b>推理模型（glm-4.7-flash）的响应里有 {@code reasoning_content}</b>，
 *       思维链在里面、{@code content} 只放最终答案（与 DeepSeek 的 deepseek-reasoner 同一形态）。
 *       基类只读 {@code choices[0].message.content} 与流式的 {@code delta.content}，
 *       所以思维链<b>结构性</b>被忽略，不会混进解析结果 —— 不需要额外过滤代码。
 *       但要留意两点：思维链<b>计入 completion_tokens</b>（成本统计因此是真实的、偏高的），
 *       且<b>占用 max-tokens 额度</b> —— 长输出阶段若思维链吃满额度，
 *       会得到 {@code finish_reason=length} 且 content 为空，表现为「模型什么都没返回」。
 *       实测 glm-4.7-flash 做一次意图解析：918 输出 token 中 871 是思维链，耗时约 13 秒。</li>
 * </ol>
 */
@Component
public class GlmProvider extends AbstractOpenAiCompatibleProvider {

    /** 厂商键，与 application.yml 的 {@code llm.providers.glm}、sys_config 的 active-provider 一致 */
    public static final String PROVIDER_NAME = "glm";

    public GlmProvider(ObjectMapper objectMapper,
                       ExternalHttpClient httpClient,
                       LlmProperties llmProperties) {
        super(objectMapper, httpClient,
                // 配置缺失时给个空对象而不是让启动失败：这样 info() 会如实报告
                // 「未配置 Key」，比整个应用起不来友好得多
                llmProperties.getProvider(PROVIDER_NAME) != null
                        ? llmProperties.getProvider(PROVIDER_NAME)
                        : new LlmProperties.LlmProviderConfig(),
                llmProperties.getTimeoutMs() != null ? llmProperties.getTimeoutMs() : 90_000);
        if (llmProperties.getProvider(PROVIDER_NAME) == null) {
            log.warn("application.yml 里没有 llm.providers.glm 配置段，GLM 将被视为不可用");
        }
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    protected void customizeRequestBody(ObjectNode body, boolean stream, boolean jsonMode) {
        if (jsonMode) {
            // 前提：prompt 里必须含 "json" 字样（基类的 buildJsonSystemPrompt 已保证）
            body.putObject("response_format").put("type", "json_object");
        }
    }

    /**
     * 要求返回 JSON，并在「不支持 response_format」时降级重试。
     *
     * <p>降级策略：先带 response_format 调一次；若被 400 拒绝且原因与 response_format 相关，
     * 就去掉该参数、只靠 prompt 约束再试一次。这样无论模型/版本支持与否，都能拿到 JSON。
     */
    @Override
    public String chatJson(String systemPrompt, String userPrompt, String jsonSchemaHint, LlmCallContext context) {
        String system = buildJsonSystemPrompt(systemPrompt, jsonSchemaHint);
        try {
            return doChat(system, userPrompt, context, true);
        } catch (LlmException e) {
            if (isResponseFormatUnsupported(e)) {
                log.warn("GLM 不支持 response_format（{}），改为纯 prompt 约束重试一次", e.getMessage());
                return doChat(system, userPrompt, context, false);
            }
            throw e;
        }
    }

    /** 判断这个 400 是不是「不支持 response_format」引起的 */
    private boolean isResponseFormatUnsupported(LlmException e) {
        if (e.getResultCode() != ResultCode.LLM_BAD_REQUEST) return false;
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("response_format") || msg.contains("response format");
    }
}
