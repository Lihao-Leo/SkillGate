import { useMemo } from 'react'
import type { ExecutionEventView } from '../api/gateway'

// stage 中文释义（技术方案 v2.0 §1.3 词表）
const STAGE_LABELS: Record<string, string> = {
  ACCEPT: '受理入口',
  AUTH: '鉴权',
  RATE_LIMIT: '限流/配额',
  FREEZE: '计费冻结',
  ENQUEUE: '调度入队',
  CLAIM: '任务认领',
  STAGE: '工作区准备',
  SANDBOX: '沙箱执行',
  COLLECT: '产物采集',
  ARTIFACT_PUT: '产物落存储',
  SETTLE: '结算',
  FINALIZE: '置终态',
  CALLBACK: '回调投递',
  WATCHDOG: '看门狗干预',
  KB_RETRIEVE: 'KB 检索',
}

const SERVICE_LABELS: Record<string, string> = {
  gateway: '网关',
  worker: '执行器',
  billing: '计费',
}

function fmtDuration(ms: number | null | undefined): string {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms}ms`
  const s = ms / 1000
  return s < 60 ? `${s.toFixed(1)}s` : `${Math.floor(s / 60)}m${(s % 60).toFixed(0)}s`
}

function fmtTime(value: string | null | undefined): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(11, 23)
}

function fmtDetail(detail: Record<string, unknown> | null | undefined): string {
  if (!detail) return ''
  const parts = Object.entries(detail)
    .filter(([, v]) => v !== null && v !== undefined && v !== '')
    .map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : String(v)}`)
  return parts.join(' · ')
}

export default function ExecutionTimeline({ events }: { events: ExecutionEventView[] }) {
  const rows = useMemo(() => events.map((e, i) => {
    const prev = i > 0 ? events[i - 1] : null
    return {
      ...e,
      label: STAGE_LABELS[e.stage] ?? e.stage,
      serviceLabel: SERVICE_LABELS[e.service] ?? e.service,
      gapMs: prev && e.occurredAt && prev.occurredAt
        ? Math.max(0, new Date(e.occurredAt).getTime() - new Date(prev.occurredAt).getTime())
        : null,
    }
  }), [events])

  const hasFailed = events.some((e) => e.status === 'FAILED')

  return (
    <div className={`timeline${hasFailed ? ' failed' : ''}`} data-testid="execution-timeline">
      {rows.map((row, i) => (
        <div
          key={row.seq}
          className={`tl-row${row.status === 'FAILED' ? ' failed' : ''}${
            row.status === 'SKIPPED' ? ' skipped' : ''}`}
          data-testid={`tl-${row.stage}`}
        >
          <div className="tl-rail">
            <span className={`tl-dot${row.status === 'OK' ? ' ok' : ''}${
              row.status === 'FAILED' ? ' fail' : ''}`} />
            {i < rows.length - 1 && <span className="tl-line" />}
          </div>
          <div className="tl-body">
            <div className="tl-head">
              <b>{row.label}</b>
              <span className="mono stage-tag">{row.stage}</span>
              <span className="svc">{row.serviceLabel}</span>
              {row.status === 'FAILED' && <span className="tag fail-tag">FAILED</span>}
              {row.status === 'SKIPPED' && <span className="tag skip-tag">SKIPPED</span>}
              <span className="dur">{fmtDuration(row.durationMs)}</span>
            </div>
            <div className="tl-sub">
              <span className="mono time">{fmtTime(row.occurredAt)}</span>
              {row.gapMs != null && row.gapMs > 0 && (
                <span className="gap">距上节点 {fmtDuration(row.gapMs)}</span>
              )}
            </div>
            {fmtDetail(row.detail) && (
              <div className="tl-detail mono">{fmtDetail(row.detail)}</div>
            )}
          </div>
        </div>
      ))}
      {rows.length === 0 && <div className="dim" style={{ padding: '8px 0' }}>暂无事件</div>}
    </div>
  )
}
