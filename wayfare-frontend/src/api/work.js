import request from './request'

// 作品列表（分页）
export function getWorkList(params) {
  return request({ url: '/works/page', method: 'get', params })
}

// 作品详情
export function getWorkDetail(id) {
  return request({ url: `/works/${id}`, method: 'get' })
}

// 发布作品
export function createWork(data) {
  return request({ url: '/works', method: 'post', data })
}

// 更新作品
export function updateWork(id, data) {
  return request({ url: `/works/${id}`, method: 'put', data })
}

// 删除作品
export function deleteWork(id) {
  return request({ url: `/works/${id}`, method: 'delete' })
}

// 获取用户作品列表
export function getUserWorks(userId, params) {
  return request({ url: '/works/page', method: 'get', params: { ...params, userId } })
}

// 管理员：审核作品（更新状态）
export function auditWork(id, status) {
  return request({ url: `/works/${id}/status`, method: 'put', params: { status } })
}

// 管理员：作品列表
export function getAdminWorkList(params) {
  return request({ url: '/works/page', method: 'get', params })
}
