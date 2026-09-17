import { defineStore } from 'pinia'

/**
 * 充值平台会话（JWT）：登录换取 token，Bearer 调 /api/user/**。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('portal.token') ?? '',
    identifier: localStorage.getItem('portal.identifier') ?? '',
  }),
  getters: {
    loggedIn: (s) => s.token !== '',
  },
  actions: {
    login(token: string, identifier: string) {
      this.token = token
      this.identifier = identifier
      localStorage.setItem('portal.token', token)
      localStorage.setItem('portal.identifier', identifier)
    },
    logout() {
      this.token = ''
      this.identifier = ''
      localStorage.removeItem('portal.token')
      localStorage.removeItem('portal.identifier')
    },
  },
})
