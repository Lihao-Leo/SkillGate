import { beforeEach, describe, expect, it } from 'vitest'
import { appKeyReady, clearCredentials, loadCredentials, saveCredentials,
  useCredentialsStore } from '../src/stores/credentials'

describe('凭证 store', () => {
  beforeEach(() => {
    localStorage.clear()
    clearCredentials()
  })

  it('save 持久化到 localStorage，重载可见', () => {
    expect(appKeyReady(useCredentialsStore.getState())).toBe(false)
    saveCredentials({ appKey: 'sk-1', secret: 'sec', adminToken: 'adm' })
    expect(appKeyReady(useCredentialsStore.getState())).toBe(true)
    expect(useCredentialsStore.getState().adminToken).toBe('adm')
    expect(JSON.parse(localStorage.getItem('admin-console.creds') ?? '{}'))
      .toEqual({ appKey: 'sk-1', secret: 'sec', adminToken: 'adm' })
  })

  it('clear 清空本地与内存', () => {
    saveCredentials({ appKey: 'sk-1', secret: 'sec', adminToken: 'adm' })
    clearCredentials()
    expect(useCredentialsStore.getState().appKey).toBe('')
    expect(appKeyReady(useCredentialsStore.getState())).toBe(false)
    expect(localStorage.getItem('admin-console.creds')).toBeNull()
  })

  it('脏数据回退为空凭证', () => {
    localStorage.setItem('admin-console.creds', '{broken json')
    expect(loadCredentials()).toEqual({ appKey: '', secret: '', adminToken: '' })
    expect(appKeyReady(useCredentialsStore.getState())).toBe(false)
  })
})
