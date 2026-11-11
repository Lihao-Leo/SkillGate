import { beforeEach, describe, expect, it } from 'vitest'
import { loginSession, logoutSession, useAuthStore } from '../src/stores/auth'
import { clearGwCreds, gwCredsReady, saveGwCreds, useGatewayCredsStore } from '../src/stores/gwCreds'

describe('会话与凭证 store', () => {
  beforeEach(() => {
    localStorage.clear()
    logoutSession()
    clearGwCreds()
  })

  it('auth login/logout 持久化', () => {
    loginSession('jwt-9', '13800008000')
    expect(useAuthStore.getState().token).toBe('jwt-9')
    expect(useAuthStore.getState().identifier).toBe('13800008000')
    expect(localStorage.getItem('portal.token')).toBe('jwt-9')
    logoutSession()
    expect(useAuthStore.getState().token).toBe('')
    expect(localStorage.getItem('portal.token')).toBeNull()
  })

  it('gwCreds save/clear 与 ready 派生', () => {
    expect(gwCredsReady(useGatewayCredsStore.getState())).toBe(false)
    saveGwCreds('sk-user-1', 'sec-1')
    expect(gwCredsReady(useGatewayCredsStore.getState())).toBe(true)
    expect(localStorage.getItem('portal.gw.appKey')).toBe('sk-user-1')
    clearGwCreds()
    expect(useGatewayCredsStore.getState().appKey).toBe('')
    expect(localStorage.getItem('portal.gw.appKey')).toBeNull()
  })
})
