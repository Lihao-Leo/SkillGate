import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Alert, App as AntdApp, Button, Card, Checkbox, Col, Descriptions, Drawer, Empty, Input,
  Modal, Progress, Row, Select, Space, Table, Tabs, Tag,
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

const TX_TYPE_TEXT: Record<string, string> = {}

function fmtDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
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

const CHART_W = 640
const CHART_H = 170

export default function ExecutionsPage() {
  const navigate = useNavigate()
  const { message, modal } = AntdApp.useApp()

  // ---------- 看板 ----------
  const [range, setRangeState] = useState<Range>(24)
  const [board, setBoard] = useState<any>(null)
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

  async function loadBoard(rangeValue: Range) {
    try {
      setBoard(await gatewayApi.adminMetricsDashboard(rangeValue))
    } catch {
      setBoard(null)
    }
  }

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

  /**
   * 筛选变更统一入口：合并 patch 后立即加载（第 1 页）。
   * 修复：状态/仅看失败等控件只改 state 不触发加载，导致筛选"不生效"。
   */
  function applyWith(patch: Partial<typeof filters>) {
    const next = { ...filters, ...patch }
    setFilters(next)
    loadRecent(range, next, 1, recent.pageSize)
  }

  /** 表头排序（时长/tokens 降序排查最耗时、最贵任务）；翻页（无 sorter.order）保留排序与页码。 */
  function onTableChange(pagination: TablePaginationConfig, _filters: unknown,
                         sorter: any) {
    const field = String(sorter?.field ?? '')
    if (field === 'durationMs' || field === 'tokensUsed') {
      const sortBy = sorter?.order
        ? field === 'durationMs' ? 'duration' : 'tokens'
        : ''
      applyWith({ sortBy })
      return
    }
    // 纯翻页：保留当前筛选与排序
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

  function applyFilters() {
    applyWith({})
  }

  function setRange(r: Range) {
    setRangeState(r)
    loadBoard(r)
    loadRecent(r, filters, recent.pageNo, recent.pageSize)
  }

  useEffect(() => {
    loadBoard(range)
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

  // ---------- 异常处理台 ----------
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

  // ---------- 看板派生 ----------
  const rangeLabel = range === 24 ? '今日' : range === 168 ? '近 7 天' : '近 30 天'
  const throughput: any[] = board?.throughput ?? []

  function linePath(key: 'accepted' | 'completed'): string {
    const values = throughput.map((t) => Number(t[key]))
    if (!values.length) return ''
    const max = Math.max(...values, 4)
    const step = (CHART_W - 40) / Math.max(values.length - 1, 1)
    return values.map((v, i) =>
      `${i === 0 ? 'M' : 'L'}${(20 + i * step).toFixed(1)},${
        (CHART_H - 26 - (v / max) * (CHART_H - 46)).toFixed(1)}`).join(' ')
  }

  const hourLabels = useMemo(() => {
    if (!throughput.length) return []
    return throughput
      .filter((_, i) => i % Math.ceil(throughput.length / 5) === 0)
      .map((t) => t.hour)
  }, [throughput])

  const statusBars = useMemo(() => {
    const counts = board?.statusCountsToday
    if (!counts) return []
    const total = Math.max(
      Number(counts.SUCCEEDED ?? 0) + Number(counts.RUNNING ?? 0)
      + Number(counts.FAILED ?? 0) + Number(counts.PENDING ?? 0), 1)
    return [
      { label: 'SUCCEEDED', value: Number(counts.SUCCEEDED ?? 0), color: '#12b76a' },
      { label: 'RUNNING', value: Number(counts.RUNNING ?? 0), color: '#2b7fff' },
      { label: 'FAILED', value: Number(counts.FAILED ?? 0), color: '#f04438' },
      { label: 'PENDING', value: Number(counts.PENDING ?? 0), color: '#98a2b3' },
    ].map((item) => ({ ...item, pct: Math.round((item.value / total) * 100) }))
  }, [board])

  const skillTop: any[] = board?.skillUsageTop ?? []
  const maxTop = Math.max(...skillTop.map((s) => Number(s.count)), 1)
  const failureTop: any[] = board?.failureTop ?? []
  const failureTopMax = Math.max(...failureTop.map((f) => Number(f.count)), 1)

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
          <Button type="link" size="small" onClick={(e) => { e.stopPropagation(); pick(row) }}>详情</Button>
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
          <Button type="link" size="small"
                  onClick={() => resendCallback(row.taskId)}>重发</Button>
          <Button type="link" danger size="small"
                  onClick={() => giveUpCallback(row.taskId)}>放弃</Button>
        </Space>
      ),
    },
  ]

  function paginationConfig(): TablePaginationConfig {
    return {
      current: recent.pageNo,
      pageSize: recent.pageSize,
      total: recent.total,
      showSizeChanger: false,
      onChange: (page, size) => loadRecent(range, filters, page, size),
    }
  }

  return (
    <div>
      <div className="page-header">
        <div>
          <h2>执行监控</h2>
          <p className="sub">skill-execute 链路实时视图 · 数据延迟 &lt; 30s</p>
        </div>
        <div className="range-pills" style={{ display: 'flex', gap: 4 }}>
          {([24, 168, 720] as Range[]).map((r) => (
            <button
              key={r}
              className={`pill${range === r ? ' active' : ''}`}
              data-testid={`range-${r}`}
              onClick={() => setRange(r)}
            >
              {r === 24 ? '今日' : r === 168 ? '近 7 天' : '近 30 天'}
            </button>
          ))}
          <button className="pill" data-testid="wall-link"
                  onClick={() => navigate('/wall')}>📺 大屏模式</button>
        </div>
      </div>

      {/* 4 统计卡 */}
      <div className="stat-grid">
        <div className="stat-card">
          <div>
            <div className="label">队列深度（PENDING）</div>
            <div className="value">{board?.queueDepth ?? '—'} <small>条</small></div>
          </div>
        </div>
        <div className="stat-card">
          <div>
            <div className="label">PENDING 年龄 P99</div>
            <div className="value">{board?.pendingAgeP99Minutes ?? '—'} <small>min</small></div>
          </div>
        </div>
        <div className="stat-card">
          <div>
            <div className="label">成功率（{rangeLabel}）</div>
            <div className="value">{board?.successRateToday ?? '—'} <small>%</small></div>
            <div className="foot">
              ▲ 完成 {board?.statusCountsToday?.SUCCEEDED ?? 0} · ▼ 失败 {board?.statusCountsToday?.FAILED ?? 0}
            </div>
          </div>
        </div>
        <div className="stat-card">
          <div>
            <div className="label">失败 Top 错误码（{rangeLabel}）</div>
            <div className="value mono" style={{ fontSize: 18 }}>
              {failureTop[0]?.errorCode ?? '—'}
            </div>
            <div className="foot">
              {failureTop[0] ? `× ${failureTop[0].count}` : '无失败'}
              {failureTop.length > 1 && <span> 共 {failureTop.length} 种 · 明细见状态分布</span>}
            </div>
          </div>
        </div>
      </div>

      {/* 吞吐图 + 状态分布 */}
      <Row gutter={14}>
        <Col span={13}>
          <Card title="任务吞吐（受理 / 完成）" size="small">
            <svg viewBox={`0 0 ${CHART_W} ${CHART_H}`} width="100%" height={CHART_H}>
              <defs>
                <linearGradient id="fillBlue" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#2b7fff" stopOpacity={0.18} />
                  <stop offset="100%" stopColor="#2b7fff" stopOpacity={0} />
                </linearGradient>
              </defs>
              {throughput.length > 1 && (
                <path
                  d={`${linePath('accepted')} L${CHART_W - 20},${CHART_H - 26} L20,${CHART_H - 26} Z`}
                  fill="url(#fillBlue)" stroke="none"
                />
              )}
              {throughput.length > 1 && (
                <path d={linePath('accepted')} fill="none" stroke="#2b7fff" strokeWidth={2} />
              )}
              {throughput.length > 1 && (
                <path d={linePath('completed')} fill="none" stroke="#12b76a" strokeWidth={2} />
              )}
              {throughput.length > 1 && hourLabels.map((label: string, i: number) => (
                <text
                  key={label + i}
                  x={20 + i * ((CHART_W - 40) / 5)}
                  y={CHART_H - 6}
                  fontSize={10}
                  fill="#98a2b3"
                >
                  {label}
                </text>
              ))}
            </svg>
            <div className="chart-legend">
              <span><i className="dot-line" style={{ background: '#2b7fff' }} />受理</span>
              <span><i className="dot-line" style={{ background: '#12b76a' }} />完成</span>
            </div>
          </Card>
        </Col>
        <Col span={11}>
          <Card title={`任务状态分布（${rangeLabel}）`} size="small">
            {statusBars.map((bar) => (
              <div key={bar.label} className="bar-row">
                <span className="bar-label">{bar.label}</span>
                <div className="bar-track">
                  <div className="bar-fill" style={{ width: `${bar.pct}%`, background: bar.color }} />
                </div>
                <span className="bar-val">{bar.value.toLocaleString()}</span>
              </div>
            ))}
            {failureTop.length > 0 && <div className="sub-head">失败原因 Top5</div>}
            {failureTop.map((f) => (
              <div key={f.errorCode} className="bar-row">
                <span className="bar-label mono" style={{ width: 130 }}>{f.errorCode}</span>
                <div className="bar-track">
                  <div className="bar-fill"
                       style={{ width: `${(Number(f.count) / failureTopMax) * 100}%`, background: '#f04438' }} />
                </div>
                <span className="bar-val">{Number(f.count).toLocaleString()}</span>
              </div>
            ))}
          </Card>
        </Col>
      </Row>

      {/* Skill 用量 Top5 */}
      <Card title="Skill 用量 Top 5" size="small" style={{ marginTop: 14 }}>
        {skillTop.map((s) => (
          <div key={s.skillCode} className="bar-row">
            <span className="bar-label" style={{ width: 160 }}>{s.skillCode}</span>
            <div className="bar-track">
              <div className="bar-fill"
                   style={{ width: `${(Number(s.count) / maxTop) * 100}%`, background: '#2b7fff' }} />
            </div>
            <span className="bar-val">{Number(s.count).toLocaleString()}</span>
          </div>
        ))}
        {skillTop.length === 0 && <Empty description="暂无执行数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
      </Card>

      {/* 任务列表 + 异常处理台 */}
      <Card style={{ marginTop: 14 }}>
        <Tabs
          defaultActiveKey="list"
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
                             onChange={(e) => {
                               const v = e.target.value
                               setFilters((prev) => ({ ...prev, skill: v }))
                               // 清空（allowClear ×）即刷
                               if (v === '' && filters.skill !== '') applyWith({ skill: '' })
                             }}
                             onPressEnter={() => applyWith({ skill: filters.skill })} />
                      <Input value={filters.tenant} placeholder="租户" allowClear
                             style={{ width: 150 }}
                             onChange={(e) => {
                               const v = e.target.value
                               setFilters((prev) => ({ ...prev, tenant: v }))
                               if (v === '' && filters.tenant !== '') applyWith({ tenant: '' })
                             }}
                             onPressEnter={() => applyWith({ tenant: filters.tenant })} />
                      <Checkbox checked={filters.onlyFailed}
                                onChange={(e) => applyWith({ onlyFailed: e.target.checked })}
                      >仅看失败</Checkbox>
                      <Button type="primary" size="small" onClick={applyFilters}>筛选</Button>
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
                    pagination={paginationConfig()}
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
                  <Table.Column title="产物" dataIndex="artifactId" width={220}
                                className="mono" />
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
        {!execution && <div v-loading style={{ minHeight: 200 }} />}
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
