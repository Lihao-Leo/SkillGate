import { vi } from 'vitest'

// Node ≥22 原生 localStorage 抢占 happy-dom 实现（缺 clear 等），统一替换为标准实现
const store = new Map<string, string>()
const storage: Storage = {
  get length() {
    return store.size
  },
  clear() {
    store.clear()
  },
  getItem(key: string) {
    return store.get(key) ?? null
  },
  setItem(key: string, value: string) {
    store.set(key, String(value))
  },
  removeItem(key: string) {
    store.delete(key)
  },
  key(index: number) {
    return [...store.keys()][index] ?? null
  },
}
vi.stubGlobal('localStorage', storage)
