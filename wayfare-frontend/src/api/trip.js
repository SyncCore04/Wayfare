import request from './request'
import { consumeSseStream } from '@/utils/sseParser'

/**
 * 行程规划相关接口（P5-B）
 *
 * 【两类请求走两条路，这是有意的】
 * 普通 CRUD（详情/列表/重排）走 axios 封装 —— 自动带 token、统一错误提示。
 * 流式接口走原生 fetch —— axios 会把整个响应缓冲下来再交给调用方，
 * 那样就完全失去流式的意义（用户还是得等到全部生成完才看到东西）。
 */

// 只解析意图（P5-B 的「确认参数」步骤）。只跑 Step1，不检索、不调地图、不落库
export function parseIntent(data) {
  return request({ url: '/trip/parse', method: 'post', data })
}

// 同步规划：一次跑完整条管线。实测 194~507 秒，所以超时单独放宽到 10 分钟
export function planSync(data) {
  return request({ url: '/trip/plan/sync', method: 'post', data, timeout: 600000 })
}

// 我的行程分页（列表页不返回 days / items）
export function myTrips(params) {
  return request({ url: '/trip/my', method: 'get', params })
}

// 行程详情（含 days + items，按 dayIndex、seq 正序）
export function getTrip(id) {
  return request({ url: `/trip/${id}`, method: 'get' })
}

// 局部重排：feedback 里带「第N天」会被后端解析成 dayIndex 做针对性重排
export function replan(id, feedback) {
  return request({
    url: `/trip/${id}/replan`,
    method: 'post',
    data: { feedback },
    timeout: 600000
  })
}

// 逻辑删除行程
export function deleteTrip(id) {
  return request({ url: `/trip/${id}`, method: 'delete' })
}

/**
 * 把行程与已发布的攻略关联（「发布为攻略」的最后一步）。
 *
 * 流程是两步：先用既有的 `POST /works` 创建攻略（图片/分类/标签走 P0 的完整校验），
 * 拿到 workId 后再调这里建立关联 —— 后端不凭空造攻略。
 * 关联后卡片会出现「AI 生成」角标、详情页会出现「完整行程」区块。
 */
export function publishTrip(id, workId) {
  return request({ url: `/trip/${id}/publish`, method: 'put', data: { workId } })
}

/**
 * 重排某一天的条目顺序（P5-B 的「上移 / 下移」编辑）。
 *
 * @param {number|string} id 行程ID
 * @param {number} dayIndex 第几天（从 1 起）
 * @param {Array<number>} itemIds 该天条目 ID，**按目标顺序**排列
 */
export function updateItemOrder(id, dayIndex, itemIds) {
  return request({
    url: `/trip/${id}/days/${dayIndex}/order`,
    method: 'put',
    data: { itemIds }
  })
}

/**
 * 发一个 POST 并消费 SSE 流。
 *
 * @param {string} path 应用内路径（不带 /api，这里统一加）
 * @param {object} body 请求体
 * @param {{onEvent?: Function, signal?: AbortSignal}} opts
 *        onEvent 每个完整事件回调一次；signal 用于「取消」按钮中断请求
 */
async function postSse(path, body, opts = {}) {
  const { onEvent, signal } = opts
  const token = localStorage.getItem('token')

  const resp = await fetch(`/api${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(body || {}),
    signal
  })

  if (!resp.ok) {
    // 401 要触发与 axios 拦截器一致的「清 token 跳登录」行为，
    // 但这里不直接操作路由（工具函数不该依赖 router），抛出让页面处理
    const err = new Error(`请求失败（HTTP ${resp.status}）`)
    err.status = resp.status
    throw err
  }

  await consumeSseStream(resp, evt => {
    if (onEvent) onEvent(evt)
  })
}

/**
 * 流式规划（SSE）。
 *
 * 事件序列：stage（六阶段进度）→ itinerary（行程骨架，到了就该渲染）→ delta（文案增量）→ done / error
 */
export function planStream(data, opts) {
  return postSse('/trip/plan/stream', data, opts)
}

/**
 * 只重生成攻略文案（不重跑管线）。
 *
 * @param {number|string} id 行程ID
 * @param {string} [provider] 指定厂商（qwen / glm / deepseek）做双模型效果对比；
 *                            不传则走常规降级链
 */
export function regenerateCopy(id, provider, opts) {
  const qs = provider ? `?provider=${encodeURIComponent(provider)}` : ''
  return postSse(`/trip/${id}/regenerate-copy${qs}`, {}, opts)
}
