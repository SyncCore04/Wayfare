import request from './request'

// 分类树（公开接口，用于发布页/首页的分类下拉）
// 后端：GET /categories/tree —— 返回二级树，只含启用状态的分类
export function getCategoryTree() {
  return request({ url: '/categories/tree', method: 'get' })
}

// 分类分页（管理员）
export function getCategoryPage(params) {
  return request({ url: '/categories/page', method: 'get', params })
}

// 分类详情
export function getCategoryDetail(id) {
  return request({ url: `/categories/${id}`, method: 'get' })
}

// 新建分类（管理员）
export function createCategory(data) {
  return request({ url: '/categories', method: 'post', data })
}

// 更新分类（管理员）
export function updateCategory(id, data) {
  return request({ url: `/categories/${id}`, method: 'put', data })
}

// 删除分类（管理员；有作品或子分类时后端会拒绝）
export function deleteCategory(id) {
  return request({ url: `/categories/${id}`, method: 'delete' })
}
