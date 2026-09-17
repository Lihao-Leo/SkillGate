import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../src/stores/auth'
import { useGatewayCredsStore } from '../src/stores/gwCreds'

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
})

describe('auth store（JWT 会话）', () => {
  it('login/logout 持久化', () => {
    const auth = useAuthStore()
    expect(auth.loggedIn).toBe(false)
    auth.login('jwt-token', '138****8000')
    expect(auth.loggedIn).toBe(true)
    expect(localStorage.getItem('portal.token')).toBe('jwt-token')

    auth.logout()
    expect(auth.loggedIn).toBe(false)
    expect(localStorage.getItem('portal.token')).toBeNull()
  })
})

describe('gatewayCreds store（执行平台凭证）', () => {
  it('save/clear 与 ready 判定', () => {
    const creds = useGatewayCredsStore()
    expect(creds.ready).toBe(false)
    creds.save('sk-user-1', 'sec')
    expect(creds.ready).toBe(true)
    expect(localStorage.getItem('portal.gw.secret')).toBe('sec')

    creds.clear()
    expect(creds.ready).toBe(false)
    expect(localStorage.getItem('portal.gw.appKey')).toBeNull()
  })
})
