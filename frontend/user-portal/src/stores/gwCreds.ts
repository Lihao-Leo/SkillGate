import { defineStore } from 'pinia'

/**
 * 执行平台调用凭证（用户自己的 AppKey + AppSecret）：
 * 用于任务查询 / 余额 / 流水（gateway HMAC 签名）。
 * 完整 Secret 仅在签发 / 重置时录入一次，存 localStorage（用户本人浏览器）。
 */
export const useGatewayCredsStore = defineStore('gatewayCreds', {
  state: () => ({
    appKey: localStorage.getItem('portal.gw.appKey') ?? '',
    secret: localStorage.getItem('portal.gw.secret') ?? '',
  }),
  getters: {
    ready: (s) => s.appKey !== '' && s.secret !== '',
  },
  actions: {
    save(appKey: string, secret: string) {
      this.appKey = appKey
      this.secret = secret
      localStorage.setItem('portal.gw.appKey', appKey)
      localStorage.setItem('portal.gw.secret', secret)
    },
    clear() {
      this.appKey = ''
      this.secret = ''
      localStorage.removeItem('portal.gw.appKey')
      localStorage.removeItem('portal.gw.secret')
    },
  },
})
