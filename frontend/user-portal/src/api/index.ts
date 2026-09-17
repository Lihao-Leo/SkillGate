/**
 * recharge-service 用户 API（JWT Bearer）+ gateway 用户侧 API（HMAC）。
 * 响应统一解包 ApiResponse{code,message,data}。
 */
import { signedHeaders } from './hmac'
import { useAuthStore } from '../stores/auth'
import { useGatewayCredsStore } from '../stores/gwCreds'

export class ApiError extends Error {
  constructor(public readonly code: number, message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

async function unwrap<T>(response: Response): Promise<T> {
  const text = await response.text()
  let parsed: { code: number; message: string; data: T } | null = null
  try {
    parsed = JSON.parse(text)
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

export interface FetchLike {
  (url: string, init?: RequestInit): Promise<Response>
}

/** 充值平台（JWT）请求 */
async function rechargeFetch<T>(method: string, path: string, body?: unknown,
                                fetchImpl: FetchLike = fetch): Promise<T> {
  const auth = useAuthStore()
  const response = await fetchImpl(path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(auth.token ? { Authorization: `Bearer ${auth.token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return unwrap<T>(response)
}

/** 执行平台（用户 AppKey HMAC）请求 */
async function gatewayFetch<T>(method: string, pathWithQuery: string, body?: unknown,
                               fetchImpl: FetchLike = fetch): Promise<T> {
  const creds = useGatewayCredsStore()
  if (!creds.ready) {
    throw new ApiError(40101, '未配置 AppKey/AppSecret，请先在「APIKey 管理」录入')
  }
  const bodyText = body === undefined ? '' : JSON.stringify(body)
  const headers = await signedHeaders(creds.appKey, creds.secret, method, pathWithQuery,
    bodyText === '' ? null : bodyText)
  const response = await fetchImpl(pathWithQuery, {
    method,
    headers,
    body: bodyText === '' ? undefined : bodyText,
  })
  return unwrap<T>(response)
}

// ---- recharge DTO（与服务端对齐）----
export interface Sku {
  skuId: string
  name: string
  points: number
  priceFen: number
}

export interface UserKeyItem {
  appKeyId: string
  isPrimary: boolean
  hasPendingSecret: boolean
  createdAt: string | null
}

export interface OrderView {
  orderNo: string
  status: 'CREATED' | 'PAYING' | 'PAID' | 'CREDITED' | 'EXPIRED'
  channel: string
  points: number
  amountFen: number
  codeUrl: string | null
  expiresAt: string | null
  paidAt: string | null
  creditedAt: string | null
  creditedPoints?: number
  newlyIssuedKey?: { appKeyId: string; appSecret: string }
}

export interface LoginResult {
  token: string
  userId: number
  identifier: string
}

export const rechargeApi = {
  sendCode: (identifier: string, fetchImpl?: FetchLike) =>
    rechargeFetch<{ devEchoCode?: string; expiresInMinutes: number }>(
      'POST', '/api/user/auth/send-code', { identifier }, fetchImpl),
  login: (identifier: string, code: string, fetchImpl?: FetchLike) =>
    rechargeFetch<LoginResult>('POST', '/api/user/auth/login',
      { identifier, code }, fetchImpl),
  skus: (fetchImpl?: FetchLike) =>
    rechargeFetch<Sku[]>('GET', '/api/user/auth/skus', undefined, fetchImpl),
  listKeys: (fetchImpl?: FetchLike) =>
    rechargeFetch<UserKeyItem[]>('GET', '/api/user/keys', undefined, fetchImpl),
  issueKey: (fetchImpl?: FetchLike) =>
    rechargeFetch<{ appKeyId: string; appSecret: string }>(
      'POST', '/api/user/keys', undefined, fetchImpl),
  resetSecret: (appKeyId: string, fetchImpl?: FetchLike) =>
    rechargeFetch<{ appKeyId: string; appSecret: string }>(
      'POST', `/api/user/keys/${encodeURIComponent(appKeyId)}/reset-secret`, undefined, fetchImpl),
  disableKey: (appKeyId: string, fetchImpl?: FetchLike) =>
    rechargeFetch<Record<string, unknown>>(
      'POST', `/api/user/keys/${encodeURIComponent(appKeyId)}/disable`, undefined, fetchImpl),
  createOrder: (channel: string, skuId?: string, customAmountFen?: number,
                fetchImpl?: FetchLike) =>
    rechargeFetch<OrderView>('POST', '/api/user/orders',
      { channel, skuId, customAmountFen }, fetchImpl),
  viewOrder: (orderNo: string, fetchImpl?: FetchLike) =>
    rechargeFetch<OrderView>('GET', `/api/user/orders/${encodeURIComponent(orderNo)}`,
      undefined, fetchImpl),
}

// ---- gateway DTO ----
export interface BalanceView {
  appKeyId: string
  balance: number
  frozen: number
}

export interface TransactionView {
  txId: string
  type: string
  taskId: string | null
  amount: number
  balanceAfter: number
  remark: string | null
  createdAt: string
}

export interface ArtifactView {
  artifactId: string
  type: string
  url: string
  sizeBytes: number | null
}

export interface ExecutionView {
  taskId: string
  status: string
  progress?: number
  skillCode?: string
  resolvedVersion?: string
  artifacts?: ArtifactView[]
  error?: { code: string; message: string } | null
  billing?: { pointsCharged?: number } | null
}

export const gatewayUserApi = {
  balance: () => gatewayFetch<BalanceView>('GET', '/api/v1/account/balance'),
  transactions: (pageNo = 1, pageSize = 20) =>
    gatewayFetch<{ total: number; items: TransactionView[] }>(
      'GET', `/api/v1/account/transactions?pageNo=${pageNo}&pageSize=${pageSize}`),
  execution: (taskId: string) =>
    gatewayFetch<ExecutionView>('GET', `/api/v1/executions/${encodeURIComponent(taskId)}`),
}
