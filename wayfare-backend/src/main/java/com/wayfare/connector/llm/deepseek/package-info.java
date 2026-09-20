/**
 * DeepSeek Provider（P1-B 落地）。
 *
 * <p>厂商专属细节：
 * <ul>
 *   <li>baseUrl 为 {@code https://api.deepseek.com/v1}，模型 {@code deepseek-chat}，
 *       上下文长、中文稳，适合行程编排这种长输出场景</li>
 *   <li>若改用 {@code deepseek-reasoner}，响应里会多出 {@code reasoning_content}，
 *       <b>必须忽略该字段</b>，否则思维链会混进最终攻略文案</li>
 *   <li>流式要拿到 token 统计，需在请求体加 {@code stream_options:{"include_usage":true}}</li>
 * </ul>
 */
package com.wayfare.connector.llm.deepseek;
