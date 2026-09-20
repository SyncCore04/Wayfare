import request from './request'

// 上传图片（支持多图）
export function uploadImages(formData) {
  return request({
    url: '/files/batch',
    method: 'post',
    data: formData
    // 不手动设置Content-Type，让axios自动添加multipart/form-data的boundary
  })
}

// 上传单张图片
export function uploadImage(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request({
    url: '/files/upload',
    method: 'post',
    data: formData
  })
}

// 上传头像
export function uploadAvatar(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request({
    url: '/files/avatar',
    method: 'post',
    data: formData
  })
}
