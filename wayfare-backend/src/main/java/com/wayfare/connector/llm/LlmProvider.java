package com.wayfare.connector.llm;

import java.util.function.Consumer;

/**
 * 大模型厂商的统一接口。
 *
 * <p>先定接口再写实现，是为了让上层（P3 的编排管线）<b>完全不知道底下是哪一家</b> ——
 * 换厂商、降级到 Mock，对调用方都是无感的。
 *
 * <p>三个能力：
 * <ul>
 *   <li>{@link #chat} 普通对话</li>
 *   <li>{@link #chatJson} 要求返回 JSON（GLM 需要额外的 response_format 参数，见实现注释）</li>
 *   <li>{@link #chatStream} 流式输出，逐段回调</li>
 * </ul>
 *
 * <p>{@link #info()} 与 {@link #name()} 都不做网络调用 ——
 * 诊断接口和降级决策需要能随时、廉价地问「这家现在能不能用」。
 */
public interface LlmProvider {

    /**
     * 取厂商名，用作 Bean 名与降级顺序里的键：{@code glm} / {@code deepseek} / {@code mock}。
     */
    String name();

    /**
     * 当前状态快照，不发起任何网络调用。
     */
    LlmInfo info();

    /** 普通对话，返回完整文本 */
    default String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, LlmCallContext.empty());
    }

    /** 普通对话，并可通过 context 接出 token 用量 */
    String chat(String systemPrompt, String userPrompt, LlmCallContext context);

    /** 要求模型返回 JSON 文本 */
    default String chatJson(String systemPrompt, String userPrompt, String jsonSchemaHint) {
        return chatJson(systemPrompt, userPrompt, jsonSchemaHint, LlmCallContext.empty());
    }

    /**
     * 要求模型返回 JSON 文本。
     *
     * @param jsonSchemaHint 对期望结构的文字说明；实现方需要保证其中含 "json" 字样
     *                       （GLM 的 response_format 有这个硬性前提，见 GlmProvider 注释）
     */
    String chatJson(String systemPrompt, String userPrompt, String jsonSchemaHint, LlmCallContext context);

    /**
     * 流式对话。
     *
     * @param onDelta 每收到一段增量文本回调一次（实现方需保证不回调空串）
     * @param onDone  正常结束
     * @param onError 出错（实现方应抛 {@link LlmException} 或把它传给这里）
     */
    void chatStream(String systemPrompt, String userPrompt,
                    Consumer<String> onDelta, Runnable onDone, Consumer<Throwable> onError);

    /**
     * 流式 + 用量回调。默认实现忽略用量（不是所有厂商都支持流式统计），
     * 支持的厂商（DeepSeek 需 stream_options）覆盖它。
     */
    default void chatStream(String systemPrompt, String userPrompt, LlmCallContext context,
                            Consumer<String> onDelta, Runnable onDone, Consumer<Throwable> onError) {
        chatStream(systemPrompt, userPrompt, onDelta, onDone, onError);
    }
}
