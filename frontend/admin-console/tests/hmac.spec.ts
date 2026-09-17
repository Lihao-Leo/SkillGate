import { describe, expect, it } from 'vitest'
import { signature, signedHeaders, sha256Hex } from '../src/api/hmac'

/**
 * 向量与 Java HmacVerifierTest 同构（sk-secret-test / sk-test-key / ts=1700000000000），
 * 保证浏览器端签名与服务端验签一致。
 */
describe('hmac 签名（与 Java HmacVerifier 同构）', () => {
  it('POST + JSON body 签名与 Java 向量一致', async () => {
    const sig = await signature('sk-secret-test', 'sk-test-key', 1700000000000,
      'POST', '/api/v1/execute', '{"skillCode":"demo"}')
    expect(sig).toBe('9cf84cf5e17c7468e0be76aab5998ec9ee681cdde55b622f7df4ab13e02aee2d')
  })

  it('GET 空 body 签名与 Java 向量一致', async () => {
    const sig = await signature('sk-secret-test', 'sk-test-key', 1700000000000,
      'GET', '/api/v1/executions/t1', '')
    expect(sig).toBe('f37c27edc8e2116a86f494170163d8afa9ef8991461a657ced87b0aa5a31174d')
  })

  it('空 body 与 null body 等价（空字节串）', async () => {
    const a = await sha256Hex('')
    const b = await sha256Hex(null)
    expect(a).toBe(b)
    // sha256("") 已知值
    expect(a).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
  })

  it('签名覆盖 method/path/body（篡改即变）', async () => {
    const base = await signature('sk-secret-test', 'sk-test-key', 1, 'POST', '/p', '{}')
    const otherPath = await signature('sk-secret-test', 'sk-test-key', 1, 'POST', '/p2', '{}')
    const otherBody = await signature('sk-secret-test', 'sk-test-key', 1, 'POST', '/p', '{ }')
    expect(new Set([base, otherPath, otherBody]).size).toBe(3)
  })

  it('signedHeaders 组装三件套 Header', async () => {
    const headers = await signedHeaders(
      { appKey: 'sk-test-key', secret: 'sk-secret-test', adminToken: '' },
      'GET', '/api/v1/skills?pageNo=1&pageSize=20')
    expect(headers['X-Skill-AppKey']).toBe('sk-test-key')
    expect(headers['X-Skill-Timestamp']).toMatch(/^\d{13}$/)
    expect(headers['X-Skill-Signature']).toMatch(/^[0-9a-f]{64}$/)
  })
})
