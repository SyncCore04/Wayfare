import { defineStore } from 'pinia'
import { login, register, getCurrentUser } from '@/api/auth'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    userInfo: JSON.parse(localStorage.getItem('userInfo') || 'null'),
    roles: []
  }),

  getters: {
    isLoggedIn: state => !!state.token,
    isAdmin: state => state.userInfo?.role === 'admin',
    userId: state => state.userInfo?.id,
    nickname: state => state.userInfo?.nickname || state.userInfo?.username,
    avatar: state => state.userInfo?.avatar || ''
  },

  actions: {
    // 登录
    async login(loginForm) {
      const res = await login(loginForm)
      this.token = res.data.token
      this.userInfo = {
        id: res.data.userId,
        username: res.data.username,
        nickname: res.data.nickname,
        avatar: res.data.avatar,
        role: res.data.role
      }
      localStorage.setItem('token', this.token)
      localStorage.setItem('userInfo', JSON.stringify(this.userInfo))
      return res
    },

    // 注册
    async register(registerForm) {
      const res = await register(registerForm)
      return res
    },

    // 拉取用户信息
    async fetchUserInfo() {
      try {
        const res = await getCurrentUser()
        this.userInfo = res.data
        localStorage.setItem('userInfo', JSON.stringify(this.userInfo))
        return res.data
      } catch (e) {
        return null
      }
    },

    // 更新用户信息
    updateUserInfo(info) {
      this.userInfo = { ...this.userInfo, ...info }
      localStorage.setItem('userInfo', JSON.stringify(this.userInfo))
    },

    // 退出登录
    logout() {
      this.token = ''
      this.userInfo = null
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
    }
  }
})
