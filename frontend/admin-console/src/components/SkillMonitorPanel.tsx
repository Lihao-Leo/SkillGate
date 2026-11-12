import { useEffect, useState } from 'react'
import { Button, Space, Table, Tag } from 'antd'
import { gatewayApi } from '../api/gateway'
import type { SkillMonitorView } from '../api/gateway'

/**
 * 单 Skill 监控面板（V2.3，Skills 抽屉「监控」Tab）：
 * 汇总卡 + 按日柱条 + 版本分布。打开时懒加载（挂载即拉取，时间窗可切换）。
 */
export default function SkillMonitorPanel({ tenantId, skillCode }: {
  tenantId: string
  skillCode: string
}) {
  void tenantId // 聚合走 skill_code 维度（idx_skill_created），tenant 由调用方保证
  const [hours, setHours] = useState(168)
  const [view, setView] = useState<SkillMonitorView | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    gatewayApi.adminSkillMonitor(tenantId, skillCode, hours)
      .then((data) => { if (!cancelled) setView(data) })
      .catch(() => { /* 监控失败容忍 */ })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [tenantId, skillCode, hours])

  const s = view?.summary
  const maxDaily = Math.max(1, ...(view?.daily ?? []).map((d) => Number(d.total)))
  const daily = view?.daily ?? []

  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        {([24, 168, 720] as const).map((h) => (
          <Button key={h} size="small" type={hours === h ? 'primary' : 'default'}
                  onClick={() => setHours(h)}>
            {h === 24 ? '24h' : h === 168 ? '7d' : '30d'}
          </Button>
        ))}
      </Space>
      <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(4, 1fr)' }}>
        <div className="stat-card"><div>
          <div className="label">总执行</div>
          <div className="value">{s?.total ?? '—'}</div>
        </div></div>
        <div className="stat-card"><div>
          <div className="label">成功率</div>
          <div className="value">{s?.successRate == null ? '—' : `${s.successRate}%`}</div>
        </div></div>
        <div className="stat-card"><div>
          <div className="label">平均耗时</div>
          <div className="value">{Math.round(s?.avgDurationMs ?? 0)} <small>ms</small></div>
        </div></div>
        <div className="stat-card"><div>
          <div className="label">消耗点数</div>
          <div className="value">{Number(view?.costPoints ?? 0).toFixed(4)}</div>
        </div></div>
      </div>

      <h4 style={{ margin: '12px 0 8px' }}>按日执行量</h4>
      {(view?.daily ?? []).length === 0 && (
        <div style={{ color: 'var(--ant-color-text-tertiary)', fontSize: 12.5, padding: '8px 0' }}>
          时间窗内暂无执行
        </div>
      )}
      {daily.map((d) => (
        <div key={String(d.day)} className="bar-row">
          <span className="bar-label" style={{ width: 90 }}>{String(d.day).slice(5)}</span>
          <div className="bar-track">
            <div className="bar-fill" style={{
              width: `${Math.max(4, (Number(d.total) / maxDaily) * 100)}%`,
              background: Number(d.failed) > 0 ? '#f04438' : '#12b76a',
            }} />
          </div>
          <span className="bar-val">{d.total}（败 {d.failed}）</span>
        </div>
      ))}

      <h4 style={{ margin: '12px 0 8px' }}>版本分布</h4>
      <Table
        size="small"
        rowKey="skill_version"
        pagination={false}
        loading={loading}
        dataSource={view?.versions ?? []}
        data-testid="skill-monitor-versions"
      >
        <Table.Column title="版本" dataIndex="skill_version" width={110} />
        <Table.Column title="调用数" dataIndex="cnt" width={90} align="right" />
        <Table.Column title="成功" dataIndex="succeeded" width={80} align="right" />
        <Table.Column title="平均耗时" dataIndex="avgDurationMs" width={110} align="right"
                      render={(v: number) => `${Math.round(Number(v))}ms`} />
      </Table>
    </div>
  )
}
