/**
 * SSE 流解析器（P5-B）
 *
 * 【为什么不用 EventSource】
 * 浏览器原生的 EventSource 只支持 GET，而我们的生成接口是 POST（要带请求体、
 * 要带 Authorization 头）。用 EventSource 就得把 rawInput 塞进 query string、
 * 把 token 塞进 URL —— 前者会被长度限制截断，后者会把凭证留在日志里。
 * 所以走 fetch + ReadableStream，自己解析 SSE 文本。
 *
 * 【为什么解析逻辑要单独成文件】
 * 它是这一整块里唯一「纯逻辑」的部分 —— 输入字符串、输出事件数组，
 * 不碰 DOM 也不碰网络。抽出来就能直接单测，而不用起浏览器。
 *
 * 解析规则按 SSE 规范（https://html.spec.whatwg.org/multipage/server-sent-events.html）：
 *   - 事件之间用空行分隔
 *   - 一行里 `字段:值`，冒号后**可选**一个空格（Spring 的 SseEmitter 就不加空格）
 *   - 以 `:` 开头的行是注释（心跳），整行忽略
 *   - 多行 `data:` 用换行拼接
 */

/** 事件之间的分隔符：优先匹配 \r\n\r\n，再匹配 \n\n */
const SEPARATORS = ['\r\n\r\n', '\n\n']

/**
 * 解析一段 SSE 文本。
 *
 * 流式场景下 chunk 的边界是任意的 —— 一个事件可能被切成两半，
 * 所以这里返回 `rest`（还没凑成完整事件的那一截），由调用方拼到下一个 chunk 前面。
 *
 * @param {string} raw 累积的原始文本
 * @returns {{events: Array<{event: string, data: string}>, rest: string}}
 */
export function parseSse(raw) {
  const events = []
  let rest = raw == null ? '' : String(raw)

  for (;;) {
    // 找最早出现的分隔符
    let at = -1
    let sepLen = 0
    for (const sep of SEPARATORS) {
      const i = rest.indexOf(sep)
      if (i !== -1 && (at === -1 || i < at)) {
        at = i
        sepLen = sep.length
      }
    }
    if (at === -1) break

    const block = rest.slice(0, at)
    rest = rest.slice(at + sepLen)
    const evt = parseBlock(block)
    if (evt) events.push(evt)
  }
  return { events, rest }
}

/**
 * 解析单个事件块（不含结尾空行）。
 * 没有任何 data 行时返回 null —— 纯注释块（心跳）不该被当成事件抛给业务层。
 */
export function parseBlock(block) {
  const lines = String(block).split(/\r?\n/)
  let event = 'message'
  const dataLines = []

  for (const line of lines) {
    if (!line) continue
    if (line.startsWith(':')) continue // 注释 / 心跳

    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    // 规范允许冒号后跟一个空格，要去掉；Spring 不加空格，所以这里是「有则去」
    if (value.startsWith(' ')) value = value.slice(1)

    if (field === 'event') event = value
    else if (field === 'data') dataLines.push(value)
  }

  if (!dataLines.length) return null
  return { event, data: dataLines.join('\n') }
}

/**
 * 消费一个 fetch 的流式响应，逐个回调事件。
 *
 * @param {Response} response fetch 的响应（必须带 body）
 * @param {(evt: {event: string, data: string}) => void} onEvent 每个完整事件的回调
 * @returns {Promise<void>}
 */
export async function consumeSseStream(response, onEvent) {
  if (!response || !response.body) {
    throw new Error('响应没有可读流，无法解析 SSE')
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      // stream: true —— 一个多字节汉字可能被切在两个 chunk 之间
      buffer += decoder.decode(value, { stream: true })
      const { events, rest } = parseSse(buffer)
      buffer = rest
      for (const evt of events) onEvent(evt)
    }
    // 收尾：有些实现最后一个事件不带结尾空行，这里补上再解析一次
    buffer += decoder.decode()
    if (buffer.trim()) {
      const { events } = parseSse(buffer + '\n\n')
      for (const evt of events) onEvent(evt)
    }
  } finally {
    try {
      reader.releaseLock()
    } catch (e) {
      // 流已关闭时 releaseLock 可能抛错，忽略
    }
  }
}

/**
 * 把 SSE 事件的 data 解析成对象。
 *
 * 后端所有事件的 data 都是 JSON（见 TripStreamService 的 send()）。
 * 解析失败时返回 null 而不是抛异常 —— 一个坏掉的事件不该让整条流崩掉。
 */
export function parseEventData(evt) {
  if (!evt || !evt.data) return null
  try {
    return JSON.parse(evt.data)
  } catch (e) {
    return null
  }
}
