import { create } from 'zustand'

/**
 * 后台登录会话（V2.1）：JWT + 角色 + 权限点，localStorage 持久化（内部 PC）。
 * 与业务面 HMAC 凭证（stores/credentials）相互独立。
 */
export interface AuthSession {
  token: string
  username: string
  displayName: string
  role: string
  roleName: string
  permissions: string[]
}

const STORAGE_KEY = 'admin-console.auth'

function load(): AuthSession | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<AuthSession>
    if (!parsed.token || !parsed.username) return null
    return {
      token: parsed.token,
      username: parsed.username,
      displayName: parsed.displayName ?? parsed.username,
      role: parsed.role ?? 'VIEWER',
      roleName: parsed.roleName ?? parsed.role ?? 'VIEWER',
      permissions: parsed.permissions ?? [],
    }
  } catch {
    return null
  }
}

interface AuthState {
  session: AuthSession | null
}

export const useAuthStore = create<AuthState>(() => ({ session: load() }))

/** 权限判定：SUPER_ADMIN 通配；未登录一律 false（router 守卫与菜单过滤共用纯函数）。 */
export function hasPerm(session: AuthSession | null, perm: string): boolean {
  if (!session) return false
  if (session.role === 'SUPER_ADMIN') return true
  return session.permissions.includes(perm)
}

export function saveSession(session: AuthSession) {
  useAuthStore.setState({ session })
  localStorage.setItem(STORAGE_KEY, JSON.stringify(session))
}

export function clearSession() {
  useAuthStore.setState({ session: null })
  localStorage.removeItem(STORAGE_KEY)
}
