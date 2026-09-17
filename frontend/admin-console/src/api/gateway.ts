/**
 * gateway 接口封装（§5.2 Skill 管理 / §5.4 查询取消 / §5.7 账户与管理 / 市场）。
 * 类型与服务端 DTO 对齐（SkillDtos / ExecuteDtos / BillingViews / AppKeyAdminService）。
 */
import { createGatewayClient, buildQuery } from './client'
import { useCredentialsStore } from '../stores/credentials'

export interface SkillSummary {
  skillCode: string
  name: string
  description: string
  visibility: string
  /** 包类型：CODE=main.py 代码技能 / AGENT=SKILL.md 智能体技能 */
  kind: string | null
  defaultVersion: string
  status: number | null
  pricingConfig: string | null
  outputConfig: string | null
}

export interface SkillVersionView {
  version: string
  packageSha256: string
  packageSize: number | null
  status: number | null
  changelog: string | null
  uploadedAt: string | null
  /** 桶内相对路径 */
  ossKey: string | null
  /** 对象绝对访问地址（bucket endpoint + key） */
  packageUrl: string | null
}

export interface SkillDetail {
  skill: SkillSummary
  versions: SkillVersionView[]
}

export interface SkillPage {
  total: number
  pageNo: number
  pageSize: number
  items: SkillSummary[]
}

export interface PackageDownloadView {
  version: string
  packageSha256: string
  downloadUrl: string
  expiresAt: string
}

export interface MarketplaceItem {
  skillCode: string
  name: string
  description: string
  defaultVersion: string
  pricingConfig: string | null
}

export interface ExecuteResult {
  taskId: string
  resolvedVersion?: string
  count?: number
  status?: string
  idempotentHit?: boolean
}

export interface ExecutionPage {
  total: number
  pageNo: number
  pageSize: number
  records: ExecutionView[]
}

export interface MaterialView {
  materialId: string
  materialType: string
  ossKey?: string
  /** 完整 oss:// 引用（execute materials 直接使用） */
  materialUrl: string
  status: number
  filename: string
  fileSize: number
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
  /** 服务端字段名 resolvedVersion（§5.4 查询响应） */
  resolvedVersion?: string
  callbackStatus?: string
  callbackUrl?: string
  modelCalls?: number
  tokensUsed?: number
  durationMs?: number
  artifacts?: ArtifactView[]
  error?: { code: string; message: string } | null
  billing?: { mode?: string; pointsCharged?: number; refundedPoints?: number;
    holdId?: string; settled?: boolean } | null
  context?: unknown
}

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

export interface TransactionPage {
  total: number
  pageNo: number
  pageSize: number
  items: TransactionView[]
}

export interface AdminKeyItem {
  appKeyId: string
  tenantId: string
  quota?: Record<string, unknown> | null
  status: number
  balance?: number
  createdAt?: string
}

export interface AdminKeyPage {
  total: number
  pageNo: number
  pageSize: number
  items: AdminKeyItem[]
}

export interface ModelProviderView {
  alias: string
  provider: string
  modelName: string
  endpoint: string
  hasApiKey: boolean
  fallbackAlias: string | null
  maxQps: number
  costPer1kInputTokens: string | null
  costPer1kOutputTokens: string | null
  costPerCall: string | null
  status: number
}

export interface KbView {
  kbId: string
  tenantId: string
  name: string
  embeddingAlias: string
  milvusCollection: string
  status: number
}

export interface KbDocumentView {
  docId: string
  kbId: string
  ossKey: string
  chunkCount: number
  status: number
  createdAt: string | null
}

export interface MetricsOverview {
  pending: number
  running: number
  succeededToday: number
  failedToday: number
  successRateToday: number | null
  pendingAgeP95Minutes: number
  insufficientPointsToday: number
  rateLimitedToday: number
}

export interface RechargeResult {
  appKeyId: string
  balance: number
  frozen: number
  alreadyCredited: boolean
}

export const gatewayApi = (() => {
  const client = createGatewayClient({
    getCredentials: () => {
      const store = useCredentialsStore()
      return store.appKeyReady ? { appKey: store.appKey, secret: store.secret, adminToken: store.adminToken } : null
    },
    getAdminToken: () => {
      const store = useCredentialsStore()
      return store.adminReady ? store.adminToken : null
    },
  })

  return {
    // ---- Skill 管理（业务面 HMAC）----
    listSkills: (pageNo: number, pageSize: number) =>
      client.signedJson<SkillPage>('GET', `/api/v1/skills${buildQuery({ pageNo, pageSize })}`),
    uploadSkill: (fields: {
      skillCode: string; version: string; name?: string; description?: string
      visibility?: string; requiredAbilities?: string; outputConfig?: string
      pricingConfig?: string; invocationSpec?: string; changelog?: string; publish?: string
    }, file: File) =>
      client.signedUpload<SkillVersionView>('/api/v1/skills/upload',
        fields as Record<string, string>, file),
    reviewVersion: (skillCode: string, version: string, status: 0 | 1 | 2) =>
      client.signedJson<SkillVersionView>('PUT',
        `/api/v1/skills/${encodeURIComponent(skillCode)}/versions/${encodeURIComponent(version)}/status`,
        { status }),
    setVisibility: (skillCode: string, visibility: 'PUBLIC' | 'PRIVATE') =>
      client.signedJson<SkillSummary>('PUT',
        `/api/v1/skills/${encodeURIComponent(skillCode)}/visibility`, { visibility }),
    uploadMaterial: (file: File, materialType: string) =>
      client.signedUpload<MaterialView>('/api/v1/materials/upload',
        { materialType }, file),
    execute: (payload: {
      skillCode: string; version?: string; count?: number
      clientRequestId: string; instructions?: string; context?: unknown
      materials?: { type: string; url: string; content?: string }[]
    }) => client.signedJson<ExecuteResult>('POST', '/api/v1/execute', payload),
    listExecutions: (status: string | undefined, pageNo: number, pageSize: number) =>
      client.signedJson<ExecutionPage>('GET',
        `/api/v1/executions${buildQuery({ status, pageNo, pageSize })}`),
    deleteSkill: (skillCode: string) =>
      client.signedJson<void>('DELETE', `/api/v1/skills/${encodeURIComponent(skillCode)}`),
    skillDetail: (skillCode: string) =>
      client.signedJson<SkillDetail>('GET', `/api/v1/skills/${encodeURIComponent(skillCode)}`),
    setDefaultVersion: (skillCode: string, version: string) =>
      client.signedJson<SkillSummary>('PUT',
        `/api/v1/skills/${encodeURIComponent(skillCode)}/default-version`, { version }),
    updateSkillConfig: (skillCode: string, outputConfig?: string, pricingConfig?: string) =>
      client.signedJson<SkillSummary>('PUT',
        `/api/v1/skills/${encodeURIComponent(skillCode)}/config`, { outputConfig, pricingConfig }),
    invocationSpec: (skillCode: string, format: 'markdown' | 'openapi' | 'tool-schema') =>
      client.signedJson<string>('GET',
        `/api/v1/skills/${encodeURIComponent(skillCode)}/invocation-spec${buildQuery({ format })}`),
    downloadPackage: (skillCode: string, version?: string) =>
      client.signedJson<PackageDownloadView>('GET',
        `/api/v1/skills/${encodeURIComponent(skillCode)}/package${buildQuery({ version })}`),
    marketplace: (pageNo: number, pageSize: number) =>
      client.signedJson<{ total: number; items: MarketplaceItem[] }>('GET',
        `/api/v1/marketplace/skills${buildQuery({ pageNo, pageSize })}`),

    // ---- 执行查询 / 取消（业务面 HMAC）----
    execution: (taskId: string) =>
      client.signedJson<ExecutionView>('GET', `/api/v1/executions/${encodeURIComponent(taskId)}`),
    cancelExecution: (taskId: string) =>
      client.signedJson<{ taskId: string; status: string; cancelled: boolean }>('POST',
        `/api/v1/executions/${encodeURIComponent(taskId)}/cancel`),

    // ---- 账户（业务面 HMAC）----
    balance: () => client.signedJson<BalanceView>('GET', '/api/v1/account/balance'),
    transactions: (taskId?: string, pageNo = 1, pageSize = 20) =>
      client.signedJson<TransactionPage>('GET',
        `/api/v1/account/transactions${buildQuery({ taskId, pageNo, pageSize })}`),

    // ---- AppKey 管理 / 点数入账（管理面 X-Admin-Token）----
    adminListKeys: (tenantId?: string, pageNo = 1, pageSize = 20) =>
      client.adminJson<AdminKeyPage>('GET',
        `/api/v1/admin/app-keys${buildQuery({ tenantId, pageNo, pageSize })}`),
    adminIssueKey: (tenantId: string, quota?: string, callbackDomains?: string) =>
      client.adminJson<{ appKeyId: string; appSecret: string }>('POST',
        '/api/v1/admin/app-keys', { tenantId, quota, callbackDomains }),
    adminResetSecret: (appKeyId: string) =>
      client.adminJson<{ appKeyId: string; appSecret: string }>('POST',
        `/api/v1/admin/app-keys/${encodeURIComponent(appKeyId)}/reset-secret`),
    adminUpdateKeyStatus: (appKeyId: string, status: 0 | 1) =>
      client.adminJson<Record<string, unknown>>('PUT',
        `/api/v1/admin/app-keys/${encodeURIComponent(appKeyId)}/status`, { status }),
    adminUpsertModelProvider: (payload: {
      alias: string; provider: string; modelName: string; endpoint?: string
      apiKey?: string; fallbackAlias?: string; maxQps?: number
      costPer1kInput?: string; costPer1kOutput?: string; costPerCall?: string; status?: number
    }) => client.adminJson<ModelProviderView>('POST', '/api/v1/admin/model-providers', payload),
    adminListModelProviders: () =>
      client.adminJson<ModelProviderView[]>('GET', '/api/v1/admin/model-providers'),
    adminDeleteModelProvider: (alias: string) =>
      client.adminJson<void>('DELETE', `/api/v1/admin/model-providers/${encodeURIComponent(alias)}`),
    adminModelProviderStatus: (alias: string, status: 0 | 1) =>
      client.adminJson<ModelProviderView>('PUT',
        `/api/v1/admin/model-providers/${encodeURIComponent(alias)}/status`, { status }),

    adminListKbs: () => client.adminJson<KbView[]>('GET', '/api/v1/admin/kbs'),
    adminCreateKb: (tenantId: string, name: string, embeddingAlias?: string) =>
      client.adminJson<KbView>('POST', '/api/v1/admin/kbs',
        { tenantId, name, embeddingAlias }),
    adminDeleteKb: (kbId: string) =>
      client.adminJson<void>('DELETE', `/api/v1/admin/kbs/${encodeURIComponent(kbId)}`),
    adminListKbDocuments: (kbId: string) =>
      client.adminJson<KbDocumentView[]>('GET',
        `/api/v1/admin/kbs/${encodeURIComponent(kbId)}/documents`),
    adminAddKbDocument: (kbId: string, file: File) =>
      client.adminUpload<KbDocumentView>(
        `/api/v1/admin/kbs/${encodeURIComponent(kbId)}/documents`, file),
    adminIngestKbDocument: (kbId: string, docId: string) =>
      client.adminJson<{ docId: string; chunkCount: number; indexed: boolean; message?: string }>(
        'POST', `/api/v1/admin/kbs/${encodeURIComponent(kbId)}/documents/${encodeURIComponent(docId)}/ingest`),
    adminDeleteKbDocument: (kbId: string, docId: string) =>
      client.adminJson<void>('DELETE',
        `/api/v1/admin/kbs/${encodeURIComponent(kbId)}/documents/${encodeURIComponent(docId)}`),

    adminSysConfig: () =>
      client.adminJson<Record<string, string>>('GET', '/api/v1/admin/sys-config'),
    adminUpdateSysConfig: (items: Record<string, string>) =>
      client.adminJson<Record<string, string>>('PUT', '/api/v1/admin/sys-config', items),

    adminMetricsDashboard: (hours: number) =>
      client.adminJson<any>('GET', `/api/v1/admin/metrics/dashboard?hours=${hours}`),
    adminListExecutions: (hours: number, status: string, pageNo: number, pageSize: number) =>
      client.adminJson<{ total: number; records: any[] }>('GET',
        `/api/v1/admin/executions?hours=${hours}&status=${status}&pageNo=${pageNo}&pageSize=${pageSize}`),
    adminResendCallback: (taskId: string) =>
      client.adminJson<any>('POST', `/api/v1/admin/executions/${encodeURIComponent(taskId)}/callback-resend`),
    retryExecution: (taskId: string) =>
      client.signedJson<{ taskId: string; status: string }>('POST',
        `/api/v1/executions/${encodeURIComponent(taskId)}/retry`),
    adminGiveUpCallback: (taskId: string) =>
      client.adminJson<any>('POST', `/api/v1/admin/executions/${encodeURIComponent(taskId)}/callback-giveup`),
    adminMetricsOverview: () =>
      client.adminJson<MetricsOverview>('GET', '/api/v1/admin/metrics/overview'),

    adminRecharge: (appKeyId: string, points: number, orderNo: string) =>
      client.adminJson<RechargeResult>('POST',
        '/api/v1/admin/credits/recharge', { appKeyId, points, orderNo }),
  }
})()
