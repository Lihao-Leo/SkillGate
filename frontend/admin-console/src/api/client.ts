/**
 * gateway HTTP 客户端：业务面 HMAC 签名请求 + 管理面 X-Admin-Token 请求，
 * 统一解包 ApiResponse{code,message,data}（code=0 成功）。
 */
import { signedHeaders } from './hmac'
import type { Credentials } from '../stores/credentials'

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export class ApiError extends Error {
  constructor(public readonly code: number, message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

export interface GatewayClientOptions {
  /** 业务面凭证（HMAC）；为 null 时调用业务面接口抛错 */
  getCredentials: () => Credentials | null
  /** 管理面 token */
  getAdminToken: () => string | null
  /** 注入 fetch（测试替身）；默认全局 fetch */
  fetchImpl?: typeof fetch
}

async function unwrap<T>(response: Response, fetchImpl: typeof fetch): Promise<T> {
  const text = await response.text()
  let parsed: ApiResponse<T> | null = null
  try {
    parsed = JSON.parse(text) as ApiResponse<T>
  } catch {
    parsed = null
  }
  if (parsed && typeof parsed.code === 'number') {
    if (parsed.code !== 0) {
      throw new ApiError(parsed.code, parsed.message ?? `错误码 ${parsed.code}`)
    }
    return parsed.data
  }
  throw new ApiError(-1, `非预期响应 HTTP ${response.status}: ${text.slice(0, 200)}`)
}

export function createGatewayClient(options: GatewayClientOptions) {
  const doFetch = options.fetchImpl ?? ((...args: Parameters<typeof fetch>) => fetch(...args))

  /** 业务面：HMAC 签名（签名串 = 实际发送的 method + path[?query] + body） */
  async function signedJson<T>(method: string, pathWithQuery: string,
                               body?: unknown): Promise<T> {
    const creds = options.getCredentials()
    if (!creds) {
      throw new ApiError(40101, '未配置 AppKey/AppSecret，请先在「连接配置」录入')
    }
    const bodyText = body === undefined ? '' : JSON.stringify(body)
    const headers = await signedHeaders(creds, method, pathWithQuery,
      bodyText === '' ? null : bodyText)
    const response = await doFetch(pathWithQuery, {
      method,
      headers,
      body: bodyText === '' ? undefined : bodyText,
    })
    return unwrap<T>(response, doFetch)
  }

  /**
   * 业务面 multipart 上传。服务端对 multipart 不缓存 body（CachedBodyFilter：
   * 大文件内存不可控），签名口径 = sha256(空 body)——只签方法+路径，与 S3
   * 预签名 PUT 不签内容的实践一致；报文字节照常发送。
   */
  async function signedUpload<T>(pathWithQuery: string,
                                 fields: Record<string, string>,
                                 file: File): Promise<T> {
    const creds = options.getCredentials()
    if (!creds) {
      throw new ApiError(40101, '未配置 AppKey/AppSecret，请先在「连接配置」录入')
    }
    const encoder = new TextEncoder()
    const boundary = '----skillplatform' + Date.now().toString(16) + Math.random().toString(16).slice(2)
    const parts: Uint8Array[] = []
    for (const [name, value] of Object.entries(fields)) {
      if (value === undefined || value === '') continue
      parts.push(encoder.encode(
        `--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n${value}\r\n`))
    }
    parts.push(encoder.encode(
      `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${file.name}"\r\n`
      + `Content-Type: ${file.type || 'application/zip'}\r\n\r\n`))
    parts.push(new Uint8Array(await file.arrayBuffer()))
    parts.push(encoder.encode(`\r\n--${boundary}--\r\n`))
    const body = concat(parts)
    const headers = await signedHeaders(creds, 'POST', pathWithQuery, null,
      `multipart/form-data; boundary=${boundary}`)
    const response = await doFetch(pathWithQuery, { method: 'POST', headers, body })
    return unwrap<T>(response, doFetch)
  }

  /** 管理面：X-Admin-Token */
  async function adminJson<T>(method: string, pathWithQuery: string,
                              body?: unknown): Promise<T> {
    const token = options.getAdminToken()
    if (!token) {
      throw new ApiError(40101, '未配置 AdminToken，请先在「连接配置」录入')
    }
    const response = await doFetch(pathWithQuery, {
      method,
      headers: { 'X-Admin-Token': token, 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    return unwrap<T>(response, doFetch)
  }

  /** 管理面 multipart：手工构造（与 signedUpload 同法），X-Admin-Token 鉴权 */
  async function adminUpload<T>(pathWithQuery: string, file: File): Promise<T> {
    const token = options.getAdminToken()
    if (!token) {
      throw new ApiError(40101, '未配置 AdminToken，请先在「连接配置」录入')
    }
    const boundary = '----skillplatform' + Date.now().toString(16) + Math.random().toString(16).slice(2)
    const encoder = new TextEncoder()
    const parts: Uint8Array[] = [encoder.encode(
      `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${file.name}"\r\n`
      + `Content-Type: ${file.type || 'application/octet-stream'}\r\n\r\n`)]
    parts.push(new Uint8Array(await file.arrayBuffer()))
    parts.push(encoder.encode(`\r\n--${boundary}--\r\n`))
    const body = concat(parts)
    const response = await doFetch(pathWithQuery, {
      method: 'POST',
      headers: { 'X-Admin-Token': token,
        'Content-Type': `multipart/form-data; boundary=${boundary}` },
      body,
    })
    return unwrap<T>(response, doFetch)
  }

  return { signedJson, signedUpload, adminJson, adminUpload }
}

function concat(chunks: Uint8Array[]): Uint8Array {
  const total = chunks.reduce((n, c) => n + c.length, 0)
  const merged = new Uint8Array(total)
  let offset = 0
  for (const chunk of chunks) {
    merged.set(chunk, offset)
    offset += chunk.length
  }
  return merged
}

export function buildQuery(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      search.append(key, String(value))
    }
  }
  const encoded = search.toString()
  return encoded === '' ? '' : `?${encoded}`
}
