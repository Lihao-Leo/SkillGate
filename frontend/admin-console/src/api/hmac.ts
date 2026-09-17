/**
 * gateway 请求签名（与 Java HmacVerifier 跨语言同构）：
 * 签名串五段 \n 分隔：appKey + timestamp + METHOD(大写) + path[?query] + sha256Hex(body)，
 * HMAC-SHA256(secret) 小写 hex；GET 无 body 取空字节串；时间戳容差 ±5min。
 */
import type { Credentials } from '../stores/credentials'

export const HEADER_APP_KEY = 'X-Skill-AppKey'
export const HEADER_TIMESTAMP = 'X-Skill-Timestamp'
export const HEADER_SIGNATURE = 'X-Skill-Signature'

const encoder = new TextEncoder()

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

/**
 * 计算签名（与 HmacVerifier.signature 相同的输入顺序）。
 */
export async function signature(secret: string, appKey: string, timestamp: number | string,
                                method: string, pathWithQuery: string,
                                body: BodyInit | null | undefined): Promise<string> {
  const stringToSign = [appKey, String(timestamp), method.toUpperCase(), pathWithQuery,
    await sha256Hex(body)].join('\n')
  return hmacSha256Hex(secret, stringToSign)
}

/**
 * 组装带签名的请求 Headers（JSON 或预构建原始体如 multipart）。
 */
export async function signedHeaders(creds: Credentials, method: string,
                                    pathWithQuery: string,
                                    body?: BodyInit | null,
                                    contentType = 'application/json'): Promise<Record<string, string>> {
  const timestamp = Date.now()
  const content = body ?? ''
  const headers: Record<string, string> = { 'Content-Type': contentType }
  headers[HEADER_APP_KEY] = creds.appKey
  headers[HEADER_TIMESTAMP] = String(timestamp)
  headers[HEADER_SIGNATURE] = await signature(creds.secret, creds.appKey, timestamp,
    method, pathWithQuery, content)
  return headers
}
