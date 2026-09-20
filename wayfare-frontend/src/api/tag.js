import request from './request'

// 标签列表（分页）
export function getTagList(params) {
  return request({ url: '/tags/page', method: 'get', params })
}

// 所有标签（热门标签，不分页）
export function getAllTags() {
  return request({ url: '/tags/hot', method: 'get', params: { limit: 100 } })
}

// 创建标签
export function createTag(data) {
  return request({ url: '/tags', method: 'post', data })
}

// 更新标签
export function updateTag(id, data) {
  return request({ url: `/tags/${id}`, method: 'put', data })
}

// 删除标签
export function deleteTag(id) {
  return request({ url: `/tags/${id}`, method: 'delete' })
}
