import { useEffect, useMemo, useState } from 'react'
import {
  App as AntdApp, Button, Card, Col, Form, Input, InputNumber, Modal, Progress,
  Row, Select, Space, Switch, Table, Tabs, Tag,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  gatewayApi,
  type BudgetPayload,
  type BudgetStatusView,
  type ConsumptionDailyRow,
  type ConsumptionGroupRow,
  type ConsumptionQuery,
  type ConsumptionSummary,
  type ConsumptionTaskRow,
} from '../api/gateway'
import { hasPerm, useAuthStore } from '../stores/auth'

type Range = 24 | 168 | 720

const CHART_W = 640
const CHART_H = 160

export default function ConsumptionPage() {
  const { message, modal } = AntdApp.useApp()
  const session = useAuthStore((state) => state.session)
  const canManage = hasPerm(session, 'consumption:manage')

  const [range, setRangeState] = useState<Range>(720)
  const [filters, setFilters] = useState({ tenantId: '', skillCode: '', alias: '' })
  const [summary, setSummary] = useState<ConsumptionSummary | null>(null)
  const [daily, setDaily] = useState<ConsumptionDailyRow[]>([])
  const [budgets, setBudgets] = useState<BudgetStatusView[]>([])
  const [loading, setLoading] = useState(false)

  // Tabs 数据
  const [bySkill, setBySkill] = useState<ConsumptionGroupRow[]>([])
  const [byModel, setByModel] = useState<ConsumptionGroupRow[]>([])
  const [byTenant, setByTenant] = useState<ConsumptionGroupRow[]>([])
  const [tasks, setTasks] = useState<ConsumptionTaskRow[]>([])
  const [taskPage, setTaskPage] = useState({ pageNo: 1, pageSize: 20, total: 0 })

  // 预算弹窗
  const [budgetDialog, setBudgetDialog] = useState({
    open: false, id: null as number | null,
    scopeType: 'GLOBAL', scopeValue: '', amountPoints: 10000, alertThresholdPct: 80,
    enabledBool: true,
  })

  function criteria(pageNo?: number, pageSize?: number): ConsumptionQuery {
    return {
      hours: range,
      tenantId: filters.tenantId.trim() || undefined,
      skillCode: filters.skillCode.trim() || undefined,
      alias: filters.alias.trim() || undefined,
      ...(pageNo !== undefined ? { pageNo, pageSize } : {}),
    }
  }

  async function loadAll(nextRange: Range = range, nextFilters = filters) {
    setLoading(true)
    const opts = {
      hours: nextRange,
      tenantId: nextFilters.tenantId.trim() || undefined,
      skillCode: nextFilters.skillCode.trim() || undefined,
      alias: nextFilters.alias.trim() || undefined,
    }
    try {
      const [summaryData, dailyData, skillRows, modelRows, tenantRows, budgetRows] =
        await Promise.all([
          gatewayApi.consumptionSummary(opts),
          gatewayApi.consumptionDaily(opts),
          gatewayApi.consumptionBySkill(opts),
          gatewayApi.consumptionByModel(opts),
          gatewayApi.consumptionByTenant(opts),
          gatewayApi.budgetList(),
        ])
      setSummary(summaryData)
      setDaily(dailyData)
      setBySkill(skillRows)
      setByModel(modelRows)
      setByTenant(tenantRows)
      setBudgets(budgetRows)
      const taskPage = await gatewayApi.consumptionTasks({ ...opts, pageNo: 1, pageSize: 20 })
      setTasks(taskPage.rows)
      setTaskPage({ pageNo: 1, pageSize: 20, total: taskPage.total })
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { loadAll() /* eslint-disable-line react-hooks/exhaustive-deps */ }, [])

  function setRange(r: Range) {
    setRangeState(r)
    loadAll(r)
  }

  // ---------- 日趋势折线（tokens / cost 各自归一化） ----------
  const tokensPath = useMemo(() => linePath(daily, 'tokens'), [daily])
  const costPath = useMemo(() => linePath(daily, 'costPoints'), [daily])
  const dayLabels = useMemo(() => daily
    .filter((_, i) => i % Math.ceil(Math.max(daily.length, 1) / 5) === 0)
    .map((d) => String(d.day).slice(5)), [daily])

  // ---------- 预算余量（取 GLOBAL 预算；无则取全部预算合计） ----------
  const budgetInfo = useMemo(() => {
    if (budgets.length === 0) return null
    const global = budgets.find((b) => b.scopeType === 'GLOBAL') ?? budgets[0]
    return global
  }, [budgets])

  async function saveBudget() {
    try {
      const payload: BudgetPayload = {
        scopeType: budgetDialog.scopeType,
        scopeValue: budgetDialog.scopeType === 'GLOBAL' ? undefined : budgetDialog.scopeValue.trim(),
        amountPoints: Number(budgetDialog.amountPoints),
        alertThresholdPct: Number(budgetDialog.alertThresholdPct),
        enabled: budgetDialog.enabledBool ? 1 : 0,
      }
      if (budgetDialog.id === null) {
        await gatewayApi.budgetCreate(payload)
        message.success('预算已创建')
      } else {
        await gatewayApi.budgetUpdate(budgetDialog.id, payload)
        message.success('预算已更新')
      }
      setBudgetDialog((prev) => ({ ...prev, open: false }))
      await loadAll()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function toggleBudget(row: BudgetStatusView) {
    try {
      await gatewayApi.budgetUpdateStatus(row.id, row.enabled === 1 ? 0 : 1)
      await loadAll()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function removeBudget(row: BudgetStatusView) {
    const ok = await new Promise<boolean>((resolve) => {
      modal.confirm({
        title: '删除预算',
        content: `确定删除该预算（${row.scopeType}:${row.scopeValue || '全局'}，额度 ${row.amountPoints} 点）？`,
        okButtonProps: { danger: true },
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      })
    })
    if (!ok) return
    try {
      await gatewayApi.budgetDelete(row.id)
      message.success('已删除')
      await loadAll()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  // ---------- 表格列 ----------
  const skillColumns: ColumnsType<ConsumptionGroupRow> = [
    { title: 'Skill', dataIndex: 'skill_code', className: 'mono' },
    { title: '调用次数', dataIndex: 'calls', width: 100, align: 'right' },
    { title: 'tokens', dataIndex: 'tokens', width: 120, align: 'right',
      render: (v: number) => v.toLocaleString() },
    { title: '消耗点数', dataIndex: 'costPoints', width: 110, align: 'right',
      render: (v: number) => Number(v ?? 0).toFixed(4) },
    { title: '失败次数', dataIndex: 'failedCalls', width: 100, align: 'right' },
    { title: '失败成本', dataIndex: 'failedCost', width: 100, align: 'right',
      render: (v: number) => Number(v ?? 0).toFixed(4) },
  ]
  const modelColumns: ColumnsType<ConsumptionGroupRow> = [
    { title: '模型 alias', dataIndex: 'alias', className: 'mono' },
    { title: '供应商', dataIndex: 'provider' },
    { title: '调用次数', dataIndex: 'calls', width: 100, align: 'right' },
    { title: 'tokens', dataIndex: 'tokens', width: 120, align: 'right',
      render: (v: number) => v.toLocaleString() },
    { title: '消耗点数', dataIndex: 'costPoints', width: 110, align: 'right',
      render: (v: number) => Number(v ?? 0).toFixed(4) },
    { title: '失败次数', dataIndex: 'failedCalls', width: 100, align: 'right' },
  ]
  const tenantColumns: ColumnsType<ConsumptionGroupRow> = [
    { title: '租户', dataIndex: 'tenant_id', className: 'mono' },
    { title: '调用次数', dataIndex: 'calls', width: 100, align: 'right' },
    { title: 'tokens', dataIndex: 'tokens', width: 120, align: 'right',
      render: (v: number) => v.toLocaleString() },
    { title: '消耗点数', dataIndex: 'costPoints', width: 110, align: 'right',
      render: (v: number) => Number(v ?? 0).toFixed(4) },
    { title: '失败次数', dataIndex: 'failedCalls', width: 100, align: 'right' },
  ]
  const taskColumns: ColumnsType<ConsumptionTaskRow> = [
    { title: 'taskId', dataIndex: 'task_id', width: 220, className: 'mono' },
    { title: '租户', dataIndex: 'tenant_id', width: 130 },
    { title: 'Skill', dataIndex: 'skill_code', width: 140 },
    { title: 'tokens', dataIndex: 'tokens', width: 100, align: 'right',
      render: (v: number) => v.toLocaleString() },
    { title: '成本（折算）', dataIndex: 'costPoints', width: 110, align: 'right',
      render: (v: number) => Number(v ?? 0).toFixed(4) },
    { title: '定价口径', dataIndex: 'pricingNote', width: 140 },
    { title: '应收点数', dataIndex: 'revenuePoints', width: 100, align: 'right' },
    {
      title: '毛利', dataIndex: 'marginPoints', width: 100, align: 'right',
      render: (v: number) => (
        <span className={v >= 0 ? 'pos' : 'neg'}>{Number(v.toFixed(4))}</span>
      ),
    },
  ]

  function linePath(rows: ConsumptionDailyRow[], key: 'tokens' | 'costPoints'): string {
    const values = rows.map((r) => Number(r[key]))
    if (!values.length) return ''
    const max = Math.max(...values, 1)
    const step = (CHART_W - 40) / Math.max(values.length - 1, 1)
    return values.map((v, i) =>
      `${i === 0 ? 'M' : 'L'}${(20 + i * step).toFixed(1)},${
        (CHART_H - 26 - (v / max) * (CHART_H - 46)).toFixed(1)}`).join(' ')
  }

  const summaryCalls = summary?.calls ?? 0
  const summaryFailed = summary?.failedCalls ?? 0

  return (
    <div>
      <div className="page-header">
        <div>
          <h2>消耗看板</h2>
          <p className="sub">
            Token 消耗与第三方接口费用 · 失败成本 · 预算与额度（基于上报明细，可能有少量偏差）
          </p>
        </div>
        <div className="range-pills" style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
          {([24, 168, 720] as Range[]).map((r) => (
            <button key={r} className={`pill${range === r ? ' active' : ''}`}
                    onClick={() => setRange(r)}>
              {r === 24 ? '今日' : r === 168 ? '近 7 天' : '近 30 天'}
            </button>
          ))}
          <Input placeholder="租户" allowClear style={{ width: 140 }} value={filters.tenantId}
                 onChange={(e) => setFilters({ ...filters, tenantId: e.target.value })}
                 onPressEnter={() => loadAll()} />
          <Input placeholder="Skill" allowClear style={{ width: 150 }} value={filters.skillCode}
                 onChange={(e) => setFilters({ ...filters, skillCode: e.target.value })}
                 onPressEnter={() => loadAll()} />
          <Input placeholder="模型 alias" allowClear style={{ width: 140 }} value={filters.alias}
                 onChange={(e) => setFilters({ ...filters, alias: e.target.value })}
                 onPressEnter={() => loadAll()} />
          <Button onClick={() => loadAll()}>刷新</Button>
        </div>
      </div>

      {/* KPI 4 卡 */}
      <div className="stat-grid">
        <div className="stat-card">
          <div>
            <div className="label">总消耗点数（窗口）</div>
            <div className="value">{Number(summary?.costPoints ?? 0).toFixed(4)}</div>
          </div>
        </div>
        <div className="stat-card">
          <div>
            <div className="label">总 tokens</div>
            <div className="value">{(summary?.tokens ?? 0).toLocaleString()}</div>
          </div>
        </div>
        <div className="stat-card">
          <div>
            <div className="label">调用次数</div>
            <div className="value">{summaryCalls.toLocaleString()}
              <small> 失败 {summaryFailed}（{summaryCalls > 0
                ? Math.round((summaryFailed / summaryCalls) * 100) : 0}%）</small>
            </div>
          </div>
        </div>
        <div className="stat-card">
          <div>
            <div className="label">预算余量（本月）</div>
            <div className="value">
              {budgetInfo
                ? `${Math.max(0, budgetInfo.amountPoints - budgetInfo.usedPoints).toLocaleString()}`
                : '—'}
              {budgetInfo && <small> / {budgetInfo.amountPoints.toLocaleString()}</small>}
            </div>
            {budgetInfo && (
              <Progress
                percent={Math.min(100, budgetInfo.usedPct)}
                size="small"
                strokeColor={budgetInfo.usedPct >= 100 ? '#f04438'
                  : budgetInfo.usedPct >= budgetInfo.alertThresholdPct ? '#f79009' : '#12b76a'}
              />
            )}
          </div>
        </div>
      </div>

      {/* 日趋势 */}
      <Card title="日消耗趋势（tokens / 点数）" size="small" style={{ marginBottom: 14 }}>
        <svg viewBox={`0 0 ${CHART_W} ${CHART_H}`} width="100%" height={CHART_H}>
          {daily.length > 1 && (
            <path d={tokensPath} fill="none" stroke="#2b7fff" strokeWidth={2} />
          )}
          {daily.length > 1 && (
            <path d={costPath} fill="none" stroke="#f79009" strokeWidth={2} />
          )}
          {daily.length > 1 && dayLabels.map((label, i) => (
            <text key={label + i} x={20 + i * ((CHART_W - 40) / 5)} y={CHART_H - 6}
                  fontSize={10} fill="#98a2b3">{label}</text>
          ))}
          {daily.length <= 1 && (
            <text x={CHART_W / 2 - 40} y={CHART_H / 2} fontSize={12} fill="#98a2b3">暂无数据</text>
          )}
        </svg>
        <div className="chart-legend">
          <span><i className="dot-line" style={{ background: '#2b7fff' }} />tokens（自身归一化）</span>
          <span><i className="dot-line" style={{ background: '#f79009' }} />点数（自身归一化）</span>
        </div>
      </Card>

      {/* 预算 */}
      <Card title={
        <span>月度预算
          {!canManage && <Tag style={{ marginLeft: 8 }} color="default">只读</Tag>}
        </span>
      } size="small" style={{ marginBottom: 14 }}
        extra={canManage ? (
          <Button size="small" type="primary" onClick={() => setBudgetDialog({
            open: true, id: null, scopeType: 'GLOBAL', scopeValue: '',
            amountPoints: 10000, alertThresholdPct: 80, enabledBool: true,
          })}>新建预算</Button>
        ) : undefined}
      >
        <Table size="small" rowKey="id" dataSource={budgets} pagination={false}
               data-testid="budget-table">
          <Table.Column title="范围" width={180}
                        render={(_, row: BudgetStatusView) => (
                          <Tag color={row.scopeType === 'GLOBAL' ? 'processing' : 'default'}>
                            {row.scopeType === 'GLOBAL' ? '全局'
                              : `${row.scopeType}:${row.scopeValue}`}
                          </Tag>
                        )} />
          <Table.Column title="月额度（点）" dataIndex="amountPoints" width={120} align="right"
                        render={(v: number) => v.toLocaleString()} />
          <Table.Column title="本月已用" dataIndex="usedPoints" width={110} align="right"
                        render={(v: number) => v.toLocaleString()} />
          <Table.Column title="用量" width={220}
                        render={(_, row: BudgetStatusView) => (
                          <Progress
                            percent={Math.min(100, row.usedPct)}
                            size="small"
                            strokeColor={row.usedPct >= 100 ? '#f04438'
                              : row.usedPct >= row.alertThresholdPct ? '#f79009' : '#12b76a'}
                          />
                        )} />
          <Table.Column title="告警阈值" dataIndex="alertThresholdPct" width={100} align="right"
                        render={(v: number) => `${v}%`} />
          <Table.Column title="启用" width={80}
                        render={(_, row: BudgetStatusView) => (
                          <Tag color={row.enabled === 1 ? 'success' : 'default'}>
                            {row.enabled === 1 ? '启用' : '停用'}
                          </Tag>
                        )} />
          {canManage && (
            <Table.Column title="操作" width={150}
                          render={(_, row: BudgetStatusView) => (
                            <Space size={4}>
                              <Button type="link" size="small"
                                      onClick={() => setBudgetDialog({
                                        open: true, id: row.id, scopeType: row.scopeType,
                                        scopeValue: row.scopeValue,
                                        amountPoints: row.amountPoints,
                                        alertThresholdPct: row.alertThresholdPct,
                                        enabledBool: row.enabled === 1,
                                      })}>编辑</Button>
                              <Button type="link" size="small"
                                      onClick={() => toggleBudget(row)}>
                                {row.enabled === 1 ? '停用' : '启用'}
                              </Button>
                              <Button type="link" danger size="small"
                                      onClick={() => removeBudget(row)}>删除</Button>
                            </Space>
                          )} />
          )}
        </Table>
      </Card>

      {/* 四维聚合 Tabs */}
      <Card>
        <Tabs
          items={[
            {
              key: 'skill', label: '按 Skill',
              children: <Table size="small" rowKey={(r) => String(r.skill_code)}
                               columns={skillColumns} dataSource={bySkill} pagination={false} />,
            },
            {
              key: 'model', label: '按模型',
              children: <Table size="small" rowKey={(r) => String(r.alias)}
                               columns={modelColumns} dataSource={byModel} pagination={false} />,
            },
            {
              key: 'tenant', label: '按租户',
              children: <Table size="small" rowKey={(r) => String(r.tenant_id)}
                               columns={tenantColumns} dataSource={byTenant} pagination={false} />,
            },
            {
              key: 'tasks', label: '任务对照（成本 vs 收入）',
              children: (
                <>
                  <Table size="small" rowKey="task_id" columns={taskColumns} dataSource={tasks}
                         pagination={{
                           current: taskPage.pageNo, pageSize: taskPage.pageSize,
                           total: taskPage.total, showSizeChanger: false,
                           onChange: (pageNo, pageSize) => {
                             gatewayApi.consumptionTasks({
                               ...criteria(), hours: range,
                               pageNo, pageSize,
                             } as ConsumptionQuery & { pageNo: number; pageSize: number })
                               .then((page) => {
                                 setTasks(page.rows)
                                 setTaskPage({ pageNo, pageSize, total: page.total })
                               })
                           },
                         }} />
                  <p className="upload-hint" style={{ marginTop: 8 }}>
                    成本 = model_call 按费率折算的模型真实消耗；应收 = 任务定价口径（PER_EXECUTION
                    单价×期望产出 / METERED 封顶 / FREE 为 0）。差值为负即亏损任务。
                  </p>
                </>
              ),
            },
          ]}
        />
      </Card>

      {/* 预算编辑弹窗 */}
      <Modal
        open={budgetDialog.open}
        title={budgetDialog.id === null ? '新建月度预算' : '编辑月度预算'}
        width={480}
        onCancel={() => setBudgetDialog((prev) => ({ ...prev, open: false }))}
        footer={null}
      >
        <Form layout="vertical" onFinish={saveBudget}>
          <Form.Item label="预算范围" required>
            <Select value={budgetDialog.scopeType} style={{ width: '100%' }}
                    disabled={budgetDialog.id !== null}
                    onChange={(v) => setBudgetDialog((prev) => ({ ...prev, scopeType: v }))}
                    options={[
                      { value: 'GLOBAL', label: '全局' },
                      { value: 'TENANT', label: '按租户' },
                      { value: 'SKILL', label: '按 Skill' },
                    ]} />
          </Form.Item>
          {budgetDialog.scopeType !== 'GLOBAL' && (
            <Form.Item label={budgetDialog.scopeType === 'TENANT' ? '租户 ID' : 'Skill 编码'} required>
              <Input value={budgetDialog.scopeValue}
                     placeholder={budgetDialog.scopeType === 'TENANT' ? '如 recharge-user-7' : '如 trace-demo'}
                     onChange={(e) => setBudgetDialog((prev) => ({ ...prev, scopeValue: e.target.value }))} />
            </Form.Item>
          )}
          <Form.Item label="月额度（点）" required>
            <InputNumber min={1} style={{ width: '100%' }} value={budgetDialog.amountPoints}
                         onChange={(v) => setBudgetDialog((prev) => ({ ...prev, amountPoints: v ?? 1 }))} />
          </Form.Item>
          <Form.Item label="告警阈值（%）">
            <InputNumber min={1} max={100} style={{ width: '100%' }}
                         value={budgetDialog.alertThresholdPct}
                         onChange={(v) => setBudgetDialog((prev) => ({ ...prev, alertThresholdPct: v ?? 80 }))} />
          </Form.Item>
          <Form.Item label="启用">
            <Switch checked={budgetDialog.enabledBool}
                    onChange={(v) => setBudgetDialog((prev) => ({ ...prev, enabledBool: v }))} />
          </Form.Item>
          <Button type="primary" htmlType="submit" block>保存</Button>
        </Form>
      </Modal>
    </div>
  )
}
