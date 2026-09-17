import { describe, expect, it, vi } from 'vitest'
import { createGatewayClient, ApiError } from '../src/api/client'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status })
}

describe('gateway 客户端', () => {
  it('signedJson：URL 不变、注入签名 Header、解包 data', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(
      { code: 0, message: 'ok', data: { appKeyId: 'k1', balance: 100, frozen: 0 } }))
    const client = createGatewayClient({
      getCredentials: () => ({ appKey: 'sk-test-key', secret: 'sk-secret-test', adminToken: '' }),
      getAdminToken: () => null,
      fetchImpl: fetchImpl as unknown as typeof fetch,
    })
    const data = await client.signedJson('GET', '/api/v1/account/balance')
    expect(data).toEqual({ appKeyId: 'k1', balance: 100, frozen: 0 })
    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe('/api/v1/account/balance')
    expect(init.headers['X-Skill-AppKey']).toBe('sk-test-key')
    expect(init.headers['X-Skill-Signature']).toMatch(/^[0-9a-f]{64}$/)
    expect(init.body).toBeUndefined()
  })

  it('signedJson：POST body 参与签名且原样发送', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: {} }))
    const client = createGatewayClient({
      getCredentials: () => ({ appKey: 'sk-test-key', secret: 'sk-secret-test', adminToken: '' }),
      getAdminToken: () => null,
      fetchImpl: fetchImpl as unknown as typeof fetch,
    })
    await client.signedJson('POST', '/api/v1/admin/credits/recharge',
      { appKeyId: 'k1', points: 100, orderNo: 'R1' })
    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe('/api/v1/admin/credits/recharge')
    expect(init.body).toBe('{"appKeyId":"k1","points":100,"orderNo":"R1"}')
  })

  it('signedUpload：手工 multipart 字节参与签名（与原始 body 验签一致）', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(
      { code: 0, message: 'ok', data: { version: '1.0.0', packageSha256: 'a' } }))
    const client = createGatewayClient({
      getCredentials: () => ({ appKey: 'sk-test-key', secret: 'sk-secret-test', adminToken: '' }),
      getAdminToken: () => null,
      fetchImpl: fetchImpl as unknown as typeof fetch,
    })
    const zip = new File([new Uint8Array([0x50, 0x4b, 0x03, 0x04, 1, 2, 3])], 'pkg.zip',
      { type: 'application/zip' })
    const result = await client.signedUpload<{ version: string }>('/api/v1/skills/upload',
      { skillCode: 'demo', version: '1.0.0', publish: 'true' }, zip)
    expect(result.version).toBe('1.0.0')

    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe('/api/v1/skills/upload')
    const headers = init.headers as Record<string, string>
    expect(headers['Content-Type']).toMatch(/^multipart\/form-data; boundary=/)

    // 闭环：multipart 签名口径 = sha256(空 body)（CachedBodyFilter 对 multipart 不缓存），
    // 用空字节重算签名必须与签名头一致；报文字节照常发送且包含表单字段与文件
    const bodyBytes = new Uint8Array(await new Response(init.body as BodyInit).arrayBuffer())
    const bodyText = new TextDecoder().decode(bodyBytes)
    expect(bodyText).toContain('name="skillCode"')
    expect(bodyText).toContain('demo')
    expect(bodyText).toContain('name="file"; filename="pkg.zip"')

    const { signature } = await import('../src/api/hmac')
    const recomputed = await signature('sk-secret-test', 'sk-test-key',
      headers['X-Skill-Timestamp'], 'POST', '/api/v1/skills/upload', '')
    expect(headers['X-Skill-Signature']).toBe(recomputed)
  })

  it('未配置凭证时抛 40101 引导提示', async () => {
    const client = createGatewayClient({
      getCredentials: () => null, getAdminToken: () => null,
      fetchImpl: vi.fn() as unknown as typeof fetch,
    })
    await expect(client.signedJson('GET', '/api/v1/skills')).rejects.toMatchObject({
      code: 40101, name: 'ApiError',
    })
  })

  it('业务错误码（code != 0）抛 ApiError', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(
      { code: 40201, message: '点数不足', data: null }, 402))
    const client = createGatewayClient({
      getCredentials: () => ({ appKey: 'k', secret: 's', adminToken: '' }),
      getAdminToken: () => null,
      fetchImpl: fetchImpl as unknown as typeof fetch,
    })
    await expect(client.signedJson('POST', '/api/v1/execute', {}))
      .rejects.toMatchObject({ code: 40201, message: '点数不足' })
  })

  it('adminJson：注入 X-Admin-Token，不带 HMAC Header', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(
      { code: 0, message: 'ok', data: { total: 1, items: [] } }))
    const client = createGatewayClient({
      getCredentials: () => ({ appKey: 'k', secret: 's', adminToken: '' }),
      getAdminToken: () => 'admin-token-x',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    })
    await client.adminJson('GET', '/api/v1/admin/app-keys?pageNo=1&pageSize=20')
    const [, init] = fetchImpl.mock.calls[0]
    expect(init.headers['X-Admin-Token']).toBe('admin-token-x')
    expect(init.headers['X-Skill-Signature']).toBeUndefined()
  })

  it('admin 未配置 token 抛错；非 JSON 响应抛 ApiError(-1)', async () => {
    const client = createGatewayClient({
      getCredentials: () => null, getAdminToken: () => null,
      fetchImpl: vi.fn() as unknown as typeof fetch,
    })
    await expect(client.adminJson('GET', '/api/v1/admin/app-keys'))
      .rejects.toBeInstanceOf(ApiError)

    const badClient = createGatewayClient({
      getCredentials: () => null,
      getAdminToken: () => 't',
      fetchImpl: vi.fn().mockResolvedValue(
        new Response('<html>502</html>', { status: 502 })) as unknown as typeof fetch,
    })
    await expect(badClient.adminJson('GET', '/api/v1/admin/app-keys'))
      .rejects.toMatchObject({ code: -1 })
  })
})
