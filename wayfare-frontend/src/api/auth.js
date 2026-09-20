import request from './request'

// 登录
export function login(data) {
  return request({ url: '/auth/login', method: 'post', data })
}

// 注册
export function register(data) {
  return request({ url: '/auth/register', method: 'post', data })
}

// 获取当前用户信息
export function getCurrentUser() {
  return request({ url: '/users/me', method: 'get' })
}
