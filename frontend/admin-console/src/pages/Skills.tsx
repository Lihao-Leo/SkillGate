import { useEffect, useRef, useState } from 'react'
import {
  Alert, App as AntdApp, Button, Card, Col, Drawer, Form, Input, InputNumber, Modal, Radio,
  Row, Select, Space, Switch, Table, Tag, Tooltip,
} from 'antd'
import { UploadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { gatewayApi } from '../api/gateway'
import type { SkillDetail, SkillPage, SkillSummary, SkillVersionView } from '../api/gateway'
import { hasPerm, useAuthStore } from '../stores/auth'
import { appKeyReady, useCredentialsStore } from '../stores/credentials'
import { usePrompt } from '../components/PromptModal'

const visibilityText: Record<string, string> = { PUBLIC: '公开', PRIVATE: '私有' }

/** pricingConfig JSON → 人类可读（如「3 点/次」「免费」「按量」） */
function pricingText(text: string | null | undefined): string {
  let pricing: any = {}
  try {
    pricing = JSON.parse(text ?? '') ?? {}
  } catch {
    pricing = {}
  }
  const mode = pricing.mode ?? pricing.model
  if (mode === 'FREE') return '免费'
  if (mode === 'METERED') return `按量（顶 ${pricing.capPoints ?? '-'} 点）`
  return `${pricing.points ?? 1} 点/次`
}

function parseJsonSafe(text: string | null | undefined): any {
  try {
    return JSON.parse(text ?? '') ?? {}
  } catch {
    return {}
  }
}

/** 执行设置 → outputConfig JSON（产出内容由 Skill 包自身决定，仅登记超时契约） */
function buildOutputConfig(timeoutSeconds: number): string {
  return JSON.stringify({ timeoutSeconds })
}

/** 计费设置 → pricingConfig JSON（§4.8：FREE / PER_EXECUTION(points)） */
function buildPricingConfig(mode: 'PER_EXECUTION' | 'FREE', points: number): string {
  if (mode === 'FREE') return JSON.stringify({ mode: 'FREE' })
  return JSON.stringify({ mode: 'PER_EXECUTION', points })
}

/** JSON 字段宽松校验：填了就必须是合法 JSON；合法返回原文，非法提示并返回 undefined */
function tryParseJson(text: string, label: string,
                      warning: (msg: string) => void): string | undefined {
  if (!text.trim()) return undefined
  try {
    JSON.parse(text)
    return text
  } catch {
    warning(`${label} 不是合法 JSON`)
    return undefined
  }
}

/** 计费配置校验（§4.8）：PER_EXECUTION 需 points>0 */
function validatePricingConfig(text: string): string | null {
  if (!text.trim()) return null
  try {
    const pricing = JSON.parse(text)
    const mode = pricing.mode ?? pricing.model
    if (mode === 'PER_EXECUTION' && !(Number(pricing.points) > 0)) {
      return '按次扣点需要大于 0 的点数'
    }
    return null
  } catch {
    return '计费配置不是合法 JSON'
  }
}

/** requiredAbilities：表单用逗号分隔书写，提交时转 JSON 数组（服务端要求合法 JSON） */
function abilitiesToJson(text: string): string | undefined {
  const items = text.split(/[,，\n]/).map((item) => item.trim()).filter(Boolean)
  return items.length === 0 ? undefined : JSON.stringify(items)
}

interface UploadState {
  tenantId: string
  skillCode: string
  version: string
  name: string
  description: string
  visibility: 'PRIVATE' | 'PUBLIC'
  requiredAbilities: string
  changelog: string
  publish: boolean
  timeoutSeconds: number
  pricingMode: 'PER_EXECUTION' | 'FREE'
  pricingPoints: number
  invocationSpec: string
}

const EMPTY_UPLOAD: UploadState = {
  tenantId: 'admin-console', skillCode: '', version: '', name: '', description: '',
  visibility: 'PRIVATE', requiredAbilities: '', changelog: '', publish: false,
  timeoutSeconds: 600, pricingMode: 'PER_EXECUTION', pricingPoints: 1, invocationSpec: '',
}

interface RunState {
  open: boolean
  skillCode: string
  version: string
  instructions: string
  context: string
  submitting: boolean
  taskId: string
  status: string
  progress: number | null
  artifacts: { artifactId: string; type: string; url: string }[]
  materials: { type: string; url: string; filename: string }[]
  uploading: boolean
  countable: boolean
  defaultCount: number
  maxCount: number
  count: number
}

const EMPTY_RUN: RunState = {
  open: false, skillCode: '', version: '', instructions: '', context: '',
  submitting: false, taskId: '', status: '', progress: null, artifacts: [],
  materials: [], uploading: false,
  countable: false, defaultCount: 1, maxCount: 1, count: 1,
}

export default function SkillsPage() {
  const { message, modal } = AntdApp.useApp()
  const session = useAuthStore((state) => state.session)
  const appKey = useCredentialsStore((state) => state.appKey)
  const secret = useCredentialsStore((state) => state.secret)
  const credsReady = appKeyReady({ appKey, secret })
  const [promptContextHolder, prompt] = usePrompt()

  const [detail, setDetail] = useState<SkillDetail | null>(null)
  const [detailOpen, setDetailOpen] = useState(false)
  const [configTimeout, setConfigTimeout] = useState(600)
  const [configPricingMode, setConfigPricingMode] = useState<'PER_EXECUTION' | 'FREE'>('PER_EXECUTION')
  const [configPricingPoints, setConfigPricingPoints] = useState(1)

  const [uploadOpen, setUploadOpen] = useState(false)
  const [upload, setUpload] = useState<UploadState>(EMPTY_UPLOAD)
  const [uploadFile, setUploadFile] = useState<File | null>(null)
  const [uploadSubmitting, setUploadSubmitting] = useState(false)
  const [modelAliases, setModelAliases] = useState<string[]>([])
  const fileRef = useRef<HTMLInputElement | null>(null)

  const [run, setRun] = useState<RunState>(EMPTY_RUN)
  const runTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const [query, setQuery] = useState({ tenantId: '', skillCode: '' })
  const [list, setList] = useState<SkillPage & { loading?: boolean }>({
    total: 0, pageNo: 1, pageSize: 20, items: [],
  })

  async function load(pageNo = 1, pageSize = 20) {
    setList((prev) => ({ ...prev, loading: true }))
    try {
      setList({
        ...(await gatewayApi.adminListSkills({
          tenantId: query.tenantId.trim() || undefined,
          skillCode: query.skillCode.trim() || undefined,
          pageNo,
          pageSize,
        })),
        pageNo,
        pageSize,
        loading: false,
      })
    } catch (e) {
      message.error((e as Error).message)
      setList((prev) => ({ ...prev, loading: false }))
    }
  }

  // 首次进入加载（StrictMode 双挂载安全：纯读操作）
  useEffect(() => {
    load(1, 20)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const perm = (code: string) => hasPerm(session, code)

  /** 试运行轮询：有 taskId 时每 2s 查一次，终态停止（useEffect cleanup，StrictMode 安全）。 */
  useEffect(() => {
    if (!run.taskId || !run.open) return
    const poll = async () => {
      try {
        const view = await gatewayApi.execution(run.taskId)
        setRun((prev) => ({
          ...prev,
          status: view.status,
          progress: view.progress ?? null,
          artifacts: view.artifacts ?? [],
        }))
        if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(view.status)) {
          if (runTimerRef.current) clearInterval(runTimerRef.current)
          runTimerRef.current = null
        }
      } catch { /* 轮询失败容忍 */ }
    }
    runTimerRef.current = setInterval(poll, 2000)
    return () => {
      if (runTimerRef.current) clearInterval(runTimerRef.current)
      runTimerRef.current = null
    }
  }, [run.taskId, run.open])

  async function openDetail(row: SkillSummary) {
    try {
      const result = await gatewayApi.adminSkillDetail(row.tenantId, row.skillCode)
      setDetail(result)
      const output = parseJsonSafe(result.skill.outputConfig)
      setConfigTimeout(Number(output.timeoutSeconds ?? 600))
      const pricing = parseJsonSafe(result.skill.pricingConfig)
      // 历史按量(METERED)配置在表单中按按次展示，保存后即转为按次扣点
      setConfigPricingMode((pricing.mode ?? pricing.model) === 'FREE' ? 'FREE' : 'PER_EXECUTION')
      setConfigPricingPoints(Number(pricing.points ?? pricing.capPoints ?? 1))
      setDetailOpen(true)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  /** 版本回滚 = 切换默认版本（秒级生效，§8.3） */
  async function makeDefault(version: string) {
    if (!detail) return
    const tenantId = detail.skill.tenantId
    const skillCode = detail.skill.skillCode
    const current = detail.skill.defaultVersion || '（无）'
    const ok = await new Promise<boolean>((resolve) => {
      modal.confirm({
        title: '版本回滚确认',
        content: `将「${skillCode}」默认版本从 ${current} 回滚到 ${version}？新执行立即解析到目标版本。`,
        okText: '回滚',
        cancelText: '取消',
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      })
    })
    if (!ok) return
    try {
      await gatewayApi.adminSetDefaultVersion(tenantId, skillCode, version)
      message.success(`默认版本已回滚至 ${version}`)
      setDetail(await gatewayApi.adminSkillDetail(tenantId, skillCode))
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function saveConfig() {
    if (!detail) return
    const outputConfig = JSON.stringify({ timeoutSeconds: configTimeout })
    const pricingConfig = configPricingMode === 'FREE'
      ? JSON.stringify({ mode: 'FREE' })
      : JSON.stringify({ mode: 'PER_EXECUTION', points: configPricingPoints })
    try {
      await gatewayApi.adminUpdateSkillConfig(detail.skill.tenantId,
        detail.skill.skillCode, outputConfig, pricingConfig)
      message.success('运营配置已更新（即时生效）')
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  function openUpload() {
    setUpload(EMPTY_UPLOAD)
    setUploadFile(null)
    if (fileRef.current) fileRef.current.value = ''
    setUploadOpen(true)
    gatewayApi.adminListModelProviders?.()
      .then((models) => setModelAliases(models.map((m) => m.alias)))
      .catch(() => setModelAliases([]))
  }

  function onFileChange(event: React.ChangeEvent<HTMLInputElement>) {
    const files = event.target.files
    setUploadFile(files && files.length > 0 ? files[0] : null)
  }

  async function submitUpload() {
    if (!upload.skillCode.trim() || !upload.version.trim()) {
      message.warning('skillCode 与 version 必填')
      return
    }
    if (!uploadFile) {
      message.warning('请选择 Skill 包（zip）')
      return
    }
    if (!uploadFile.name.toLowerCase().endsWith('.zip')) {
      message.warning('仅支持 zip 包')
      return
    }
    const spec = tryParseJson(upload.invocationSpec, '调用说明', message.warning)
    if (upload.invocationSpec.trim() && spec === undefined) return
    const pricingProblem = validatePricingConfig(
      buildPricingConfig(upload.pricingMode, upload.pricingPoints))
    if (pricingProblem) {
      message.warning(pricingProblem)
      return
    }
    setUploadSubmitting(true)
    try {
      const version = await gatewayApi.adminUploadSkill(upload.tenantId.trim(),
        upload.skillCode.trim(), {
          version: upload.version.trim(),
          name: upload.name.trim(),
          description: upload.description.trim(),
          visibility: upload.visibility,
          requiredAbilities: abilitiesToJson(upload.requiredAbilities) ?? '',
          outputConfig: buildOutputConfig(upload.timeoutSeconds),
          pricingConfig: buildPricingConfig(upload.pricingMode, upload.pricingPoints),
          invocationSpec: upload.invocationSpec.trim(),
          changelog: upload.changelog.trim(),
          publish: upload.publish ? 'true' : '',
        }, uploadFile)
      message.success(`已上传 ${upload.skillCode}@${version.version}（${upload.publish ? '已提交发布' : '待审核'}）`)
      setUploadOpen(false)
      load(list.pageNo, list.pageSize)
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setUploadSubmitting(false)
    }
  }

  /** 版本审核流转：通过(1)/驳回·废弃(2)；驳回需填原因（写审计列 reject_reason） */
  async function review(version: string, status: 1 | 2) {
    if (!detail) return
    const tenantId = detail.skill.tenantId
    const skillCode = detail.skill.skillCode
    let reason: string | undefined
    if (status === 2) {
      reason = (await prompt(`驳回 ${version}（原因写入审核记录）`,
        '如：产物结构与调用说明不符')) ?? undefined
    }
    try {
      await gatewayApi.adminReviewVersion(tenantId, skillCode, version, status, reason)
      message.success(`${version} ${status === 1 ? '审核通过并发布' : '已驳回/废弃'}（审核人：${session?.username}）`)
      setDetail(await gatewayApi.adminSkillDetail(tenantId, skillCode))
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function confirmDelete(row: SkillSummary) {
    const ok = await new Promise<boolean>((resolve) => {
      modal.confirm({
        title: '删除确认',
        content: `确定删除 Skill「${row.skillCode}」（租户 ${row.tenantId}）？将删除全部版本记录并清理对象存储中的包文件，不可恢复。`,
        okText: '删除',
        okButtonProps: { danger: true },
        cancelText: '取消',
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      })
    })
    if (!ok) return
    try {
      await gatewayApi.adminDeleteSkill(row.tenantId, row.skillCode)
      message.success('已删除')
      load(list.pageNo, list.pageSize)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function toggleVisibility(row: SkillSummary) {
    const target = row.visibility === 'PUBLIC' ? 'PRIVATE' : 'PUBLIC'
    const ok = await new Promise<boolean>((resolve) => {
      modal.confirm({
        title: target === 'PUBLIC' ? '上架确认' : '下架确认',
        content: target === 'PUBLIC'
          ? `将「${row.skillCode}」上架到公开市场？上架后所有租户均可查看并发起调用（按其定价扣点）。`
          : `将「${row.skillCode}」从公开市场下架？下架后其他租户无法再发起调用，历史任务不受影响。`,
        okText: target === 'PUBLIC' ? '确认上架' : '确认下架',
        okButtonProps: target === 'PUBLIC' ? {} : { danger: true },
        cancelText: '取消',
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      })
    })
    if (!ok) return
    try {
      await gatewayApi.adminSetVisibility(row.tenantId, row.skillCode,
        target as 'PUBLIC' | 'PRIVATE')
      message.success(target === 'PUBLIC' ? '已上架市场' : '已下架市场')
      load(list.pageNo, list.pageSize)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  /** 试运行入口（带租户上下文，走管理面详情取 outputConfig）。 */
  async function openRunRow(row: SkillSummary, version?: string) {
    setRun({ ...EMPTY_RUN, open: true, skillCode: row.skillCode, version: version ?? '' })
    try {
      const detailData = await gatewayApi.adminSkillDetail(row.tenantId, row.skillCode)
      if (detailData.skill.outputConfig) {
        const output = JSON.parse(detailData.skill.outputConfig)
        setRun((prev) => ({
          ...prev,
          countable: Boolean(output.countable),
          defaultCount: Number(output.defaultCount ?? 1),
          maxCount: Number(output.maxCount ?? output.defaultCount ?? 1),
          count: Number(output.defaultCount ?? 1),
        }))
      }
      setRun((prev) => ({
        ...prev,
        version: version ?? detailData.skill.defaultVersion ?? '',
      }))
    } catch { /* 数量信息缺失时按不可数处理 */ }
  }

  /** 素材 multipart 直传 → confirm 状态 → oss:// 引用（§5.1/§4.3） */
  async function onRunMaterial(event: React.ChangeEvent<HTMLInputElement>) {
    const files = event.target.files
    if (!files || files.length === 0) return
    setRun((prev) => ({ ...prev, uploading: true }))
    try {
      const material = await gatewayApi.uploadMaterial(files[0], 'document')
      setRun((prev) => ({
        ...prev,
        materials: [...prev.materials, {
          type: 'document', url: material.materialUrl, filename: material.filename,
        }],
      }))
      message.success(`素材已上传确认：${material.filename}`)
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setRun((prev) => ({ ...prev, uploading: false }))
      event.target.value = ''
    }
  }

  async function submitRun() {
    if (!run.instructions.trim() && !run.context.trim()) {
      message.warning('请填写任务指令或上下文 JSON')
      return
    }
    let context: unknown
    if (run.context.trim()) {
      try {
        context = JSON.parse(run.context)
      } catch {
        message.warning('上下文不是合法 JSON')
        return
      }
    }
    setRun((prev) => ({ ...prev, submitting: true }))
    try {
      const result = await gatewayApi.execute({
        skillCode: run.skillCode,
        version: run.version.trim() || undefined,
        count: run.countable ? run.count : undefined,
        clientRequestId: `console-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        instructions: run.instructions.trim() || undefined,
        context,
        materials: run.materials.map((m) => ({ type: m.type, url: m.url })),
      })
      setRun((prev) => ({ ...prev, taskId: result.taskId, status: result.status ?? 'PENDING' }))
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setRun((prev) => ({ ...prev, submitting: false }))
    }
  }

  const columns: ColumnsType<SkillSummary> = [
    { title: '租户', dataIndex: 'tenantId', width: 130, ellipsis: true },
    {
      title: 'Skill',
      render: (_, row) => (
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span
            style={{
              width: 36, height: 36, borderRadius: 10, flex: 'none',
              background: 'linear-gradient(135deg, #eef1fe, #e5e9ff)',
              color: '#3b5bf5', fontWeight: 700, display: 'grid', placeItems: 'center',
            }}
          >
            {(row.name ?? row.skillCode).slice(0, 1)}
          </span>
          <div>
            <div style={{ fontWeight: 600 }}>{row.name}</div>
            <div className="mono" style={{ fontSize: 12, color: 'var(--ant-color-text-tertiary)' }}>
              {row.skillCode}
            </div>
          </div>
        </div>
      ),
    },
    {
      title: '类型', width: 90,
      render: (_, row) => (
        <Tag color={row.kind === 'AGENT' ? 'warning' : 'processing'} style={{ margin: 0 }}>
          {row.kind === 'AGENT' ? '智能体' : '代码'}
        </Tag>
      ),
    },
    {
      title: '可见性', width: 90,
      render: (_, row) => (
        <Tag color={row.visibility === 'PUBLIC' ? 'success' : 'default'} style={{ margin: 0 }}>
          {visibilityText[row.visibility] ?? row.visibility}
        </Tag>
      ),
    },
    { title: '默认版本', dataIndex: 'defaultVersion', width: 100 },
    {
      title: '状态', width: 80,
      render: (_, row) => (
        <Tag color={row.status === 1 ? 'success' : 'error'} style={{ margin: 0 }}>
          {row.status === 1 ? '启用' : '禁用'}
        </Tag>
      ),
    },
    { title: '计费', width: 120, render: (_, row) => pricingText(row.pricingConfig) },
    {
      title: '操作', width: 220, fixed: 'right',
      render: (_, row) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={() => openDetail(row)}
                  data-testid={`detail-${row.skillCode}`}>详情</Button>
          <Tooltip title={credsReady ? '' : '试运行走业务面计费，需先在「业务凭证」绑定 AppKey'}>
            <span>
              <Button type="link" size="small" disabled={!credsReady}
                      onClick={() => openRunRow(row, row.defaultVersion)}
                      data-testid={`run-${row.skillCode}`}>试运行</Button>
            </span>
          </Tooltip>
          {perm('skill:publish') && (
            <Button type="text" size="small" danger={row.visibility === 'PUBLIC'}
                    onClick={() => toggleVisibility(row)}
                    data-testid={`shelf-${row.skillCode}`}>
              {row.visibility === 'PUBLIC' ? '下架' : '上架'}
            </Button>
          )}
          {perm('skill:delete') && (
            <Button type="text" danger size="small" onClick={() => confirmDelete(row)}
                    data-testid={`delete-${row.skillCode}`}>删除</Button>
          )}
        </Space>
      ),
    },
  ]

  const versionColumns: ColumnsType<SkillVersionView> = [
    { title: '版本', dataIndex: 'version', width: 80 },
    { title: 'sha256', dataIndex: 'packageSha256', ellipsis: true, className: 'mono' },
    { title: 'changelog', dataIndex: 'changelog', ellipsis: true },
    {
      title: '存储',
      width: 200,
      render: (_, row) => (
        <Tooltip title={row.packageUrl ?? row.ossKey ?? ''} placement="top">
          <span className="mono" data-testid={`store-${row.version}`}
                style={{
                  fontSize: 11, color: 'var(--ant-color-text-tertiary)', display: 'inline-block',
                  maxWidth: 190, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                }}>
            {row.packageUrl ?? row.ossKey ?? '-'}
          </span>
        </Tooltip>
      ),
    },
    {
      title: '状态',
      width: 88,
      render: (_, row) => {
        const tag = (
          <Tag
            style={{ margin: 0 }}
            color={row.status === 1 ? 'success' : row.status === 2 ? 'error' : 'default'}
          >
            {row.status === 1 ? '已发布' : row.status === 2 ? '已废弃' : '待审核'}
          </Tag>
        )
        return (row.rejectReason || row.reviewer) ? (
          <Tooltip placement="top"
                   title={`审核人：${row.reviewer ?? '-'}${row.rejectReason ? ' · 原因：' + row.rejectReason : ''}`}>
            <span>{tag}</span>
          </Tooltip>
        ) : tag
      },
    },
    {
      title: '操作',
      width: 220,
      render: (_, row) => (
        <Space size={4}>
          {row.status === 0 && perm('skill:review') && (
            <Button type="text" style={{ color: '#12b76a' }} size="small"
                    onClick={() => review(row.version, 1)}
                    data-testid={`approve-${row.version}`}>通过</Button>
          )}
          {row.status === 0 && perm('skill:review') && (
            <Button type="text" danger size="small" onClick={() => review(row.version, 2)}
                    data-testid={`reject-${row.version}`}>驳回</Button>
          )}
          {row.status === 1 && perm('skill:review') && (
            <Button type="text" danger size="small" onClick={() => review(row.version, 2)}
                    data-testid={`deprecate-${row.version}`}>废弃</Button>
          )}
          {row.version !== detail?.skill.defaultVersion && row.status === 1
            && perm('skill:rollback') && (
            <Tooltip title="秒级回滚：新执行立即解析到此版本" placement="top">
              <Button type="link" size="small" onClick={() => makeDefault(row.version)}
                      data-testid={`make-default-${row.version}`}>回滚到此版</Button>
            </Tooltip>
          )}
          {row.version === detail?.skill.defaultVersion && (
            <Tag color="processing" style={{ margin: 0 }}>默认</Tag>
          )}
        </Space>
      ),
    },
  ]

  return (
    <div>
      {promptContextHolder}
      <div className="page-header">
        <div>
          <h2>Skill 管理</h2>
          <p className="sub">Skill 元数据、版本审核（权限隔离）、回滚与运营配置</p>
        </div>
        <Space style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <Input placeholder="租户过滤" allowClear style={{ width: 150 }}
                 value={query.tenantId}
                 onChange={(e) => setQuery({ ...query, tenantId: e.target.value })}
                 onPressEnter={() => load(1)} />
          <Input placeholder="skillCode 过滤" allowClear style={{ width: 170 }}
                 value={query.skillCode}
                 onChange={(e) => setQuery({ ...query, skillCode: e.target.value })}
                 onPressEnter={() => load(1)} />
          <Button onClick={() => load(list.pageNo, list.pageSize)}>刷新</Button>
          <Button type="primary" icon={<UploadOutlined />} onClick={openUpload}
                  data-testid="open-upload" disabled={!perm('skill:upload')}>
            上传 Skill
          </Button>
        </Space>
      </div>
      <Card>
        <Table<SkillSummary>
          rowKey={(row) => `${row.tenantId}/${row.skillCode}`}
          columns={columns as ColumnsType<SkillSummary>}
          dataSource={list.items}
          loading={list.loading}
          pagination={{
            current: list.pageNo,
            pageSize: list.pageSize,
            total: list.total,
            showSizeChanger: false,
            showTotal: (total) => `共 ${total} 条`,
            onChange: (page, size) => load(page, size),
          }}
          data-testid="skills-table"
        />
      </Card>

      {/* 上传弹窗 */}
      <Modal
        open={uploadOpen}
        title="上传 Skill 包"
        width={640}
        onCancel={() => setUploadOpen(false)}
        footer={null}
        maskClosable={false}
      >
        <Form layout="vertical" data-testid="upload-form">
          <Row gutter={12}>
            <Col span={8}>
              <Form.Item label="租户" required>
                <Input data-testid="upload-tenant" value={upload.tenantId}
                       onChange={(e) => setUpload({ ...upload, tenantId: e.target.value })}
                       placeholder="上传到哪个租户" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="skillCode" required>
                <Input data-testid="upload-skillcode" value={upload.skillCode}
                       onChange={(e) => setUpload({ ...upload, skillCode: e.target.value })}
                       placeholder="如 video-gen" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="版本" required>
                <Input data-testid="upload-version" value={upload.version}
                       onChange={(e) => setUpload({ ...upload, version: e.target.value })}
                       placeholder="如 1.0.0" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item label="名称">
                <Input data-testid="upload-name" value={upload.name}
                       onChange={(e) => setUpload({ ...upload, name: e.target.value })} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item label="可见性">
                <Select data-testid="upload-visibility" style={{ width: '100%' }}
                        value={upload.visibility}
                        onChange={(v) => setUpload({ ...upload, visibility: v })}
                        options={[
                          { value: 'PRIVATE', label: '私有（PRIVATE）' },
                          { value: 'PUBLIC', label: '公开（PUBLIC）' },
                        ]} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item label="描述">
            <Input.TextArea data-testid="upload-description" rows={2} value={upload.description}
                            onChange={(e) => setUpload({ ...upload, description: e.target.value })} />
          </Form.Item>
          <Form.Item label="包文件" required>
            <input type="file" accept=".zip" onChange={onFileChange}
                   data-testid="upload-file" ref={fileRef} style={{ width: '100%' }} />
            <span className="upload-hint">
              ≤100MB；自动识别类型：含 main.py = 代码技能，含 SKILL.md =
              智能体技能（平台内置 runner 执行，包零改造）；sha256 由服务端计算登记
            </span>
          </Form.Item>
          <Form.Item label="所需能力">
            <Input data-testid="upload-abilities" value={upload.requiredAbilities}
                   onChange={(e) => setUpload({ ...upload, requiredAbilities: e.target.value })}
                   placeholder="可留空；如 llm.chat, video.generate（逗号分隔，自动转 JSON 数组）" />
            <span className="upload-hint">
              可留空——不影响上传与执行；声明后便于运营识别技能依赖。可用别名见「模型与能力」
              {modelAliases.length > 0 && `：${modelAliases.join(' / ')}`}
            </span>
          </Form.Item>
          <Form.Item label="执行超时">
            <InputNumber data-testid="upload-timeout" min={60} max={3600} step={60}
                         value={upload.timeoutSeconds}
                         onChange={(v) => setUpload({ ...upload, timeoutSeconds: v ?? 600 })} />
            <span className="upload-hint" style={{ marginLeft: 8 }}>
              秒；一般保持 600 即可，长任务可上传后在详情里调整
            </span>
          </Form.Item>
          <Form.Item label="计费模式">
            <Radio.Group data-testid="upload-pricing-mode" value={upload.pricingMode}
                         onChange={(e) => setUpload({ ...upload, pricingMode: e.target.value })}>
              <Radio.Button value="PER_EXECUTION">按次扣点</Radio.Button>
              <Radio.Button value="FREE">免费</Radio.Button>
            </Radio.Group>
          </Form.Item>
          {upload.pricingMode === 'PER_EXECUTION' && (
            <Form.Item label="每次扣点">
              <InputNumber data-testid="upload-pricing-points" min={1} value={upload.pricingPoints}
                           onChange={(v) => setUpload({ ...upload, pricingPoints: v ?? 1 })} />
              <span className="upload-hint" style={{ marginLeft: 8 }}>
                点（该 Skill 执行一次消耗多少积分）
              </span>
            </Form.Item>
          )}
          <Form.Item label="调用说明">
            <Input.TextArea rows={3} value={upload.invocationSpec}
                            onChange={(e) => setUpload({ ...upload, invocationSpec: e.target.value })}
                            placeholder="JSON（materials/instructions/context 契约，可留空）" />
          </Form.Item>
          <Form.Item label="changelog">
            <Input value={upload.changelog} placeholder="本版本变更说明"
                   onChange={(e) => setUpload({ ...upload, changelog: e.target.value })} />
          </Form.Item>
          <Form.Item label="提交发布">
            <Switch data-testid="upload-publish" checked={upload.publish}
                    onChange={(v) => setUpload({ ...upload, publish: v })} />
            <span className="upload-hint" style={{ marginLeft: 8 }}>
              开启则上传后直接提交审核发布
            </span>
          </Form.Item>
          <Form.Item>
            <Button type="primary" loading={uploadSubmitting} onClick={submitUpload}
                    data-testid="upload-submit">上传</Button>
          </Form.Item>
        </Form>
      </Modal>

      {/* 详情抽屉：版本列表 + 运营配置 */}
      <Drawer
        open={detailOpen}
        width={620}
        title={detail?.skill.name ?? '详情'}
        onClose={() => setDetailOpen(false)}
      >
        {detail && (
          <>
            <h4 className="drawer-title">
              版本列表 <span className="sub">sha256 / changelog / 审核状态</span>
            </h4>
            <Table
              size="small"
              rowKey="version"
              columns={versionColumns}
              dataSource={detail.versions}
              pagination={false}
              data-testid="versions-table"
            />
            <h4 className="drawer-title" style={{ marginTop: 22 }}>
              运营配置 <span className="sub">保存后即时生效</span>
            </h4>
            <Form layout="horizontal" labelCol={{ style: { width: 120 } }}>
              <Form.Item label="执行超时（秒）">
                <InputNumber min={60} max={3600} step={60} value={configTimeout}
                             onChange={(v) => setConfigTimeout(v ?? 600)} />
              </Form.Item>
              <Form.Item label="计费模式">
                <Radio.Group data-testid="config-pricing-mode" value={configPricingMode}
                             onChange={(e) => setConfigPricingMode(e.target.value)}>
                  <Radio.Button value="PER_EXECUTION">按次扣点</Radio.Button>
                  <Radio.Button value="FREE">免费分发</Radio.Button>
                </Radio.Group>
              </Form.Item>
              {configPricingMode === 'PER_EXECUTION' && (
                <Form.Item label="每次扣点">
                  <InputNumber min={1} value={configPricingPoints}
                               data-testid="config-pricing-points"
                               onChange={(v) => setConfigPricingPoints(v ?? 1)} />
                  <span className="upload-hint" style={{ marginLeft: 8 }}>
                    点（执行一次消耗多少积分）
                  </span>
                </Form.Item>
              )}
              <Form.Item>
                <Button type="primary" onClick={saveConfig} data-testid="save-config">
                  保存配置
                </Button>
              </Form.Item>
            </Form>
          </>
        )}
      </Drawer>

      {/* 试运行弹窗（走业务面计费，2s 轮询） */}
      <Modal
        open={run.open}
        title={`试运行 · ${run.skillCode}`}
        width={640}
        onCancel={() => setRun(EMPTY_RUN)}
        afterClose={() => {
          if (runTimerRef.current) clearInterval(runTimerRef.current)
          runTimerRef.current = null
        }}
        maskClosable={false}
        footer={null}
      >
        {!run.taskId ? (
          <Form layout="horizontal" labelCol={{ style: { width: 90 } }}>
            <Form.Item label="版本">
              <Input data-testid="run-version" value={run.version} placeholder="留空 = 默认版本"
                     onChange={(e) => setRun({ ...run, version: e.target.value })} />
            </Form.Item>
            {run.countable && (
              <Form.Item label="产出数量">
                <InputNumber data-testid="run-count" min={1} max={run.maxCount} value={run.count}
                             onChange={(v) => setRun({ ...run, count: v ?? 1 })} />
                <span className="upload-hint" style={{ marginLeft: 8 }}>最多 {run.maxCount}</span>
              </Form.Item>
            )}
            <Form.Item label="任务指令">
              <Input.TextArea data-testid="run-instructions" rows={3} value={run.instructions}
                              placeholder="本次要做什么（对应该技能 instructions）"
                              onChange={(e) => setRun({ ...run, instructions: e.target.value })} />
            </Form.Item>
            <Form.Item label="上下文">
              <Input.TextArea data-testid="run-context" rows={3} value={run.context}
                              placeholder='JSON（可选），如 {"business":"母婴零售","members":12000}'
                              onChange={(e) => setRun({ ...run, context: e.target.value })} />
            </Form.Item>
            <Form.Item label="素材">
              <div style={{ width: '100%' }}>
                <input type="file" disabled={run.uploading} onChange={onRunMaterial}
                       data-testid="run-material" />
                {run.materials.length > 0 && (
                  <div data-testid="run-materials" style={{ marginTop: 6 }}>
                    {run.materials.map((m, i) => (
                      <Tag key={i} closable onClose={() => setRun((prev) => ({
                        ...prev,
                        materials: prev.materials.filter((_, index) => index !== i),
                      }))}>{m.filename}</Tag>
                    ))}
                  </div>
                )}
              </div>
            </Form.Item>
            <Form.Item>
              <Button type="primary" loading={run.submitting} onClick={submitRun}
                      data-testid="run-submit">执行</Button>
            </Form.Item>
          </Form>
        ) : (
          <>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <span>
                taskId：<span className="mono" data-testid="run-taskid">{run.taskId}</span>
              </span>
              <Tag color={run.status === 'SUCCEEDED' ? 'success'
                : run.status === 'FAILED' ? 'error' : 'processing'}>
                {run.status}{run.progress != null ? ` ${run.progress}%` : ''}
              </Tag>
            </div>
            <Alert
              style={{ marginTop: 12 }}
              type={run.status === 'SUCCEEDED' ? 'success' : 'error'}
              showIcon
              message={run.status === 'SUCCEEDED'
                ? '执行完成，产物已登记（可在执行监控按 taskId 查询详情）'
                : '执行失败，详情见执行监控'}
              data-testid="run-terminal"
            />
            <div data-testid="run-artifacts" style={{ marginTop: 12 }}>
              {run.artifacts.map((artifact) => (
                <div key={artifact.artifactId}>
                  <a href={artifact.url} target="_blank" rel="noopener noreferrer">
                    {artifact.artifactId}（{artifact.type}）
                  </a>
                </div>
              ))}
            </div>
          </>
        )}
      </Modal>
    </div>
  )
}
