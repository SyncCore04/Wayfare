import axios from 'axios'
import { ElMessage } from 'element-plus'
import { ref } from 'vue'
import router from '@/router'

/**
 * 正在进行的请求数（P5-D）。
 *
 * 拦截器里 +1 / -1，App.vue 据此显示顶部加载条 ——
 * 这样「有没有在加载」只有一处真相，各页面不必各自维护 loading 标志，
 * 也不会出现「A 页面转圈、B 页面不转」的不一致。
 * 组件只读它，不要直接改。
 */
export const pendingCount = ref(0)

const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 请求拦截器：携带JWT Token
request.interceptors.request.use(
  config => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`
    }
    pendingCount.value++
    return config
  },
  error => {
    // 请求都没发出去也要把计数还回去，否则加载条会永远停在那
    pendingCount.value = Math.max(0, pendingCount.value - 1)
    return Promise.reject(error)
  }
)

// 响应拦截器：统一处理返回结果
request.interceptors.response.use(
  response => {
    pendingCount.value = Math.max(0, pendingCount.value - 1)
    const res = response.data
    // 业务成功
    if (res.code === 200) {
      return res
    }
    // 未登录或token过期
    if (res.code === 401) {
      ElMessage.error('登录已过期，请重新登录')
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
      router.push('/login')
      return Promise.reject(new Error(res.message || '未登录'))
    }
    // 其他业务错误
    ElMessage.error(res.message || '请求失败')
    return Promise.reject(new Error(res.message || '请求失败'))
  },
  error => {
    pendingCount.value = Math.max(0, pendingCount.value - 1)
    if (error.response) {
      const status = error.response.status
      if (status === 401) {
        ElMessage.error('登录已过期，请重新登录')
        localStorage.removeItem('token')
        localStorage.removeItem('userInfo')
        router.push('/login')
      } else if (status === 403) {
        ElMessage.error('没有权限访问')
      } else if (status === 404) {
        ElMessage.error('请求的资源不存在')
      } else if (status >= 500) {
        ElMessage.error('服务器错误，请稍后重试')
      } else {
        ElMessage.error(error.response.data?.message || '请求失败')
      }
    } else if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请检查网络')
    } else {
      ElMessage.error('网络错误，请检查连接')
    }
    return Promise.reject(error)
  }
)

export default request
