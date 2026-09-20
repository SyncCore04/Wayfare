/**
 * GLM（智谱）Provider（P1-B 落地）。
 *
 * <p>厂商专属细节：
 * <ul>
 *   <li>baseUrl 为 {@code https://open.bigmodel.cn/api/paas/v4}</li>
 *   <li>使用 {@code response_format: {"type":"json_object"}} 时，
 *       prompt 里<b>必须出现 "json" 字样</b>，否则该参数不生效</li>
 *   <li>若返回 400 提示不支持 response_format，自动降级为「纯 prompt 约束」重试一次</li>
 * </ul>
 */
package com.wayfare.connector.llm.glm;
