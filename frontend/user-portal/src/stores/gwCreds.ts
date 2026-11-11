import { create } from 'zustand'

/**
 * 执行平台调用凭证（用户自己的 AppKey + AppSecret）：
 * 用于任务查询 / 余额 / 流水（gateway HMAC 签名）。
 * 完整 Secret 仅在签发 / 重置时录入一次，存 localStorage（用户本人浏览器）。
 */

interface GatewayCredsState {
  appKey: string
  secret: string
}

export const useGatewayCredsStore = create<GatewayCredsState>(() => ({
  appKey: localStorage.getItem('portal.gw.appKey') ?? '',
  secret: localStorage.getItem('portal.gw.secret') ?? '',
}))

export function gwCredsReady(state: Pick<GatewayCredsState, 'appKey' | 'secret'>): boolean {
  return state.appKey !== '' && state.secret !== ''
}

export function saveGwCreds(appKey: string, secret: string) {
  useGatewayCredsStore.setState({ appKey, secret })
  localStorage.setItem('portal.gw.appKey', appKey)
  localStorage.setItem('portal.gw.secret', secret)
}

export function clearGwCreds() {
  useGatewayCredsStore.setState({ appKey: '', secret: '' })
  localStorage.removeItem('portal.gw.appKey')
  localStorage.removeItem('portal.gw.secret')
}
