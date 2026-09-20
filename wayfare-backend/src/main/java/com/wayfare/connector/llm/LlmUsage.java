package com.wayfare.connector.llm;

/**
 * 一次大模型调用的 token 用量。
 *
 * <p>P1-B 只负责「从厂商响应里把 usage 抠出来并通过回调交出去」；
 * <b>真正落库是 P4-C 的事</b>（成本与中断的精细化记录）。这里定义出来，
 * 是为了让 P1-B 的接口形状一次成型，P4 不用回头改签名。
 *
 * @param promptTokens     输入 token
 * @param completionTokens 输出 token
 * @param totalTokens      合计；厂商没给时由前两者相加
 */
public record LlmUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {

    /** 三值齐全才算有效统计（有些厂商在流式的最后一个 chunk 只给 usage） */
    public boolean isValid() {
        return promptTokens != null || completionTokens != null || totalTokens != null;
    }

    public static LlmUsage of(Integer prompt, Integer completion) {
        Integer total = (prompt == null && completion == null)
                ? null
                : (prompt == null ? 0 : prompt) + (completion == null ? 0 : completion);
        return new LlmUsage(prompt, completion, total);
    }

    /** 无统计时用它，避免调用方到处判空 */
    public static LlmUsage empty() {
        return new LlmUsage(null, null, null);
    }
}
