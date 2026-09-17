import { describe, expect, it } from 'vitest'
import { signature, sha256Hex } from '../src/api/hmac'

/** 向量与 Java HmacVerifierTest 同构，保证浏览器端签名与服务端验签一致 */
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

  it('空 body sha256 与已知值一致', async () => {
    expect(await sha256Hex('')).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
  })
})
