import request from './request'

// 作品评论列表
export function getCommentList(workId, params) {
  return request({ url: `/comments/work/${workId}`, method: 'get', params })
}

// 发表评论
export function createComment(data) {
  return request({ url: '/comments', method: 'post', data })
}

// 删除评论
export function deleteComment(id) {
  return request({ url: `/comments/${id}`, method: 'delete' })
}

// 回复评论
export function replyComment(data) {
  return request({ url: '/comments/reply', method: 'post', data })
}
