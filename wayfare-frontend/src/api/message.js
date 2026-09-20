import request from './request'

// 会话列表
export function getConversationList() {
  return request({ url: '/messages/conversations', method: 'get' })
}

// 与某用户的消息列表
// 注意路径以 /conversation/ 为前缀：后端是 GET /messages/conversation/{otherUserId}
// （原写法 /messages/{otherUserId} 与后端不匹配，会 404）
export function getMessageList(otherUserId, params) {
  return request({ url: `/messages/conversation/${otherUserId}`, method: 'get', params })
}

// 发送消息
// 后端是 POST /messages/send（原写法 POST /messages 不匹配）
export function sendMessage(data) {
  return request({ url: '/messages/send', method: 'post', data })
}

// 未读消息总数
export function getUnreadCount() {
  return request({ url: '/messages/unread/count', method: 'get' })
}

// 标记已读
// 后端是 PUT /messages/read/{otherUserId}（原写法 /messages/{id}/read 不匹配）
export function markAsRead(otherUserId) {
  return request({ url: `/messages/read/${otherUserId}`, method: 'put' })
}
