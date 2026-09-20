package com.wayfare.connector.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provider 工厂：按名字取 Provider 实例。
 *
 * <p>注入 {@code List<LlmProvider>} 后<b>用 {@code provider.name()} 建索引</b>，
 * 而不是依赖 Spring 的 Bean 名 ——  Bean 名默认是类名（{@code glmProvider}），
 * 与配置里写的 {@code glm} 对不上，是个很容易踩的坑。
 * 用 {@code name()} 建索引，新增厂商只要加一个 {@code @Component} 就自动被登记。
 */
@Component
public class LlmProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderFactory.class);

    private final Map<String, LlmProvider> providers;

    public LlmProviderFactory(List<LlmProvider> providerList) {
        Map<String, LlmProvider> map = new LinkedHashMap<>();
        for (LlmProvider provider : providerList) {
            LlmProvider previous = map.put(provider.name(), provider);
            if (previous != null) {
                log.warn("检测到重复的 Provider 名 '{}'，后者 {} 覆盖了前者 {}",
                        provider.name(), provider.getClass().getSimpleName(),
                        previous.getClass().getSimpleName());
            }
        }
        this.providers = Map.copyOf(map);
        log.info("已注册的大模型 Provider: {}", this.providers.keySet());
    }

    /** 取 Provider；名字不存在返回 null（不抛异常，交给决策器去回落） */
    public LlmProvider get(String providerName) {
        return providerName == null ? null : providers.get(providerName);
    }

    public boolean contains(String providerName) {
        return providerName != null && providers.containsKey(providerName);
    }

    /** 已注册的 Provider 名集合，用于诊断输出 */
    public Set<String> names() {
        return providers.keySet();
    }

    /** 兜底 Provider（永远应该存在） */
    public LlmProvider mock() {
        return providers.get(com.wayfare.connector.llm.mock.MockLlmProvider.PROVIDER_NAME);
    }
}
