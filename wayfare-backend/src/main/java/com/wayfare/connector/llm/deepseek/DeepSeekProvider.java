package com.wayfare.connector.llm.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wayfare.connector.governance.ExternalHttpClient;
import com.wayfare.connector.llm.AbstractOpenAiCompatibleProvider;
import com.wayfare.connector.llm.LlmProperties;
import org.springframework.stereotype.Component;

/**
 * DeepSeek Provider。
 *
 * <p><b>baseUrl</b>：{@code https://api.deepseek.com/v1}（来自配置，不硬编码）
 * <br><b>认证</b>：{@code Authorization: Bearer {apiKey}}
 * <br><b>模型</b>：deepseek-chat（默认，中文稳、上下文长） / deepseek-reasoner（带思维链）
 *
 * <h3>三个必须记住的坑（都写在下面的实现里）</h3>
 * <ol>
 *   <li><b>{@code deepseek-reasoner} 会在 delta 里额外返回 {@code reasoning_content}</b>。
 *       那是思维链，<b>必须忽略</b>，否则「先自我推理一大段」会混进最终攻略文案。
 *       基类的流式解析只读 {@code delta.content}、压根不碰 reasoning_content，
 *       所以这个坑在基类层面就规避掉了 —— 这里不必也不应再补逻辑。</li>
 *   <li><b>流式默认不给 usage</b>，必须在请求体里加
 *       {@code stream_options: {"include_usage": true}} 才会在最后一个 chunk 返回 token 统计。
 *       不加的话 P4 的成本记录就永远是 0。</li>
 *   <li><b>长输出（行程编排）要把 max_tokens 提到 4096 以上</b>，
 *       否则一份多日行程会在中途被截断；该值在 application.yml 里配置。</li>
 * </ol>
 */
@Component
public class DeepSeekProvider extends AbstractOpenAiCompatibleProvider {

    public static final String PROVIDER_NAME = "deepseek";

    public DeepSeekProvider(ObjectMapper objectMapper,
                            ExternalHttpClient httpClient,
                            LlmProperties llmProperties) {
        super(objectMapper, httpClient,
                llmProperties.getProvider(PROVIDER_NAME) != null
                        ? llmProperties.getProvider(PROVIDER_NAME)
                        : new LlmProperties.LlmProviderConfig(),
                llmProperties.getTimeoutMs() != null ? llmProperties.getTimeoutMs() : 90_000);
        if (llmProperties.getProvider(PROVIDER_NAME) == null) {
            log.warn("application.yml 里没有 llm.providers.deepseek 配置段，DeepSeek 将被视为不可用");
        }
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    protected void customizeRequestBody(ObjectNode body, boolean stream, boolean jsonMode) {
        if (stream) {
            // 不加这个，流式响应里就没有 usage，token 统计与成本记录全为 0
            body.putObject("stream_options").put("include_usage", true);
        }
        // 注意：这里与 GLM 不同，刻意不加 response_format ——
        // DeepSeek 的 JSON 输出靠 prompt 约束就够稳，少一个参数少一个失败点。
    }
}
