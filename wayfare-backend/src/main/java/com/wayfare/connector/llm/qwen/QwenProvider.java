package com.wayfare.connector.llm.qwen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wayfare.common.result.ResultCode;
import com.wayfare.connector.governance.ExternalHttpClient;
import com.wayfare.connector.llm.AbstractOpenAiCompatibleProvider;
import com.wayfare.connector.llm.LlmCallContext;
import com.wayfare.connector.llm.LlmException;
import com.wayfare.connector.llm.LlmProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云百炼（DashScope）Qwen Provider —— 走 OpenAI 兼容模式。
 *
 * <p><b>baseUrl</b>：{@code https://dashscope.aliyuncs.com/compatible-mode/v1}（来自配置，不硬编码）
 * <br><b>认证</b>：{@code Authorization: Bearer {apiKey}}
 * <br><b>模型</b>：{@code qwen3.8-flash}（当前默认，<b>推理模型</b>）
 *
 * <h3>三个必须记住的坑</h3>
 * <ol>
 *   <li><b>模型名大小写敏感，且必须用官方 id。</b>实测把 {@code qwen3.8-flash} 写成
 *       {@code qwen3.8-Flash}（大写 F）会直接返回
 *       {@code model_not_found: The model does not exist or you do not have access to it} ——
 *       报错文案看着像「没权限」，其实是名字不对。
 *       <b>这一点与 GLM 不同</b>（GLM 那边大小写不敏感），换模型时别凭手感敲。
 *       拿不准就去 {@code GET {baseUrl}/models} 拉全量 id 列表（本项目实测返回 256 个模型）。</li>
 *   <li><b>它是推理模型</b>，响应里有 {@code reasoning_content}，思维链在里面、
 *       {@code content} 只放最终答案。基类只读 {@code choices[0].message.content}
 *       与流式的 {@code delta.content}，所以思维链<b>结构性</b>被忽略，不会混进结果。
 *       但要注意思维链<b>计入 completion_tokens</b>（成本统计真实但偏高），
 *       且<b>占用 max-tokens 额度</b> —— 长输出阶段若思维链吃满额度，会得到
 *       {@code finish_reason=length} 且 content 为空。实测一次意图解析：冷启动 48 秒 /
 *       2251 输出 token（2194 是思维链），热起来 9~14 秒。</li>
 *   <li><b>百炼这个端点是聚合入口，不止阿里自家模型</b>：{@code GET /models} 里还能看到
 *       {@code glm-5.3} / {@code deepseek-v4-flash} / {@code kimi/kimi-k2.8-preview} 等。
 *       所以「换模型」在这里比「换厂商」更常用 —— 改 {@code llm.providers.qwen.model} 即可。</li>
 * </ol>
 *
 * <p><b>为什么值得单独接一家（而不只是在 GLM 内部换模型）</b>：
 * 系统的降级链是<b>厂商粒度</b>的（{@code llm.fallback-order}），同一厂商换个模型不算降级。
 * 接入 qwen 之前，链上实际只有 GLM 一家可用（DeepSeek 未配 Key），
 * 所以「GLM 过载」就等于「整个 AI 能力不可用」；接进来之后
 * {@code qwen → glm → deepseek} 才是一条真正能降级的链。
 */
@Component
public class QwenProvider extends AbstractOpenAiCompatibleProvider {

    /** 厂商键，与 application.yml 的 {@code llm.providers.qwen}、sys_config 的 active-provider 一致 */
    public static final String PROVIDER_NAME = "qwen";

    public QwenProvider(ObjectMapper objectMapper,
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
            log.warn("application.yml 里没有 llm.providers.qwen 配置段，Qwen 将被视为不可用");
        }
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    protected void customizeRequestBody(ObjectNode body, boolean stream, boolean jsonMode) {
        if (jsonMode) {
            // 百炼兼容模式支持 response_format；前提同 GLM —— prompt 里得出现 "json" 字样
            // （基类的 buildJsonSystemPrompt 已保证这一点）
            body.putObject("response_format").put("type", "json_object");
        }
    }

    /**
     * 要求返回 JSON，并在「不支持 response_format」时降级重试。
     *
     * <p>与 {@code GlmProvider} 同一套路，理由也一样：百炼上不同模型对
     * {@code response_format} 的支持度不一致（同一个端点上挂着上百个模型），
     * 一旦被 400 拒绝就退化成「纯 prompt 约束」再试一次，
     * 这样无论选到哪个模型，要 JSON 这个能力都不会整块失效。
     */
    @Override
    public String chatJson(String systemPrompt, String userPrompt, String jsonSchemaHint, LlmCallContext context) {
        String system = buildJsonSystemPrompt(systemPrompt, jsonSchemaHint);
        try {
            return doChat(system, userPrompt, context, true);
        } catch (LlmException e) {
            if (isResponseFormatUnsupported(e)) {
                log.warn("Qwen 模型不支持 response_format（{}），改为纯 prompt 约束重试一次", e.getMessage());
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
