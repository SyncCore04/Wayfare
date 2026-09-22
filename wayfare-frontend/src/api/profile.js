import request from './request'

// 获取当前用户的旅行偏好画像（P2-A）
// 从未填写过时后端返回「空画像」（userId 已填、其余字段为空），不是 404 —— 前端直接渲染空表单即可
export function getTravelProfile() {
  return request({ url: '/profile/travel', method: 'get' })
}

// 保存当前用户的旅行偏好画像（存在则更新，不存在则插入）
// 整体替换语义：提交什么就是什么，未提交的字段会被清空 —— 所以前端必须提交完整表单
export function saveTravelProfile(data) {
  return request({ url: '/profile/travel', method: 'put', data })
}
