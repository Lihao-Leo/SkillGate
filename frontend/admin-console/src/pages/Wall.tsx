import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { gatewayApi } from '../api/gateway'
import StagePipeline from '../components/StagePipeline'

/**
 * 监控大屏（V2.1 指标化）：KPI 卡行（运行总数/运行中/成功率/失败数/平均耗时/token 费用/
 * 待处理告警）+ TOP skill 执行次数排行 + 任务流水线墙。10s 轮询，深色全屏。
 * 注意：轮询必须在 useEffect 内（修复旧 Vue 版模块顶层 setInterval 的隐患）。
 */
interface WallTask {
  taskId: string
  tenantId: string
  skillCode: string
  status: string
  progress?: number | null
  durationMs?: number | null
  createdAt?: string | null
  events: any[]
}

interface Kpi {
  key: string
  label: string
  value: string
  tone: 'blue' | 'green' | 'red' | 'amber' | 'plain'
  pulse?: boolean
}

const PAGE_SIZE = 8
const TOP_N = 10

export default function WallPage() {
  const navigate = useNavigate()
  const [tasks, setTasks] = useState<WallTask[]>([])
  const [board, setBoard] = useState<any>(null)
  const [pendingAlerts, setPendingAlerts] = useState(0)
  const [updated, setUpdated] = useState('')

  const topSkills = useMemo<any[]>(() => board?.skillUsageTop ?? [], [board])
  const maxTopCount = useMemo(
    () => Math.max(1, ...topSkills.map((row) => Number(row.count ?? 0))), [topSkills])

  const kpis = useMemo<Kpi[]>(() => {
    const b = board ?? {}
    const cost = typeof b.costPointsWindow === 'number' ? b.costPointsWindow : 0
    const avgSec = b.avgDurationMsWindow ? (b.avgDurationMsWindow / 1000).toFixed(1) : '0.0'
    return [
      { key: 'total', label: '运行总数（窗口）', value: String(b.totalToday ?? 0), tone: 'blue' },
      { key: 'running', label: '运行中', value: String(b.running ?? 0), tone: 'blue',
        pulse: Number(b.running ?? 0) > 0 },
      { key: 'successRate', label: '成功率',
        value: b.successRateToday == null ? '—' : `${b.successRateToday}%`, tone: 'green' },
      { key: 'failed', label: '失败数',
        value: String(b.failedToday ?? b.statusCountsToday?.FAILED ?? 0),
        tone: 'red', pulse: Number(b.failedToday ?? 0) > 0 },
      { key: 'avgDuration', label: '平均耗时', value: `${avgSec}s`, tone: 'plain' },
      { key: 'tokenCost', label: 'Token 费用（点）', value: cost.toFixed(2), tone: 'amber' },
      { key: 'pendingAlerts', label: '待处理告警', value: String(pendingAlerts),
        tone: pendingAlerts > 0 ? 'red' : 'plain', pulse: pendingAlerts > 0 },
    ]
  }, [board, pendingAlerts])

  async function load() {
    try {
      const [page, metrics] = await Promise.all([
        gatewayApi.adminListExecutions(24, { pageNo: 1, pageSize: PAGE_SIZE }),
        gatewayApi.adminMetricsDashboard(24, TOP_N),
      ])
      setBoard(metrics)
      const loaded: WallTask[] = []
      for (const row of page.records) {
        let events: any[] = []
        try {
          events = await gatewayApi.adminExecutionEvents(row.taskId)
        } catch { /* 单条失败不影响整墙 */ }
        loaded.push({ ...row, events })
      }
      setTasks(loaded)
      try {
        setPendingAlerts((await gatewayApi.adminAlertPendingCount()).count)
      } catch { /* 徽标失败容忍 */ }
      setUpdated(new Date().toLocaleTimeString('zh-CN', { hour12: false }))
    } catch { /* 网络抖动保留上一帧 */ }
  }

  // 10s 轮询（useEffect + cleanup，StrictMode 安全；修复旧版模块顶层 setInterval 隐患）
  useEffect(() => {
    load()
    const timer = setInterval(load, 10000)
    return () => clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="wall">
      <header className="wall-head">
        <div>
          <h1>SkillGate · 执行监控大屏</h1>
          <p>
            KPI + TOP{TOP_N} Skill 排行 + 最近 {PAGE_SIZE} 条任务 · 每 10 秒自动刷新
            {updated && <> · 更新于 {updated}</>}
          </p>
        </div>
        <button className="back" onClick={() => navigate('/executions')}>返回管理台</button>
      </header>

      {/* KPI 卡行（7 项） */}
      <div className="kpi-row" data-testid="wall-kpis">
        {kpis.map((kpi) => (
          <div key={kpi.key} className={`kpi tone-${kpi.tone}${kpi.pulse ? ' pulse' : ''}`}
               data-testid={`kpi-${kpi.key}`}>
            <span className="kpi-label">{kpi.label}</span>
            <span className="kpi-value">{kpi.value}</span>
          </div>
        ))}
      </div>

      <div className="wall-body">
        {/* TOP skill 执行次数排行 */}
        <div className="top-panel" data-testid="wall-top">
          <h3>TOP{TOP_N} Skill 执行排行（24h）</h3>
          {topSkills.map((row, index) => (
            <div key={row.skillCode} className="top-row">
              <span className={`rank rank-${index + 1}`}>{index + 1}</span>
              <span className="top-skill">{row.skillCode}</span>
              <div className="bar-wrap">
                <div className="bar"
                     style={{ width: `${Math.max(6, (Number(row.count) / maxTopCount) * 100)}%` }} />
              </div>
              <span className="top-count mono">{row.count}</span>
            </div>
          ))}
          {topSkills.length === 0 && <div className="top-empty">窗口内暂无执行</div>}
        </div>

        {/* 任务流水线墙 */}
        <div className="wall-grid">
          {tasks.map((t) => (
            <div key={t.taskId} className={`cell ${t.status.toLowerCase()}`}>
              <div className="cell-head">
                <span className="mono tid">{t.taskId.slice(-14)}</span>
                <span className="skill">{t.skillCode}</span>
                <span className="badge" data-status={t.status}>{t.status}</span>
              </div>
              <StagePipeline events={t.events} status={t.status}
                             progress={t.progress} callbackUrl={null} />
              <div className="cell-foot mono">
                <span>{t.tenantId}</span>
                {t.durationMs && <span>{(t.durationMs / 1000).toFixed(1)}s</span>}
              </div>
            </div>
          ))}
          {tasks.length === 0 && <div className="empty">近 24 小时暂无任务</div>}
        </div>
      </div>
    </div>
  )
}
