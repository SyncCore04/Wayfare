/**
 * 大模型连接器抽象（P1-B 落地）。
 *
 * <p>只支持两家厂商：GLM（智谱）与 DeepSeek，二者都走 OpenAI 兼容协议，
 * 因此公共逻辑（请求体拼装、重试、用量统计）放在
 * {@code AbstractOpenAiCompatibleProvider}，子类只写差异部分。
 *
 * <p>禁止在任何地方硬编码 baseUrl 或模型名，一律从 {@code llm} 配置段读取。
 */
package com.wayfare.connector.llm;
