import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Alert, App as AntdApp, Button, Card, Checkbox, Descriptions, Drawer, Input, Modal,
  Progress, Select, Space, Table, Tabs, Tag,
} from 'antd'
import type { ColumnsType, TablePaginationConfig } from 'antd/es/table'
import { SearchOutlined } from '@ant-design/icons'
import { gatewayApi } from '../api/gateway'
import type { ExecutionEventView, ExecutionView } from '../api/gateway'
import ExecutionTimeline from '../components/ExecutionTimeline'
import StagePipeline from '../components/StagePipeline'
import ModelCallTable from '../components/ModelCallTable'
import ArtifactPreviewModal from '../components/ArtifactPreviewModal'
import type { ArtifactView } from '../api/gateway'

type Range = 24 | 168 | 720

const statusMeta: Record<string, { label: string; color: string }> = {
  PENDING: { label: 'PENDING', color: '#98a2b3' },
  RUNNING: { label: 'RUNNING', color: '#2b7fff' },
  CANCELLING: { label: 'CANCELLING', color: '#f79009' },
  SUCCEEDED: { label: 'SUCCEEDED', color: '#12b76a' },
  FAILED: { label: 'FAILED', color: '#f04438' },
  CANCELLED: { label: 'CANCELLED', color: '#98a2b3' },
}

function fmtDuration(ms: number | null | undefined): string {
  if (!ms) return '—'
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s}s`
  return `${Math.floor(s / 60)}m${String(s % 60).padStart(2, '0')}s`
}

function isTerminal(status: string): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELLED'
}

/**
 * 执行监控（V2.2 瘦身版）：聚焦任务定位与干预。
 * 运行健康/消耗趋势等看板内容已上移至「总览」首页，避免重复。
 */
export default function ExecutionsPage() {
  const navigate = useNavigate()
  const { message, modal } = AntdApp.useApp()

  const [range, setRangeState] = useState<Range>(24)
  const [recent, setRecent] = useState<{ pageNo: number; pageSize: number; items: any[]; total: number }>(
    { pageNo: 1, pageSize: 10, items: [], total: 0 })
  const [recentLoading, setRecentLoading] = useState(false)
  const [filters, setFilters] = useState({
    status: '', skill: '', tenant: '', onlyFailed: false, sortBy: '',
  })

  // ---------- 详情抽屉 ----------
  const [taskIdInput, setTaskIdInput] = useState('')
  const [execution, setExecution] = useState<ExecutionView | null>(null)
  const [loading, setLoading] = useState(false)
  const [events, setEvents] = useState<ExecutionEventView[]>([])
  const [preview, setPreview] = useState<{ taskId: string; artifact: ArtifactView } | null>(null)
  const [inputVisible, setInputVisible] = useState(false)
  const [inputLoading, setInputLoading] = useState(false)
  const [inputJson, setInputJson] = useState('')
  const refreshTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)

  async function loadRecent(rangeValue: Range, currentFilters: typeof filters,
                            pageNo: number, pageSize: number) {
    setRecentLoading(true)
    try {
      const page = await gatewayApi.adminListExecutions(rangeValue, {
        status: currentFilters.onlyFailed ? '' : currentFilters.status,
        skill: currentFilters.skill.trim() || undefined,
        tenant: currentFilters.tenant.trim() || undefined,
        onlyFailed: currentFilters.onlyFailed,
        sortBy: currentFilters.sortBy || undefined,
        pageNo,
        pageSize,
      })
      setRecent({ pageNo, pageSize, items: page.records, total: page.total })
    } catch {
      setRecent((prev) => ({ ...prev, items: [] }))
    } finally {
      setRecentLoading(false)
    }
  }

  /** 筛选变更统一入口：合并 patch 后立即加载（第 1 页）。 */
  function applyWith(patch: Partial<typeof filters>) {
    const next = { ...filters, ...patch }
    setFilters(next)
    loadRecent(range, next, 1, recent.pageSize)
  }

  /** 表头排序；翻页（无 sorter.order）保留排序与页码。 */
  function onTableChange(pagination: TablePaginationConfig, _filters: unknown, sorter: any) {
    const field = String(sorter?.field ?? '')
    if (field === 'durationMs' || field === 'tokensUsed') {
      const sortBy = sorter?.order
        ? field === 'durationMs' ? 'duration' : 'tokens'
        : ''
      applyWith({ sortBy })
      return
    }
    loadRecent(range, filters, pagination.current ?? 1, pagination.pageSize ?? recent.pageSize)
  }

  /** 管理台强制终止：立即杀沙箱，按已发生用量结算/退款（worker 不可达时由看门狗兜底收殓） */
  async function forceCancelTask(taskIdToCancel: string) {
    const ok = await new Promise<boolean>((resolve) => {
      modal.confirm({
        title: '强制终止',
        content: '立即终止该任务：沙箱将被杀死，已发生用量（模型 token 等）正常结算，未消耗冻结点数全额退还。确认强制终止？',
        okText: '强制终止',
        okButtonProps: { danger: true },
        cancelText: '取消',
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      })
    })
    if (!ok) return
    try {
      const result = await gatewayApi.adminForceCancel(taskIdToCancel)
      message.success(result.cancelled ? '已受理强制终止' : `当前状态 ${result.status} 不可终止`)
      if (execution?.taskId === taskIdToCancel) await queryById(taskIdToCancel)
      await loadRecent(range, filters, recent.pageNo, recent.pageSize)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  useEffect(() => {
    loadRecent(range, filters, 1, recent.pageSize)
    return () => stopRefresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // ---------- 任务详情（右侧抽屉） ----------
  async function queryById(id: string) {
    setLoading(true)
    setExecution(null)
    try {
      // 后管为全平台视角：详情走 admin 端点（无租户过滤），与任务列表口径一致
      const view = await gatewayApi.adminExecutionDetail(id)
      setExecution(view)
      await loadEvents(id)
      armRefresh(id, view.status)
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setLoading(false)
    }
  }

  function stopRefresh() {
    if (refreshTimerRef.current) {
      clearInterval(refreshTimerRef.current)
      refreshTimerRef.current = null
    }
  }

  async function loadEvents(id: string) {
    try {
      setEvents(await gatewayApi.adminExecutionEvents(id))
    } catch {
      setEvents([])
    }
  }

  /** 非终态任务每 5s 刷新状态与事件链；终态即停（useEffect cleanup，StrictMode 安全）。 */
  function armRefresh(id: string, status: string) {
    stopRefresh()
    if (status !== 'PENDING' && status !== 'RUNNING' && status !== 'CANCELLING') return
    refreshTimerRef.current = setInterval(async () => {
      try {
        const view = await gatewayApi.adminExecutionDetail(id)
        setExecution(view)
        await loadEvents(id)
        if (isTerminal(view.status)) stopRefresh()
      } catch { /* 查询抖动忽略 */ }
    }, 5000)
  }

  function query() {
    if (!taskIdInput.trim()) return
    queryById(taskIdInput.trim())
  }

  function pick(row: any) {
    setTaskIdInput(row.taskId)
    queryById(row.taskId)
  }

  /** 失败任务重试：按原入参重放一次完整受理（新 taskId） */
  async function retryTask(taskIdToRetry: string) {
    try {
      const result = await gatewayApi.retryExecution(taskIdToRetry)
      message.success(`已重新受理：${result.taskId}`)
      setTaskIdInput(result.taskId)
      await queryById(result.taskId)
      await loadRecent(range, filters, recent.pageNo, recent.pageSize)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function showInput() {
    if (!execution) return
    setInputVisible(true)
    setInputLoading(true)
    try {
      const data = await gatewayApi.adminExecutionInput(execution.taskId)
      setInputJson(JSON.stringify(data, null, 2))
    } catch (e) {
      setInputVisible(false)
      message.error((e as Error).message)
    } finally {
      setInputLoading(false)
    }
  }

  async function copyInput() {
    try {
      await navigator.clipboard.writeText(inputJson)
      message.success('已复制入参 JSON')
    } catch {
      message.error('复制失败（浏览器剪贴板权限）')
    }
  }

  // ---------- 异常处理台（回调死信） ----------
  const deadLetters = recent.items.filter((i) => i.callbackStatus === 'FAILED')

  async function resendCallback(taskIdToResend: string) {
    try {
      await gatewayApi.adminResendCallback(taskIdToResend)
      message.success('已重投回调')
      await loadRecent(range, filters, recent.pageNo, recent.pageSize)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function giveUpCallback(taskIdToGiveUp: string) {
    try {
      await gatewayApi.adminGiveUpCallback(taskIdToGiveUp)
      message.success('已放弃补投')
      await loadRecent(range, filters, recent.pageNo, recent.pageSize)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  // ---------- 列表列 ----------
  const recentColumns: ColumnsType<any> = [
    { title: 'taskId', dataIndex: 'taskId', width: 250, className: 'mono' },
    { title: '租户', dataIndex: 'tenantId', width: 140 },
    { title: 'Skill', dataIndex: 'skillCode', width: 170 },
    {
      title: '状态', width: 110,
      render: (_, row) => (
        <span style={{ color: statusMeta[row.status]?.color ?? '#98a2b3', fontWeight: 600 }}>
          ● {row.status}
        </span>
      ),
    },
    {
      title: '进度', width: 110,
      render: (_, row) => row.progress != null
        ? <Progress percent={row.progress} size="small" strokeWidth={7} />
        : row.status === 'PENDING' ? <span>排队中</span>
        : row.errorCode ? <span className="neg">{row.errorCode}</span>
        : <span>—</span>,
    },
    {
      title: '时长', dataIndex: 'durationMs', width: 90, sorter: true,
      render: (v: number | null) => fmtDuration(v),
    },
    {
      title: 'tokens', dataIndex: 'tokensUsed', width: 100, sorter: true,
      render: (v: number | null) => v?.toLocaleString() ?? '—',
    },
    {
      title: '操作', width: 160, fixed: 'right',
      render: (_, row) => (
        <Space size={4}>
          <Button type="link" size="small"
                  onClick={(e) => { e.stopPropagation(); pick(row) }}>详情</Button>
          {row.status === 'FAILED' && (
            <Button type="link" size="small"
                    onClick={(e) => { e.stopPropagation(); retryTask(row.taskId) }}>重试</Button>
          )}
          {(row.status === 'RUNNING' || row.status === 'PENDING' || row.status === 'CANCELLING') && (
            <Button type="link" danger size="small"
                    onClick={(e) => { e.stopPropagation(); forceCancelTask(row.taskId) }}>终止</Button>
          )}
        </Space>
      ),
    },
  ]

  const deadColumns: ColumnsType<any> = [
    { title: 'taskId', dataIndex: 'taskId', width: 250, className: 'mono' },
    { title: 'Skill', dataIndex: 'skillCode', width: 170 },
    { title: '回调地址', dataIndex: 'callbackUrl', ellipsis: true },
    {
      title: '操作', width: 140,
      render: (_, row) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={() => resendCallback(row.taskId)}>重发</Button>
          <Button type="link" danger size="small" onClick={() => giveUpCallback(row.taskId)}>放弃</Button>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <div className="page-header">
        <div>
          <h2>执行监控</h2>
          <p className="sub">skill-execute 任务定位与干预 · 运行健康/消耗趋势见「总览」</p>
        </div>
        <div className="range-pills" style={{ display: 'flex', gap: 4 }}>
          {([24, 168, 720] as Range[]).map((r) => (
            <button
              key={r}
              className={`pill${range === r ? ' active' : ''}`}
              data-testid={`range-${r}`}
              onClick={() => {
                setRangeState(r)
                loadRecent(r, filters, recent.pageNo, recent.pageSize)
              }}
            >
              {r === 24 ? '今日' : r === 168 ? '近 7 天' : '近 30 天'}
            </button>
          ))}
          <button className="pill" data-testid="wall-link"
                  onClick={() => navigate('/wall')}>📺 大屏模式</button>
        </div>
      </div>

      <Card>
        <Tabs
          items={[
            {
              key: 'list',
              label: `任务列表（${recent.total}）`,
              children: (
                <>
                  <div className="filter-bar">
                    <Space wrap>
                      <Select
                        value={filters.status || undefined}
                        placeholder="状态"
                        allowClear
                        style={{ width: 130 }}
                        disabled={filters.onlyFailed}
                        onChange={(v) => applyWith({ status: v ?? '' })}
                        options={Object.entries(statusMeta).map(([value, m]) => (
                          { value, label: m.label }))}
                      />
                      <Input value={filters.skill} placeholder="Skill 搜索" allowClear
                             style={{ width: 170 }}
                             onChange={(e) => setFilters((prev) => ({ ...prev, skill: e.target.value }))}
                             onPressEnter={() => applyWith({ skill: filters.skill })} />
                      <Input value={filters.tenant} placeholder="租户" allowClear
                             style={{ width: 150 }}
                             onChange={(e) => setFilters((prev) => ({ ...prev, tenant: e.target.value }))}
                             onPressEnter={() => applyWith({ tenant: filters.tenant })} />
                      <Checkbox checked={filters.onlyFailed}
                                onChange={(e) => applyWith({ onlyFailed: e.target.checked })}
                      >仅看失败</Checkbox>
                      <Button type="primary" size="small"
                              onClick={() => applyWith({})}>筛选</Button>
                      <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
                        <Input
                          value={taskIdInput}
                          placeholder="输入 taskId 查询详情"
                          allowClear
                          data-testid="taskid-input"
                          onChange={(e) => setTaskIdInput(e.target.value)}
                          onPressEnter={query}
                          prefix={<SearchOutlined />}
                        />
                        <Button type="primary" loading={loading} onClick={query}
                                data-testid="query-btn">查询</Button>
                      </div>
                    </Space>
                  </div>
                  <Table
                    size="small"
                    rowKey="taskId"
                    columns={recentColumns}
                    dataSource={recent.items}
                    loading={recentLoading}
                    pagination={{
                      current: recent.pageNo,
                      pageSize: recent.pageSize,
                      total: recent.total,
                      showSizeChanger: false,
                      onChange: (page, size) => loadRecent(range, filters, page, size),
                    }}
                    onRow={(row) => ({ onClick: () => pick(row), style: { cursor: 'pointer' } })}
                    onChange={onTableChange}
                    scroll={{ x: 1100 }}
                  />
                </>
              ),
            },
            {
              key: 'dead',
              label: `异常处理台（${deadLetters.length}）`,
              children: (
                <Table
                  size="small"
                  rowKey="taskId"
                  columns={deadColumns}
                  dataSource={deadLetters}
                  pagination={false}
                />
              ),
            },
          ]}
        />
      </Card>

      {/* 详情抽屉 */}
      <Drawer
        open={!!execution}
        width={680}
        title={execution ? `任务详情 · ${execution.taskId}` : '任务详情'}
        onClose={() => {
          stopRefresh()
          setExecution(null)
          setEvents([])
        }}
        destroyOnClose
      >
        {execution && (
          <>
            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label="状态">
                <Tag color={statusMeta[execution.status]?.color === '#f04438' ? 'error'
                  : statusMeta[execution.status]?.color === '#12b76a' ? 'success'
                  : statusMeta[execution.status]?.color === '#2b7fff' ? 'processing' : 'default'}>
                  {statusMeta[execution.status]?.label ?? execution.status}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="版本">{execution.resolvedVersion ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="扣点">{execution.billing?.pointsCharged ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="时长">{fmtDuration(execution.durationMs)}</Descriptions.Item>
              <Descriptions.Item label="模型调用" span={2}>
                {execution.modelCalls ?? '—'} 次 / {execution.tokensUsed ?? 0} tokens
              </Descriptions.Item>
              <Descriptions.Item label="回调">
                <Tag color={execution.callbackStatus === 'SENT' ? 'success'
                  : execution.callbackStatus === 'FAILED' ? 'error' : 'default'}>
                  {execution.callbackStatus ?? '无回调'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="回调地址" span={2}>
                <span className="mono">{execution.callbackUrl ?? '—'}</span>
              </Descriptions.Item>
            </Descriptions>
            <Space style={{ margin: '10px 0' }}>
              <Button size="small" data-testid="input-btn" onClick={showInput}>查看入参 JSON</Button>
              {execution.status === 'FAILED' && (
                <Button size="small" type="primary" ghost
                        onClick={() => retryTask(execution.taskId)}>重试</Button>
              )}
              {(execution.status === 'RUNNING' || execution.status === 'PENDING'
                || execution.status === 'CANCELLING') && (
                <Button size="small" type="primary" danger ghost data-testid="cancel-btn"
                        onClick={() => forceCancelTask(execution.taskId)}>强制终止</Button>
              )}
            </Space>
            {execution.error && (
              <Alert type="error" showIcon closable={false} style={{ margin: '10px 0' }}
                     message={`${execution.error.code}: ${execution.error.message}`}
                     data-testid="execution-error" />
            )}

            {/* 执行链路（流程图 + 时间线，数据源 execution_event / MySQL） */}
            <StagePipeline events={events} status={execution.status}
                           progress={execution.progress} callbackUrl={execution.callbackUrl} />
            <div className="list-head" style={{ marginTop: 16 }}><b>节点明细</b></div>
            <ExecutionTimeline events={events} />

            {execution.artifacts && execution.artifacts.length > 0 && (
              <>
                <h4 style={{ margin: '16px 0 8px' }}>
                  产物（结构化产物可在线预览；下载链接 24h 有效）
                </h4>
                <Table size="small" rowKey="artifactId" dataSource={execution.artifacts}
                       pagination={false} data-testid="artifacts-table">
                  <Table.Column title="产物" dataIndex="artifactId" width={220} className="mono" />
                  <Table.Column title="类型" dataIndex="type" width={100} />
                  <Table.Column title="大小 (B)" dataIndex="sizeBytes" width={100} />
                  <Table.Column title="查看 / 下载" width={170}
                                render={(_, row: ArtifactView) => (
                                  <Space size={8}>
                                    {row.previewable && (
                                      <Button type="link" size="small"
                                              data-testid={`preview-${row.artifactId}`}
                                              onClick={() => setPreview({
                                                taskId: execution.taskId, artifact: row,
                                              })}>预览</Button>
                                    )}
                                    <a href={row.url} target="_blank" rel="noopener noreferrer">下载</a>
                                  </Space>
                                )} />
                </Table>
              </>
            )}

            {/* V2.1：逐次模型调用明细（token 细分与费率折算） */}
            <div className="list-head" style={{ marginTop: 16 }}>
              <b>模型调用明细</b>
              <span className="sub">
                {execution.modelCalls ?? 0} 次 / {execution.tokensUsed ?? 0} tokens
              </span>
            </div>
            <ModelCallTable taskId={execution.taskId} />
          </>
        )}
        {!execution && <div style={{ minHeight: 200 }} />}
      </Drawer>

      {/* 入参 JSON 查看 */}
      <Modal
        open={inputVisible}
        title="受理入参（instructions / materials / context 原文）"
        width={640}
        onCancel={() => setInputVisible(false)}
        footer={[
          <Button key="copy" onClick={copyInput}>复制 JSON</Button>,
          <Button key="close" type="primary" onClick={() => setInputVisible(false)}>关闭</Button>,
        ]}
      >
        <pre className="mono" style={{ maxHeight: 420, overflow: 'auto' }}
             data-testid="input-json">{inputJson}</pre>
      </Modal>

      {/* 产物在线预览（结构化 JSON/MD/HTML） */}
      <ArtifactPreviewModal target={preview} onClose={() => setPreview(null)} />
    </div>
  )
}
