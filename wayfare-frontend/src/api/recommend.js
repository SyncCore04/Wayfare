import request from './request'

// 记录浏览历史
export function recordBrowse(workId) {
  return request({ url: `/recommend/browse/${workId}`, method: 'post' })
}

// 首页瀑布流推荐
export function getFeed(params) {
  return request({ url: '/recommend/feed', method: 'get', params })
}

// 个性化推荐
export function getPersonalRecommend(params) {
  return request({ url: '/recommend/personal', method: 'get', params })
}

// 热门排行榜
export function getHotWorks(params) {
  return request({ url: '/recommend/hot', method: 'get', params })
}

// 相似作品
export function getSimilarWorks(workId, limit = 10) {
  return request({ url: `/recommend/similar/${workId}`, method: 'get', params: { limit } })
}

// 用户偏好标签
export function getUserTags() {
  return request({ url: '/recommend/user-tags', method: 'get' })
}

// 清除浏览历史
export function clearHistory() {
  return request({ url: '/recommend/history', method: 'delete' })
}
