import { create } from 'zustand'

/**
 * 充值平台会话（JWT）：登录换取 token，Bearer 调 /api/user/**。
 */

interface AuthState {
  token: string
  identifier: string
}

export const useAuthStore = create<AuthState>(() => ({
  token: localStorage.getItem('portal.token') ?? '',
  identifier: localStorage.getItem('portal.identifier') ?? '',
}))

export function authLoggedIn(state: Pick<AuthState, 'token'>): boolean {
  return state.token !== ''
}

export function loginSession(token: string, identifier: string) {
  useAuthStore.setState({ token, identifier })
  localStorage.setItem('portal.token', token)
  localStorage.setItem('portal.identifier', identifier)
}

export function logoutSession() {
  useAuthStore.setState({ token: '', identifier: '' })
  localStorage.removeItem('portal.token')
  localStorage.removeItem('portal.identifier')
}
