import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Card, Space, Tag } from 'antd'
import {
  AlertOutlined, ApiOutlined, AppstoreOutlined, FundViewOutlined, KeyOutlined,
  PieChartOutlined, WalletOutlined,
} from '@ant-design/icons'
import { gatewayApi } from '../api/gateway'

/**
 * 首页总览（V2.2）：运行健康 + 消耗总览合并。
 * 运行 KPI / 消耗 KPI / 日消耗趋势 / 待处理告警 / 快捷入口。
 */
const CHART_W = 960
const CHART_H = 150

function linePath(rows: any[], key: string): string {
  const values = rows.map((r) => Number(r[key]))
  if (!values.length) return ''
  const max = Math.max(...values, 1)
  const step = (CHART_W - 40) / Math.max(values.length - 1, 1)
  return values.map((v, i) =>
    `${i === 0 ? 'M' : 'L'}${(20 + i * step).toFixed(1)},${
      (CHART_H - 26 - (v / max) * (CHART_H - 46)).toFixed(1)}`).join(' ')
}

interface Kpi {
  label: string
  value: string
  sub?: string
  color?: string
}

export default function HomePage() {
  const navigate = useNavigate()
  const [board, setBoard] = useState<any>(null)
  const [summary, setSummary] = useState<any>(null)
  const [daily, setDaily] = useState<any[]>([])
  const [pendingAlerts, setPendingAlerts] = useState(0)
  const [skillTop, setSkillTop] = useState<any[]>([])

  useEffect(() => {
    (async () => {
      try {
        const [metrics, consumption] = await Promise.all([
          gatewayApi.adminMetricsDashboard(24),
          gatewayApi.consumptionSummary({ hours: 720 }),
        ])
        setBoard(metrics)
        setSummary(consumption)
        setSkillTop(metrics.skillUsageTop ?? [])
        try {
          setDaily(await gatewayApi.consumptionDaily({ hours: 720 }))
        } catch { /* 趋势失败不阻塞 */ }
        try {
          setPendingAlerts((await gatewayApi.adminAlertPendingCount()).count)
        } catch { /* 徽标失败容忍 */ }
      } catch { /* 整体失败保留空态 */ }
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const runKpis = useMemo<Kpi[]>(() => {
    const counts = board?.statusCountsToday ?? {}
    return [
      { label: '队列深度（PENDING）', value: String(board?.queueDepth ?? 0),
        sub: `运行中 ${board?.running ?? 0}`, color: '#2b7fff' },
      { label: '成功率（今日）',
        value: board?.successRateToday == null ? '—' : `${board.successRateToday}%`,
        sub: `完成 ${counts.SUCCEEDED ?? 0} · 失败 ${counts.FAILED ?? 0}`, color: '#12b76a' },
      { label: '排队年龄 P99', value: `${board?.pendingAgeP99Minutes ?? 0} min`,
        sub: '阈值 5min', color: '#475467' },
      { label: '待处理告警', value: String(pendingAlerts),
        sub: pendingAlerts > 0 ? '需处理' : '无', color: '#f04438' },
    ]
  }, [board, pendingAlerts])

  const consumeKpis = useMemo<Kpi[]>(() => {
    const s = summary ?? {}
    const calls = Number(s.calls ?? 0)
    const failed = Number(s.failedCalls ?? 0)
    return [
      { label: '消耗点数（30 天）', value: Number(s.costPoints ?? 0).toFixed(2),
        sub: `模型 ${s.models ?? 0} 个`, color: '#f79009' },
      { label: 'Token 消耗（30 天）', value: Number(s.tokens ?? 0).toLocaleString(),
        color: '#2b7fff' },
      { label: '模型调用（30 天）', value: calls.toLocaleString(), color: '#475467' },
      { label: '失败调用 / 失败成本',
        value: `${failed} / ${Number(s.failedCost ?? 0).toFixed(2)}`,
        color: failed > 0 ? '#f04438' : '#475467' },
    ]
  }, [summary])

  const tokensPath = useMemo(() => linePath(daily, 'tokens'), [daily])
  const costPath = useMemo(() => linePath(daily, 'costPoints'), [daily])
  const dayLabels = useMemo(() => daily
    .filter((_, i) => i % Math.ceil(Math.max(daily.length, 1) / 6) === 0)
    .map((d) => String(d.day).slice(5)), [daily])

  const entries = [
    { path: '/executions', title: '执行监控', desc: '任务列表 · 筛选 · 干预',
      icon: <FundViewOutlined /> },
    { path: '/skills', title: 'Skill 管理', desc: '上传 · 审核 · 版本回滚',
      icon: <AppstoreOutlined /> },
    { path: '/consumption', title: '消耗看板', desc: '预算 · 四维聚合 · 任务对账',
      icon: <PieChartOutlined /> },
    { path: '/keys', title: 'AppKey 与账户', desc: '签发 · 轮换 · 入账',
      icon: <KeyOutlined /> },
    { path: '/models', title: '模型与能力', desc: '别名路由 · 连通性测试',
      icon: <ApiOutlined /> },
    { path: '/alerts', title: '告警中心', desc: '规则 · 记录认领',
      icon: <AlertOutlined /> },
  ]

  return (
    <div>
      <div className="page-header">
        <div>
          <h2>总览</h2>
          <p className="sub">运行健康与消耗总览 · 数据延迟 &lt; 30s</p>
        </div>
        <Space>
          <Button onClick={() => navigate('/executions')}>进入执行监控</Button>
          <Button onClick={() => navigate('/consumption')}>进入消耗看板</Button>
        </Space>
      </div>

      {/* 运行健康 KPI */}
      <div className="section-head">运行健康（今日）</div>
      <div className="stat-grid" data-testid="home-run-kpis">
        {runKpis.map((kpi) => (
          <div key={kpi.label} className="stat-card">
            <div style={{ width: '100%' }}>
              <div className="label">{kpi.label}</div>
              <div className="value" style={kpi.color ? { color: kpi.color } : undefined}>
                {kpi.value}
              </div>
              {kpi.sub && <div className="foot">{kpi.sub}</div>}
            </div>
          </div>
        ))}
      </div>

      {/* 消耗 KPI */}
      <div className="section-head">消耗总览（30 天）</div>
      <div className="stat-grid" data-testid="home-consume-kpis">
        {consumeKpis.map((kpi) => (
          <div key={kpi.label} className="stat-card">
            <div style={{ width: '100%' }}>
              <div className="label">{kpi.label}</div>
              <div className="value" style={kpi.color ? { color: kpi.color } : undefined}>
                {kpi.value}
              </div>
              {kpi.sub && <div className="foot">{kpi.sub}</div>}
            </div>
          </div>
        ))}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 1fr', gap: 14 }}>
        {/* 日消耗趋势 */}
        <Card title="日消耗趋势（tokens / 点数，30 天）" size="small">
          <svg viewBox={`0 0 ${CHART_W} ${CHART_H}`} width="100%" height={CHART_H}>
            {daily.length > 1 && <path d={tokensPath} fill="none" stroke="#2b7fff" strokeWidth={2} />}
            {daily.length > 1 && <path d={costPath} fill="none" stroke="#f79009" strokeWidth={2} />}
            {daily.length > 1 && dayLabels.map((label, i) => (
              <text key={label + i} x={20 + i * ((CHART_W - 40) / 6)} y={CHART_H - 6}
                    fontSize={10} fill="#98a2b3">{label}</text>
            ))}
            {daily.length <= 1 && (
              <text x={CHART_W / 2 - 60} y={CHART_H / 2} fontSize={12} fill="#98a2b3">
                暂无消耗数据 · 跑一次 Skill 执行后可见
              </text>
            )}
          </svg>
          <div className="chart-legend">
            <span><i className="dot-line" style={{ background: '#2b7fff' }} />tokens（归一化）</span>
            <span><i className="dot-line" style={{ background: '#f79009' }} />点数（归一化）</span>
          </div>
        </Card>

        {/* Skill 用量排行 + 告警 + 快捷入口 */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <Card title="Skill 用量排行（24h）" size="small">
            {skillTop.length === 0 && (
              <div style={{ color: 'var(--ant-color-text-tertiary)', fontSize: 12.5, padding: '12px 0' }}>
                暂无执行数据
              </div>
            )}
            {skillTop.map((s: any) => {
              const max = Math.max(...skillTop.map((x) => Number(x.count)), 1)
              return (
                <div key={s.skillCode} className="bar-row">
                  <span className="bar-label" style={{ width: 120 }}>{s.skillCode}</span>
                  <div className="bar-track">
                    <div className="bar-fill"
                         style={{ width: `${Math.max(6, (Number(s.count) / max) * 100)}%`,
                                  background: '#2b7fff' }} />
                  </div>
                  <span className="bar-val">{Number(s.count).toLocaleString()}</span>
                </div>
              )
            })}
            {pendingAlerts > 0 && (
              <div style={{ marginTop: 12 }}>
                <Tag color="error" style={{ cursor: 'pointer' }}
                     onClick={() => navigate('/alerts')}>
                  <AlertOutlined /> {pendingAlerts} 条待处理告警 · 去处理
                </Tag>
              </div>
            )}
          </Card>

          <Card title="快捷入口" size="small">
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
              {entries.map((entry) => (
                <Card key={entry.path} size="small" hoverable style={{ cursor: 'pointer' }}
                      onClick={() => navigate(entry.path)}>
                  <Space size={8}>
                    {entry.icon}
                    <div>
                      <b style={{ fontSize: 13 }}>{entry.title}</b>
                      <div style={{
                        color: 'var(--ant-color-text-tertiary)', fontSize: 11.5,
                      }}>{entry.desc}</div>
                    </div>
                  </Space>
                </Card>
              ))}
            </div>
          </Card>
        </div>
      </div>
    </div>
  )
}
