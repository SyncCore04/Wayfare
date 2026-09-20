package com.wayfare.connector.llm;

/**
 * 一次「用哪个 Provider」的决策结果。
 *
 * <p>把决策结果显式返回，而不是让调用方自己去猜 —— 因为「这次是不是降级来的」
 * 必须能被观测到：P3 要把 {@code isFallback} 与 {@code reason} 写进返回的 meta，
 * P6 的监控看板也要统计降级率。如果只返回一个 Provider 实例，这些信息就丢了。
 *
 * @param provider     实际要用的 Provider 实例
 * @param providerName 厂商名（glm / deepseek / mock），从 provider 上取一份方便直接输出
 * @param model        实际会使用的模型名
 * @param isFallback   是否走了降级（true = 不是用户配置的首选，而是备用或兜底）
 * @param reason       降级原因；未降级时为 null
 */
public record ResolvedLlm(LlmProvider provider, String providerName, String model,
                          boolean isFallback, String reason) {

    public static ResolvedLlm primary(LlmProvider provider) {
        return new ResolvedLlm(provider, provider.name(),
                provider.info().model(), false, null);
    }

    public static ResolvedLlm fallback(LlmProvider provider, String reason) {
        return new ResolvedLlm(provider, provider.name(),
                provider.info().model(), true, reason);
    }
}
