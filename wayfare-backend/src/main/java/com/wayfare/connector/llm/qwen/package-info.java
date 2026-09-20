package com.wayfare.connector.llm.qwen;

/**
 * 阿里云百炼（DashScope）Qwen 连接器。
 *
 * <p>本项目的大模型连接器是<b>可插拔</b>的：{@code LlmProviderFactory} 按 {@code provider.name()}
 * 自动登记所有 {@code @Component LlmProvider}，所以「多一家厂商」= 「多一个类 + 一段配置」，
 * 决策器、降级链、出站治理、成本统计全都不用改。
 * 这个包就是「第三条腿」，与 {@code glm} / {@code deepseek} 两个包平级。
 */
