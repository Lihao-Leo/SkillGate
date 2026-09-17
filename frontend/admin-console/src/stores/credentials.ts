import { defineStore } from 'pinia'

/**
 * 管理后台凭证（V1 网关无运营账号体系）：
 * - AppKey + AppSecret：业务面（/api/v1/** HMAC 签名），运营持有本租户 key
 * - AdminToken：管理面（/api/v1/admin/** X-Admin-Token）
 * 仅存 localStorage（内部 PC，浏览器本地）；退出即清。
 */
export interface Credentials {
  appKey: string
  secret: string
  adminToken: string
}

const STORAGE_KEY = 'admin-console.creds'

function load(): Credentials {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { appKey: '', secret: '', adminToken: '' }
    const parsed = JSON.parse(raw) as Partial<Credentials>
    return {
      appKey: parsed.appKey ?? '',
      secret: parsed.secret ?? '',
      adminToken: parsed.adminToken ?? '',
    }
  } catch {
    return { appKey: '', secret: '', adminToken: '' }
  }
}

export const useCredentialsStore = defineStore('credentials', {
  state: (): Credentials => load(),
  getters: {
    /** 业务面（HMAC）可用 */
    appKeyReady: (s) => s.appKey !== '' && s.secret !== '',
    /** 管理面（admin token）可用 */
    adminReady: (s) => s.adminToken !== '',
  },
  actions: {
    save(creds: Credentials) {
      this.appKey = creds.appKey
      this.secret = creds.secret
      this.adminToken = creds.adminToken
      localStorage.setItem(STORAGE_KEY, JSON.stringify(creds))
    },
    clear() {
      this.appKey = ''
      this.secret = ''
      this.adminToken = ''
      localStorage.removeItem(STORAGE_KEY)
    },
  },
})
