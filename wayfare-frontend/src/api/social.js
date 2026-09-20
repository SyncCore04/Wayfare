import request from './request'

// 点赞/取消点赞作品（toggle模式）
export function likeWork(workId) {
  return request({ url: '/likes/toggle', method: 'post', params: { targetType: 1, targetId: workId } })
}

// 取消点赞（同toggle）
export function unlikeWork(workId) {
  return request({ url: '/likes/toggle', method: 'post', params: { targetType: 1, targetId: workId } })
}

// 检查是否已点赞
export function checkLike(workId) {
  return request({ url: '/likes/check', method: 'get', params: { targetType: 1, targetId: workId } })
}

// 收藏/取消收藏作品（toggle模式）
export function favoriteWork(workId) {
  return request({ url: '/favorites/toggle', method: 'post', params: { workId } })
}

// 取消收藏（同toggle）
export function unfavoriteWork(workId) {
  return request({ url: '/favorites/toggle', method: 'post', params: { workId } })
}

// 检查是否已收藏
export function checkFavorite(workId) {
  return request({ url: '/favorites/check', method: 'get', params: { workId } })
}

// 我的收藏列表
export function getMyFavorites(params) {
  return request({ url: '/favorites/my', method: 'get', params })
}

// 关注/取消关注用户（toggle模式）
export function followUser(userId) {
  return request({ url: '/follows/toggle', method: 'post', params: { followingId: userId } })
}

// 取消关注（同toggle）
export function unfollowUser(userId) {
  return request({ url: '/follows/toggle', method: 'post', params: { followingId: userId } })
}

// 检查是否已关注
export function checkFollow(userId) {
  return request({ url: '/follows/check', method: 'get', params: { followingId: userId } })
}

// 我的关注列表
export function getMyFollowing(params) {
  return request({ url: '/follows/following', method: 'get', params })
}

// 我的粉丝列表
export function getMyFollowers(params) {
  return request({ url: '/follows/followers', method: 'get', params })
}

// 用户关注列表
export function getUserFollowing(userId, params) {
  return request({ url: `/follows/${userId}/following`, method: 'get', params })
}

// 用户粉丝列表
export function getUserFollowers(userId, params) {
  return request({ url: `/follows/${userId}/followers`, method: 'get', params })
}
