import { Fragment, useEffect, useMemo, useState } from 'react'
import type { ExecutionEventView } from '../api/gateway'

/**
 * CI/CD 风格动态节点流程图（数据源：execution_event，纯 MySQL，零 Jaeger 依赖）。
 * RUNNING 任务每 5s 轮询（ExecutionsView 已有），当前节点脉冲动画 + 实时耗时。
 */

interface Props {
  events: ExecutionEventView[]
  status: string
  progress?: number | null
  callbackUrl?: string | null
}

// 流水线阶段组（组内为子步骤；词表见技术方案 §1.3）
interface StageGroup {
  key: string
  label: string
  stages: string[]
  optional?: boolean
}

const GROUPS: StageGroup[] = [
  { key: 'accept', label: '受理调度', stages: ['ACCEPT', 'AUTH', 'RATE_LIMIT', 'FREEZE', 'ENQUEUE'] },
  { key: 'sandbox', label: '沙箱执行', stages: ['CLAIM', 'STAGE', 'SANDBOX'] },
  { key: 'artifact', label: '产物', stages: ['COLLECT', 'ARTIFACT_PUT'] },
  { key: 'settle', label: '结算', stages: ['SETTLE'] },
  { key: 'finalize', label: '终态', stages: ['FINALIZE'] },
  { key: 'callback', label: '回调', stages: ['CALLBACK'], optional: true },
]

type State = 'success' | 'failed' | 'skipped' | 'running' | 'pending'

/** 每秒心跳：驱动当前节点「已停留 X 秒」计时（useEffect + cleanup，StrictMode 安全）。 */
function useNowTick(intervalMs: number): number {
  const [tick, setTick] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setTick(Date.now()), intervalMs)
    return () => clearInterval(timer)
  }, [intervalMs])
  return tick
}

function fmtMs(ms: number | null): string {
  if (ms == null) return ''
  if (ms < 1000) return `${ms}ms`
  const s = ms / 1000
  return s < 60 ? `${s.toFixed(1)}s` : `${Math.floor(s / 60)}m${Math.round(s % 60)}s`
}

const STATE_TEXT: Record<State, string> = {
  success: '完成',
  failed: '失败',
  skipped: '跳过',
  running: '进行中',
  pending: '等待',
}

export default function StagePipeline({ events, status, progress, callbackUrl }: Props) {
  const nowTick = useNowTick(1000)

  const stageEvent = useMemo(() => {
    // 每个 stage 取最后一条事件（CALLBACK 多次重试时以最终一次为准）
    const map = new Map<string, ExecutionEventView>()
    for (const e of events) map.set(e.stage, e)
    return map
  }, [events])

  const groups = useMemo(() => {
    const activeGroups = GROUPS.filter((g) =>
      !g.optional || callbackUrl || events.some((e) => g.stages.includes(e.stage)))
    const lastEvent = events.length ? events[events.length - 1] : null
    const terminal = ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(status)

    // 当前推进到哪一组：最后事件所属组的下一组（最后事件是组末尾时）；PENDING 未认领时落在沙箱组
    let currentKey: string | null = null
    if (!terminal) {
      if (status === 'PENDING' && !stageEvent.has('CLAIM')) {
        currentKey = 'sandbox'
      } else if (lastEvent) {
        const idx = activeGroups.findIndex((g) => g.stages.includes(lastEvent.stage))
        const group = activeGroups[idx]
        const atGroupEnd = group ? lastEvent.stage === group.stages[group.stages.length - 1] : false
        currentKey = atGroupEnd && idx < activeGroups.length - 1
          ? activeGroups[idx + 1].key
          : group?.key ?? null
        // 组内最后事件是 FAILED → 已停在该组（红色），不再前移
        if (lastEvent.status === 'FAILED') currentKey = group?.key ?? null
      }
    }

    return activeGroups.map((g) => {
      const groupEvents = g.stages.map((s) => stageEvent.get(s))
        .filter((e): e is ExecutionEventView => Boolean(e))
      let state: State = 'pending'
      if (groupEvents.length) {
        const hasFail = groupEvents.some((e) => e.status === 'FAILED')
        const allDone = g.stages.every((s) => {
          const e = stageEvent.get(s)
          return e && (e.status === 'OK' || e.status === 'SKIPPED')
        })
        state = hasFail ? 'failed'
          : allDone ? 'success'
          : groupEvents.some((e) => e.status === 'SKIPPED') ? 'skipped' : 'success'
        if (!hasFail && !allDone && g.key === currentKey) state = 'running'
        if (hasFail) state = 'failed'
      } else if (g.key === currentKey) {
        state = 'running'
      }

      const durationMs = groupEvents.reduce((sum, e) => sum + (e.durationMs ?? 0), 0)
      const lastTime = groupEvents.length ? groupEvents[groupEvents.length - 1].occurredAt : null
      const lastOkTime = groupEvents.filter((e) => e.status !== 'FAILED')
        .slice(-1)[0]?.occurredAt ?? lastTime
      const stuckMs = state === 'running' && lastOkTime
        ? Math.max(0, nowTick - new Date(lastOkTime).getTime()) : null
      return {
        key: g.key,
        label: g.label,
        state,
        durationMs,
        failedStage: groupEvents.filter((e) => e.status === 'FAILED').slice(-1)[0]?.stage ?? null,
        stuckMs,
        progress: g.key === 'sandbox' && state === 'running' ? progress ?? null : null,
      }
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [events, status, progress, callbackUrl, stageEvent, nowTick])

  const overallFailed = groups.some((g) => g.state === 'failed')

  return (
    <div className={`pipeline${overallFailed ? ' failed' : ''}`} data-testid="stage-pipeline">
      {groups.map((g, i) => (
        <Fragment key={g.key}>
          <div className={`node ${g.state}`}>
            <div className="node-icon">
              {g.state === 'success' && <span className="ico ok">✓</span>}
              {g.state === 'failed' && <span className="ico fail">✕</span>}
              {g.state === 'skipped' && <span className="ico skip">—</span>}
              {g.state === 'running' && <span className="ico run" />}
              {g.state === 'pending' && <span className="ico pending" />}
            </div>
            <div className="node-label">{g.label}</div>
            <div className="node-sub">
              {g.state === 'running' && (
                <>
                  <span className="live-dot" />已停留 {fmtMs(g.stuckMs)}
                  {g.progress != null && <> · 进度 {g.progress}%</>}
                </>
              )}
              {g.state === 'success' && fmtMs(g.durationMs)}
              {g.state === 'failed' && <span className="neg">{g.failedStage ?? 'FAILED'}</span>}
              {g.state === 'skipped' && '跳过'}
              {g.state === 'pending' && STATE_TEXT.pending}
            </div>
          </div>
          {i < groups.length - 1 && (
            <div
              className={`connector${
                g.state === 'success' || g.state === 'skipped' ? ' filled' : ''}`}
            />
          )}
        </Fragment>
      ))}
    </div>
  )
}
