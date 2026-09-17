import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCredentialsStore } from '../src/stores/credentials'

describe('凭证 store', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('save 持久化到 localStorage，重载可见', () => {
    const store = useCredentialsStore()
    expect(store.appKeyReady).toBe(false)
    store.save({ appKey: 'sk-1', secret: 'sec', adminToken: 'adm' })
    expect(store.appKeyReady).toBe(true)
    expect(store.adminReady).toBe(true)
    expect(JSON.parse(localStorage.getItem('admin-console.creds') ?? '{}'))
      .toEqual({ appKey: 'sk-1', secret: 'sec', adminToken: 'adm' })
  })

  it('clear 清空本地与内存', () => {
    const store = useCredentialsStore()
    store.save({ appKey: 'sk-1', secret: 'sec', adminToken: 'adm' })
    store.clear()
    expect(store.appKey).toBe('')
    expect(store.appKeyReady).toBe(false)
    expect(localStorage.getItem('admin-console.creds')).toBeNull()
  })

  it('脏数据回退为空凭证', () => {
    localStorage.setItem('admin-console.creds', '{broken json')
    setActivePinia(createPinia())
    const store = useCredentialsStore()
    expect(store.appKeyReady).toBe(false)
  })
})
