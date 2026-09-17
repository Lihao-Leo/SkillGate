/**
 * gateway 请求签名（与 Java HmacVerifier 跨语言同构，user-portal 用于
 * 任务查询 / 余额 / 流水——用户持自己的 AppKey+Secret，浏览器端 WebCrypto 签名）。
 */
const encoder = new TextEncoder()

export const HEADER_APP_KEY = 'X-Skill-AppKey'
export const HEADER_TIMESTAMP = 'X-Skill-Timestamp'
export const HEADER_SIGNATURE = 'X-Skill-Signature'

export async function sha256Hex(body: BodyInit | null | undefined): Promise<string> {
  const bytes = body == null || body === '' ? new Uint8Array(0)
    : typeof body === 'string' ? encoder.encode(body)
    : body instanceof Uint8Array ? body
    : body instanceof ArrayBuffer ? new Uint8Array(body)
    : encoder.encode(String(body))
  const digest = await crypto.subtle.digest('SHA-256', bytes as unknown as ArrayBufferView)
  return Array.from(new Uint8Array(digest)).map(b => b.toString(16).padStart(2, '0')).join('')
}

async function hmacSha256Hex(secret: string, stringToSign: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    'raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
  const mac = await crypto.subtle.sign('HMAC', key, encoder.encode(stringToSign))
  return Array.from(new Uint8Array(mac)).map(b => b.toString(16).padStart(2, '0')).join('')
}

export async function signature(secret: string, appKey: string, timestamp: number | string,
                                method: string, pathWithQuery: string,
                                body: BodyInit | null | undefined): Promise<string> {
  const stringToSign = [appKey, String(timestamp), method.toUpperCase(), pathWithQuery,
    await sha256Hex(body)].join('\n')
  return hmacSha256Hex(secret, stringToSign)
}

export async function signedHeaders(appKey: string, secret: string, method: string,
                                    pathWithQuery: string,
                                    body?: BodyInit | null): Promise<Record<string, string>> {
  const timestamp = Date.now()
  const content = body ?? ''
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  headers[HEADER_APP_KEY] = appKey
  headers[HEADER_TIMESTAMP] = String(timestamp)
  headers[HEADER_SIGNATURE] = await signature(secret, appKey, timestamp,
    method, pathWithQuery, content)
  return headers
}
