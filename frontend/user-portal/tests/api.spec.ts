import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { rechargeApi, gatewayUserApi, ApiError } from '../src/api'
import { useAuthStore } from '../src/stores/auth'
import { useGatewayCredsStore } from '../src/stores/gwCreds'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status })
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
})

describe('rechargeApi（JWT）', () => {
  it('登录后注入 Bearer token 并解包 data', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(
      { code: 0, message: 'ok', data: { token: 'jwt-1', userId: 7, identifier: '138****8000' } }))
    const result = await rechargeApi.login('13800008000', '123456',
      fetchImpl as unknown as typeof fetch)
    expect(result.token).toBe('jwt-1')
    // 登录请求本身尚无会话，不携带 Authorization
    const loginHeaders = fetchImpl.mock.calls[0][1]?.headers as Record<string, string>
    expect(loginHeaders.Authorization).toBeUndefined()

    // 登录成功后（store 已写入）后续请求携带 token
    useAuthStore().login('jwt-1', '138****8000')
    const fetch2 = vi.fn().mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: [] }))
    await rechargeApi.listKeys(fetch2 as unknown as typeof fetch)
    expect(fetch2.mock.calls[0][1]?.headers).toMatchObject({ Authorization: 'Bearer jwt-1' })
  })

  it('未登录不带 Authorization；业务码非 0 抛 ApiError', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({ code: 42901, message: '发送过频', data: null }, 429))
    await expect(rechargeApi.sendCode('13800008000', fetchImpl as unknown as typeof fetch))
      .rejects.toMatchObject({ code: 42901, message: '发送过频' })
    const headers = fetchImpl.mock.calls[0][1]?.headers as Record<string, string>
    expect(headers.Authorization).toBeUndefined()
  })

  it('下单携带 channel/skuId 或 customAmountFen；查询拼 orderNo', async () => {
    // 每次调用返回全新 Response（body 只能读一次，不可复用同一实例）
    const fetchImpl = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(
      { code: 0, message: 'ok', data: { orderNo: 'R1', status: 'PAYING' } })))
    await rechargeApi.createOrder('MOCK', 'sku-100', undefined,
      fetchImpl as unknown as typeof fetch)
    expect(fetchImpl.mock.calls[0][0]).toBe('/api/user/orders')
    expect(fetchImpl.mock.calls[0][1]?.body)
      .toBe(JSON.stringify({ channel: 'MOCK', skuId: 'sku-100', customAmountFen: undefined }))

    await rechargeApi.viewOrder('R1', fetchImpl as unknown as typeof fetch)
    expect(fetchImpl.mock.calls[1][0]).toBe('/api/user/orders/R1')
  })
})

describe('gatewayUserApi（用户 AppKey HMAC）', () => {
  it('未配置凭证抛 40101', async () => {
    await expect(gatewayUserApi.balance()).rejects.toBeInstanceOf(ApiError)
  })

  it('配置凭证后 HMAC 签名请求任务查询', async () => {
    useGatewayCredsStore().save('sk-user-1', 'sec-1')
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({
      code: 0, message: 'ok',
      data: { taskId: 'task-9', status: 'SUCCEEDED', progress: 100 },
    }))
    // gatewayUserApi 内部用全局 fetch —— stub 之
    vi.stubGlobal('fetch', fetchImpl)
    const view = await gatewayUserApi.execution('task-9')
    expect(view.status).toBe('SUCCEEDED')
    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe('/api/v1/executions/task-9')
    expect((init as RequestInit).headers).toMatchObject({ 'X-Skill-AppKey': 'sk-user-1' })
    vi.unstubAllGlobals()
  })
})
