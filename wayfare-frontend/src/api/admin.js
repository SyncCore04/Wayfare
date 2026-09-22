import request from './request'

/**
 * 后台管理接口封装（P6-A 起）。
 *
 * 分两类，别混：
 *  · /diagnostics/*  —— **只读诊断**（+ ping）。ping 会真实消耗配额与 token，只在管理员点按钮时调。
 *  · /admin/*        —— **写配置**。全部走 SysConfigService.set，写完立即生效（清 Redis 缓存），**不重启**。
 *
 * ⚠️ 所有配置值前端拿到时敏感项（Key / AK）已被服务端掩码。**绝不要把掩码串当新值提交回去** ——
 * 那会把 "sk-a****" 写进配置，等于把 Key 弄坏。留空表示不修改。
 */

// ---------- 诊断（只读） ----------

// 两个连接器的一次性总览：开关、当前决策、熔断状态、调用统计、今日 token/成本
export function getConnectors() {
  return request({ url: '/diagnostics/connectors', method: 'get' })
}

// 每个厂商的配置与状态（模型名 / Base URL / 掩码 Key / 是否可用 / 熔断）
export function getLlmProviders() {
  return request({ url: '/diagnostics/llm/providers', method: 'get' })
}

// 大模型连通性测试。传 provider 时「指定厂商不降级」—— 否则测 DeepSeek 可能被 GLM 回答
export function pingLlm(provider) {
  return request({ url: '/diagnostics/llm/ping', method: 'post', data: provider ? { provider } : {} })
}

// 地图连通性测试：真实发起一次 POI 检索（走三级降级），返回耗时与命中数
export function pingMap(city, keyword) {
  return request({ url: '/diagnostics/map/ping', method: 'post', data: { city, keyword } })
}

// ---------- 配置（读写） ----------

// 全部配置，按 group 分组（llm / map / trip），敏感值已掩码
export function getConfigs() {
  return request({ url: '/admin/configs', method: 'get' })
}

// 读单个键
export function getConfig(key) {
  return request({ url: `/admin/configs/${key}`, method: 'get' })
}

/**
 * 写单个键，立即生效。
 *
 * ⚠️ 传空串是**有语义**的：把该键清成空（如清空地图 AK）。
 * 但注意 SysConfigService 读取时对空串会回落到 L1 —— 想「彻底清空」L2 需要知道这个取舍。
 */
export function setConfig(key, value) {
  return request({ url: `/admin/configs/${key}`, method: 'put', data: { value } })
}

/**
 * 一键改大模型开关（当前厂商 / 降级顺序）。
 *
 * ⚠️ 已知坑：**空的 fallbackOrder 会被后端忽略**（配置不变）。想真正改降级顺序请传非空值；
 * 想清空得单独走 setConfig('llm.fallback-order', '')。model 不支持在线改，会被放进 ignored。
 */
export function setLlmConfig({ activeProvider, fallbackOrder } = {}) {
  return request({ url: '/admin/config/llm', method: 'post', data: { activeProvider, fallbackOrder } })
}

// 一键开关地图能力。这是答辩要演示的那个按钮 —— 关闭是**能力降级**，系统照常可用
export function setMapEnabled(enabled) {
  return request({ url: '/admin/config/map/enabled', method: 'post', data: { enabled } })
}

// ---------- 生成统计（P4-C 交付，P6 接入） ----------

// 生成统计总览（成功率 / P95 / token / 成本 / 阶段拆解 / 地图模式拆解 / Top 错误）
export function getGenerationStats(from, to) {
  return request({ url: '/admin/generation/stats', method: 'get', params: { from, to } })
}

// 单次生成的逐阶段 token 与成本拆解
export function getTripBreakdown(tripId) {
  return request({ url: `/admin/generation/trips/${tripId}/breakdown`, method: 'get' })
}

// 每日趋势（P6-B 折线图）：窗口含今天，往前数 days 天；每一天都会有一行（含没有数据的 0）
export function getGenerationTrend(days = 7) {
  return request({ url: '/admin/generation/trend', method: 'get', params: { days } })
}

/**
 * 生成明细分页（P6-B 表格）。
 * 筛选条件为空的键不要传 —— 传空串后端会当「没填」，但少传更清楚。
 */
export function getGenerationLogs(params) {
  return request({ url: '/admin/generation/logs', method: 'get', params })
}

// 外部调用日志分页（P6-B 第二个页签）。connector 传 null/不传 = 全部
export function getExternalCalls({ connector, page = 1, size = 20 } = {}) {
  return request({ url: '/admin/generation/external-calls', method: 'get', params: { connector, page, size } })
}
