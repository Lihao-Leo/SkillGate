import { create } from 'zustand'

/**
 * 管理后台凭证：
 * - AppKey + AppSecret：业务面（/api/v1/** HMAC 签名），运营持有本租户 key
 * - AdminToken：管理面 legacy 静态 token（V2.1 过渡兼容，JWT 为主通道）
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

/** 从 localStorage 重新读取（脏数据容错：解析失败回退空凭证；测试与调试用）。 */
export function loadCredentials(): Credentials {
  return load()
}

interface CredentialsState {
  appKey: string
  secret: string
  adminToken: string
}

export const useCredentialsStore = create<CredentialsState>(() => load())

/** 业务面（HMAC）可用 */
export function appKeyReady(state: Pick<CredentialsState, 'appKey' | 'secret'>): boolean {
  return state.appKey !== '' && state.secret !== ''
}

export function saveCredentials(creds: Credentials) {
  useCredentialsStore.setState({ ...creds })
  localStorage.setItem(STORAGE_KEY, JSON.stringify(creds))
}

export function clearCredentials() {
  useCredentialsStore.setState({ appKey: '', secret: '', adminToken: '' })
  localStorage.removeItem(STORAGE_KEY)
}
