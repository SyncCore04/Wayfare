package com.wayfare.connector.llm;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型启动期配置（L1）。
 * 对应 application.yml 的 {@code llm:} 段。
 *
 * <p><b>与 L2 的关系</b>：本类是 L1，只放「启动就必需」的东西 —— 连接地址、模型名、API Key。
 * 而 {@code llm.enabled} / {@code llm.active-provider} / {@code llm.fallback-order} 这几个开关
 * <b>同时也存在于 sys_config 表（L2）</b>，运行时以 L2 为准。
 * 也就是说：本类里的这几个字段实际是「L2 里没配时的兜底值」。
 * 真正做判断时要先问 {@code SysConfigService}，读不到再回落到本类 ——
 * 具体见 {@code connector.llm} 包内 P1-B 的实现。
 *
 * <p>为什么不把 provider 名单写死成 glm/deepseek 两个字段：
 * 用 {@code Map<String, LlmProviderConfig>} 才能让「加第三家厂商」只改配置不改代码，
 * 而且 P1-D 的熔断器要按 provider 名做隔离，天然需要一个 Map。
 */
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private static final Logger log = LoggerFactory.getLogger(LlmProperties.class);

    /** 是否启用大模型能力（L1 兜底值，运行时以 sys_config 的 llm.enabled 为准） */
    private boolean enabled = true;

    /** 当前厂商：glm | deepseek | mock（L1 兜底值） */
    private String activeProvider = "glm";

    /** 降级顺序，逗号分隔（L1 兜底值） */
    private String fallbackOrder = "glm,deepseek";

    /** 单次调用超时（毫秒）。行程编排是长输出，默认给足 90 秒 */
    private Integer timeoutMs = 90000;

    /** 厂商配置，key 为厂商名（glm / deepseek） */
    private Map<String, LlmProviderConfig> providers = new LinkedHashMap<>();

    /**
     * 单个厂商的配置项。
     *
     * <p>{@code apiKey} 一律来自环境变量占位符（{@code ${GLM_API_KEY:}}），
     * <b>禁止在 application.yml 里写真实 Key</b>；默认值为空串时代表「未配置」，
     * P1-B 会据此判断该厂商不可用并往下游降级。
     */
    public static class LlmProviderConfig {

        private String baseUrl;
        private String apiKey;
        private String model;
        private Double temperature = 0.3;
        private Integer maxTokens = 4096;

        /** 是否已配置可用（没 Key 就等于没这个厂商）。仅判断，不做任何网络调用 */
        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank() && baseUrl != null && !baseUrl.isBlank();
        }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    }

    /** 取某厂商配置，不存在返回 null */
    public LlmProviderConfig getProvider(String providerName) {
        return providerName == null ? null : providers.get(providerName);
    }

    /**
     * 启动时把厂商配置情况打到日志里。
     *
     * <p>为什么值得专门写一段：这个项目最容易踩、又最难查的故障就是「Key 没配上」——
     * 表现出来是接口报错或静默降级到 Mock，而日志里什么都没有。
     * 启动时明确说清「哪家已配置、哪家会被跳过」，能让排查从半小时变成一眼。
     *
     * <p><b>只打前 4 位和长度，绝不打整段 Key。</b>日志会被收集、转发、贴进工单，
     * 打明文等于把可用凭证散播出去。
     */
    @PostConstruct
    public void logProviderStatus() {
        if (providers == null || providers.isEmpty()) {
            log.warn("llm.providers 未配置任何厂商，大模型能力将全部回落到 Mock");
            return;
        }
        providers.forEach((name, config) -> {
            if (config == null) return;
            if (config.isConfigured()) {
                log.info("LLM 厂商[{}] 已配置: model={}, baseUrl={}, apiKey={}（长度 {}）",
                        name, config.getModel(), config.getBaseUrl(),
                        maskKey(config.getApiKey()), config.getApiKey().length());
            } else {
                log.warn("LLM 厂商[{}] 未配置 API Key，降级链里会被跳过（检查 .env.properties 或环境变量）", name);
            }
        });
        log.info("LLM 开关: enabled={}, 默认厂商={}, 降级顺序={}（运行时以 sys_config 为准）",
                enabled, activeProvider, fallbackOrderList());
    }

    /** 掩码：只暴露前 4 位，足以辨认「填的是不是那一把 Key」 */
    private String maskKey(String key) {
        if (key == null || key.isEmpty()) return "(空)";
        if (key.length() <= 4) return "****";
        return key.substring(0, 4) + "****";
    }

    /**
     * 把 {@code fallback-order} 解析成列表。
     * 放在这里是为了让「逗号分隔 + 去过空白项」只实现一次，避免各调用方各写一遍 split。
     */
    public List<String> fallbackOrderList() {
        List<String> order = new ArrayList<>();
        if (fallbackOrder == null) return order;
        for (String name : fallbackOrder.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty() && !order.contains(trimmed)) {
                order.add(trimmed);
            }
        }
        return order;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getActiveProvider() { return activeProvider; }
    public void setActiveProvider(String activeProvider) { this.activeProvider = activeProvider; }
    public String getFallbackOrder() { return fallbackOrder; }
    public void setFallbackOrder(String fallbackOrder) { this.fallbackOrder = fallbackOrder; }
    public Integer getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Integer timeoutMs) { this.timeoutMs = timeoutMs; }
    public Map<String, LlmProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, LlmProviderConfig> providers) { this.providers = providers; }
}
