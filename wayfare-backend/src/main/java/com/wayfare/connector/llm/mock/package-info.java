/**
 * Mock 大模型 Provider（P1-B 落地）。
 *
 * <p>作用：双厂商都不可用时的最后一级回落，让整条链路在无 API Key 的机器上
 * 也能被完整演示与测试。命中 Mock 时必须在返回的 meta 里标记 {@code provider=mock}，
 * 不允许静默伪装成真实厂商结果。
 */
package com.wayfare.connector.llm.mock;
