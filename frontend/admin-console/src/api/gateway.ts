/**
 * gateway 接口封装（§5.2 Skill 管理 / §5.4 查询取消 / §5.7 账户与管理 / 市场）。
 * 类型与服务端 DTO 对齐（SkillDtos / ExecuteDtos / BillingViews / AppKeyAdminService）。
 */
import { createGatewayClient, buildQuery } from './client'
import { useCredentialsStore } from '../stores/credentials'
import { useAuthStore } from '../stores/auth'

// ---- 后台登录与用户管理类型（对齐 AdminAuthDtos）----
export interface LoginResult {
  token: string
  expiresInHours: number
  username: string
  displayName: string | null
  role: string
  roleName: string
  permissions: string[]
}

export interface AdminProfile {
  username: string
  displayName: string | null
  role: string
  roleName: string
  permissions: string[]
}

export interface UserView {
  id: number
  username: string
  displayName: string | null
  role: string
  roleName: string
  status: number
  lastLoginAt: string | null
}

export interface UserPage {
  total: number
  pageNo: number
  pageSize: number
  rows: UserView[]
}

export interface RoleView {
  roleCode: string
  roleName: string
  remark: string | null
  builtin: number
  permissions: string[]
}

export interface PermissionView {
  permCode: string
  permName: string
  groupName: string
  sortNo: number
}

// ---- 告警中心 / 模型明细类型（对齐 AlertRuleService / model_call 表）----
export interface AlertRuleView {
  id: number
  ruleCode: string
  ruleName: string
  ruleType: string
  skillCode: string | null
  thresholdValue: number | null
  severity: string
  channels: string
  dedupWindowSeconds: number
  enabled: number
}

export type AlertRulePayload = {
  ruleCode: string; ruleName: string; ruleType: string; skillCode?: string
  thresholdValue: number; severity?: string; channels?: string
  dedupWindowSeconds?: number; enabled?: number
}

export interface AlertRecordView {
  id: number
  ruleId: number | null
  ruleCode: string
  ruleType: string
  severity: string
  status: string
  taskId: string | null
  skillCode: string | null
  tenantId: string | null
  title: string
  content: string | null
  detail: string | null
  notified: number
  firstSeenAt: string
  lastSeenAt: string
  ackBy: string | null
  ackAt: string | null
  ackNote: string | null
}

export interface AlertRecordPage {
  total: number
  pageNo: number
  pageSize: number
  rows: AlertRecordView[]
}

export interface ModelCatalogEntry {
  provider: string
  label: string
  endpointHint: string
  models: string[]
}

export interface ModelCallView {
  id: number
  taskId: string
  seq: number | null
  kind: string
  alias: string | null
  modelName: string | null
  provider: string | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  costPoints: number | null
  durationMs: number | null
  status: string
  createdAt: string
}

export interface ArtifactContentView {
  artifactId: string
  type: string
  contentType: string | null
  sizeBytes: number | null
  content: string
  truncated: boolean
}

export interface SkillSummary {
  tenantId: string
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
  /** 审核人（V2.1 审计列） */
  reviewer?: string | null
  reviewedAt?: string | null
  rejectReason?: string | null
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
  /** MIME（V2.1 worker 落库推断；存量行 octet-stream） */
  contentType?: string | null
  /** 结构化类型（json/text/markdown/html）可在线预览 */
  previewable?: boolean
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

/** 执行事件链单条事件（技术方案 v2.0 §1.6，detail 已解析为对象） */
export interface ExecutionEventView {  taskId: string
  seq: number
  stage: string
  status: string
  service: string
  durationMs?: number | null
  traceId?: string | null
  occurredAt?: string | null
  detail?: Record<string, unknown> | null
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

/** 飞书告警配置（统一维护：sys_config 四键） */
export interface AlertConfigView {
  webhookUrl: string
  secret: string
  runningSeconds: number
  tokensThreshold: number
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
    getAuthToken: () => useAuthStore().session?.token || null,
    onUnauthorized: () => {
      useAuthStore().clear()
      window.location.assign('/login')
    },
  })

  return {
    // ---- 后台登录与用户管理（admin 面 JWT）----
    adminLogin: (username: string, password: string) =>
      client.plainJson<LoginResult>('POST', '/api/v1/admin/auth/login', { username, password }),
    adminMe: () =>
      client.adminJson<AdminProfile>('GET', '/api/v1/admin/auth/me'),
    adminChangePassword: (oldPassword: string, newPassword: string) =>
      client.adminJson<void>('PUT', '/api/v1/admin/auth/password',
        { oldPassword, newPassword }),
    adminListUsers: (keyword: string | undefined, pageNo: number, pageSize: number) =>
      client.adminJson<UserPage>('GET',
        '/api/v1/admin/users' + buildQuery({ keyword, pageNo, pageSize })),
    adminCreateUser: (payload: { username: string; password: string;
      displayName?: string; roleCode: string }) =>
      client.adminJson<UserView>('POST', '/api/v1/admin/users', payload),
    adminChangeUserRole: (username: string, roleCode: string) =>
      client.adminJson<UserView>('PUT',
        `/api/v1/admin/users/${encodeURIComponent(username)}/role`, { roleCode }),
    adminChangeUserStatus: (username: string, status: 0 | 1) =>
      client.adminJson<UserView>('PUT',
        `/api/v1/admin/users/${encodeURIComponent(username)}/status`, { status }),
    adminResetUserPassword: (username: string) =>
      client.adminJson<{ username: string; password: string }>('POST',
        `/api/v1/admin/users/${encodeURIComponent(username)}/reset-password`),
    adminListRoles: () =>
      client.adminJson<RoleView[]>('GET', '/api/v1/admin/roles'),
    adminListPermissions: () =>
      client.adminJson<PermissionView[]>('GET', '/api/v1/admin/permissions'),

    // ---- 告警中心（admin 面 JWT）----
    adminListAlertRules: () =>
      client.adminJson<AlertRuleView[]>('GET', '/api/v1/admin/alert-rules'),
    adminCreateAlertRule: (payload: AlertRulePayload) =>
      client.adminJson<AlertRuleView>('POST', '/api/v1/admin/alert-rules', payload),
    adminUpdateAlertRule: (id: number, payload: Partial<AlertRulePayload>) =>
      client.adminJson<AlertRuleView>('PUT', `/api/v1/admin/alert-rules/${id}`, payload),
    adminDeleteAlertRule: (id: number) =>
      client.adminJson<void>('DELETE', `/api/v1/admin/alert-rules/${id}`),
    adminUpdateAlertRuleStatus: (id: number, enabled: 0 | 1) =>
      client.adminJson<AlertRuleView>('PUT', `/api/v1/admin/alert-rules/${id}/status`,
        { enabled }),
    adminListAlertRecords: (opts: { status?: string; ruleType?: string; taskId?: string;
      skillCode?: string; pageNo: number; pageSize: number }) =>
      client.adminJson<AlertRecordPage>('GET', '/api/v1/admin/alert-records' + buildQuery({
        status: opts.status || undefined,
        ruleType: opts.ruleType || undefined,
        taskId: opts.taskId || undefined,
        skillCode: opts.skillCode || undefined,
        pageNo: opts.pageNo, pageSize: opts.pageSize,
      })),
    adminAckAlertRecord: (id: number, note?: string) =>
      client.adminJson<AlertRecordView>('PUT',
        `/api/v1/admin/alert-records/${id}/ack`, { note }),
    adminAlertPendingCount: () =>
      client.adminJson<{ count: number }>('GET', '/api/v1/admin/alert-records/pending-count'),

    // ---- 模型目录与连通性（V2.1）----
    adminModelCatalog: () =>
      client.adminJson<ModelCatalogEntry[]>('GET', '/api/v1/admin/model-catalog'),
    adminTestModelProvider: (payload: { alias?: string; endpoint?: string;
      modelName?: string; apiKey?: string }) =>
      client.adminJson<{ ok: boolean; latencyMs: number; httpStatus: number;
        message: string; sample: string }>('POST', '/api/v1/admin/model-providers/test',
        payload),

    // ---- 执行明细扩展（V2.1）----
    adminModelCalls: (taskId: string) =>
      client.adminJson<ModelCallView[]>('GET',
        `/api/v1/admin/executions/${encodeURIComponent(taskId)}/model-calls`),
    adminArtifactContent: (taskId: string, artifactId: string) =>
      client.adminJson<ArtifactContentView>('GET',
        `/api/v1/admin/executions/${encodeURIComponent(taskId)}/artifacts/`
        + `${encodeURIComponent(artifactId)}/content`),

    // ---- Skill 管理面（admin 面 JWT，按权限点分权）----
    adminListSkills: (opts: { tenantId?: string; skillCode?: string; visibility?: string;
      pageNo: number; pageSize: number }) =>
      client.adminJson<SkillPage>('GET', '/api/v1/admin/skills' + buildQuery({
        tenantId: opts.tenantId || undefined,
        skillCode: opts.skillCode || undefined,
        visibility: opts.visibility || undefined,
        pageNo: opts.pageNo,
        pageSize: opts.pageSize,
      })),
    adminSkillDetail: (tenantId: string, skillCode: string) =>
      client.adminJson<SkillDetail>('GET',
        `/api/v1/admin/skills/${encodeURIComponent(tenantId)}/${encodeURIComponent(skillCode)}`),
    adminUploadSkill: (tenantId: string, skillCode: string, fields: {
      version: string; name?: string; description?: string; visibility?: string
      requiredAbilities?: string; outputConfig?: string; pricingConfig?: string
      invocationSpec?: string; changelog?: string; publish?: string
    }, file: File) =>
      client.adminUpload<SkillVersionView>(
        `/api/v1/admin/skills/${encodeURIComponent(tenantId)}/${encodeURIComponent(skillCode)}/versions`,
        file, fields as Record<string, string>),
    adminReviewVersion: (tenantId: string, skillCode: string, version: string,
                         status: 1 | 2, reason?: string) =>
      client.adminJson<SkillVersionView>('PUT',
        `/api/v1/admin/skills/${encodeURIComponent(tenantId)}/${encodeURIComponent(skillCode)}`
        + `/versions/${encodeURIComponent(version)}/status`, { status, reason }),
    adminSetDefaultVersion: (tenantId: string, skillCode: string, version: string) =>
      client.adminJson<SkillSummary>('PUT',
        `/api/v1/admin/skills/${encodeURIComponent(tenantId)}/${encodeURIComponent(skillCode)}`
        + `/default-version`, { version }),
    adminSetVisibility: (tenantId: string, skillCode: string, visibility: 'PUBLIC' | 'PRIVATE') =>
      client.adminJson<SkillSummary>('PUT',
        `/api/v1/admin/skills/${encodeURIComponent(tenantId)}/${encodeURIComponent(skillCode)}`
        + `/visibility`, { visibility }),
    adminUpdateSkillConfig: (tenantId: string, skillCode: string,
                             outputConfig: string, pricingConfig: string) =>
      client.adminJson<SkillSummary>('PUT',
        `/api/v1/admin/skills/${encodeURIComponent(tenantId)}/${encodeURIComponent(skillCode)}`
        + `/config`, { outputConfig, pricingConfig }),
    adminDeleteSkill: (tenantId: string, skillCode: string) =>
      client.adminJson<void>('DELETE',
        `/api/v1/admin/skills/${encodeURIComponent(tenantId)}/${encodeURIComponent(skillCode)}`),

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

    adminAlertConfig: () =>
      client.adminJson<AlertConfigView>('GET', '/api/v1/admin/alert-config'),
    adminSaveAlertConfig: (config: { webhookUrl: string; secret: string;
      runningSeconds: number; tokensThreshold: number }) =>
      client.adminJson<AlertConfigView>('PUT', '/api/v1/admin/alert-config', config),
    adminTestAlertConfig: () =>
      client.adminJson<{ ok: boolean; message: string; response?: string }>(
        'POST', '/api/v1/admin/alert-config/test'),

    adminMetricsDashboard: (hours: number, topN = 5) =>
      client.adminJson<any>('GET', `/api/v1/admin/metrics/dashboard?hours=${hours}&topN=${topN}`),
    adminListExecutions: (hours: number, opts: {
      status?: string; skill?: string; tenant?: string; onlyFailed?: boolean;
      sortBy?: string; pageNo: number; pageSize: number
    }) =>
      client.adminJson<{ total: number; records: any[] }>('GET',
        '/api/v1/admin/executions' + buildQuery({
          hours,
          status: opts.status || undefined,
          skillCode: opts.skill,
          tenantId: opts.tenant,
          onlyFailed: opts.onlyFailed ? 'true' : undefined,
          sortBy: opts.sortBy || undefined,
          pageNo: opts.pageNo,
          pageSize: opts.pageSize,
        })),
    adminForceCancel: (taskId: string) =>
      client.adminJson<{ taskId: string; status: string; cancelled: boolean }>('POST',
        `/api/v1/admin/executions/${encodeURIComponent(taskId)}/force-cancel`),
    adminResendCallback: (taskId: string) =>
      client.adminJson<any>('POST', `/api/v1/admin/executions/${encodeURIComponent(taskId)}/callback-resend`),
    retryExecution: (taskId: string) =>
      client.signedJson<{ taskId: string; status: string }>('POST',
        `/api/v1/executions/${encodeURIComponent(taskId)}/retry`),
    adminGiveUpCallback: (taskId: string) =>
      client.adminJson<any>('POST', `/api/v1/admin/executions/${encodeURIComponent(taskId)}/callback-giveup`),
    adminExecutionDetail: (taskId: string) =>
      client.adminJson<ExecutionView>('GET', `/api/v1/admin/executions/${encodeURIComponent(taskId)}`),
    adminExecutionEvents: (taskId: string) =>
      client.adminJson<ExecutionEventView[]>('GET',
        `/api/v1/admin/executions/${encodeURIComponent(taskId)}/events`),
    adminExecutionInput: (taskId: string) =>
      client.adminJson<unknown>('GET', `/api/v1/admin/executions/${encodeURIComponent(taskId)}/input`),
    adminMetricsOverview: () =>
      client.adminJson<MetricsOverview>('GET', '/api/v1/admin/metrics/overview'),

    adminRecharge: (appKeyId: string, points: number, orderNo: string) =>
      client.adminJson<RechargeResult>('POST',
        '/api/v1/admin/credits/recharge', { appKeyId, points, orderNo }),
  }
})()
