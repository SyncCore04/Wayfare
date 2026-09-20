package com.wayfare.connector.llm;

/**
 * 某个厂商的当前状态快照（用于诊断接口与日志，<b>不做任何网络调用</b>）。
 *
 * @param provider  厂商名 glm / deepseek / mock
 * @param model     实际会使用的模型名
 * @param available 现在能不能用（是否配了 Key、是否被熔断）
 * @param reason    不可用时的原因；可用时为 null
 */
public record LlmInfo(String provider, String model, boolean available, String reason) {

    public static LlmInfo available(String provider, String model) {
        return new LlmInfo(provider, model, true, null);
    }

    public static LlmInfo unavailable(String provider, String model, String reason) {
        return new LlmInfo(provider, model, false, reason);
    }
}
