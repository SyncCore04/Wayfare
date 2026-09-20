package com.wayfare.connector.llm;

import java.util.function.Consumer;

/**
 * 一次大模型调用的上下文：目前只承载「用量回调」，将来要加 traceId / 超时覆盖
 * 也往这里加，避免再去改 {@link LlmProvider} 的方法签名。
 *
 * <p>为什么不用 ThreadLocal 存用量：一次请求可能并发调多家（降级），
 * ThreadLocal 下区分不开是哪一次调用上报的。
 */
public final class LlmCallContext {

    private final Consumer<LlmUsage> onUsage;

    private LlmCallContext(Consumer<LlmUsage> onUsage) {
        this.onUsage = onUsage;
    }

    public static LlmCallContext of(Consumer<LlmUsage> onUsage) {
        return new LlmCallContext(onUsage);
    }

    /** 不需要统计时用它，允许到处透传而不必判空 */
    public static LlmCallContext empty() {
        return new LlmCallContext(null);
    }

    /** 有回调且有有效数据才触发，省得每个 Provider 自己判 */
    public void reportUsage(LlmUsage usage) {
        if (onUsage != null && usage != null && usage.isValid()) {
            onUsage.accept(usage);
        }
    }
}
