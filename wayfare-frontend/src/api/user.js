import request from './request'

// 获取用户信息
export function getUserInfo(id) {
  return request({ url: `/users/${id}`, method: 'get' })
}

// 更新当前用户信息
export function updateUser(data) {
  return request({ url: '/users/me', method: 'put', data })
}

// 修改密码
export function changePassword(data) {
  return request({ url: '/users/me/password', method: 'put', data })
}

// 上传头像
export function uploadAvatar(formData) {
  return request({
    url: '/files/avatar',
    method: 'post',
    data: formData
    // 不手动设置Content-Type，让axios自动添加multipart/form-data的boundary
  })
}

// 管理员：获取用户列表
export function getUserList(params) {
  return request({ url: '/users/page', method: 'get', params })
}

// 管理员：禁用/启用用户
export function toggleUserStatus(id, status) {
  return request({ url: `/users/${id}/status`, method: 'put', params: { status } })
}
