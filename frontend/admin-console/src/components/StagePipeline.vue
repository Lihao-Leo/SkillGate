<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
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

const props = defineProps<Props>()

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

// 每秒心跳：驱动当前节点「已停留 X 秒」计时
const nowTick = ref(Date.now())
let timer: ReturnType<typeof setInterval> | null = null
onMounted(() => { timer = setInterval(() => { nowTick.value = Date.now() }, 1000) })
onUnmounted(() => { if (timer) clearInterval(timer) })

const stageEvent = computed(() => {
  // 每个 stage 取最后一条事件（CALLBACK 多次重试时以最终一次为准）
  const map = new Map<string, ExecutionEventView>()
  for (const e of props.events) map.set(e.stage, e)
  return map
})

const groups = computed(() => {
  const activeGroups = GROUPS.filter(g =>
      !('optional' in g && g.optional) || props.callbackUrl || props.events.some(e => g.stages.includes(e.stage)))
  const lastEvent = props.events.length ? props.events[props.events.length - 1] : null
  const terminal = ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(props.status)

  // 当前推进到哪一组：最后事件所属组的下一组（最后事件是组末尾时）；PENDING 未认领时落在沙箱组
  let currentKey: string | null = null
  if (!terminal) {
    if (props.status === 'PENDING' && !stageEvent.value.has('CLAIM')) {
      currentKey = 'sandbox'
    } else if (lastEvent) {
      const idx = activeGroups.findIndex(g => g.stages.includes(lastEvent.stage))
      const group = activeGroups[idx]
      const atGroupEnd = group ? lastEvent.stage === group.stages[group.stages.length - 1] : false
      currentKey = atGroupEnd && idx < activeGroups.length - 1
        ? activeGroups[idx + 1].key
        : group?.key ?? null
      // 组内最后事件是 FAILED → 已停在该组（红色），不再前移
      if (lastEvent.status === 'FAILED') currentKey = group?.key ?? null
    }
  }

  return activeGroups.map(g => {
    const events = g.stages.map(s => stageEvent.value.get(s)).filter(Boolean) as ExecutionEventView[]
    let state: State = 'pending'
    if (events.length) {
      const hasFail = events.some(e => e.status === 'FAILED')
      const allDone = g.stages.every(s => {
        const e = stageEvent.value.get(s)
        return e && (e.status === 'OK' || e.status === 'SKIPPED')
      })
      state = hasFail ? 'failed' : allDone ? 'success'
        : events.some(e => e.status === 'SKIPPED') && !events.some(e => e.status === 'OK' && false)
          ? 'skipped' : 'success'
      if (!hasFail && !allDone && g.key === currentKey) state = 'running'
      if (hasFail) state = 'failed'
    } else if (g.key === currentKey) {
      state = 'running'
    }

    const durationMs = events.reduce((sum, e) => sum + (e.durationMs ?? 0), 0)
    const lastTime = events.length ? events[events.length - 1].occurredAt : null
    const lastOkTime = events.filter(e => e.status !== 'FAILED').slice(-1)[0]?.occurredAt ?? lastTime
    const stuckMs = state === 'running' && lastOkTime
      ? Math.max(0, nowTick.value - new Date(lastOkTime).getTime()) : null
    return {
      key: g.key,
      label: g.label,
      state,
      durationMs,
      failedStage: events.filter(e => e.status === 'FAILED').slice(-1)[0]?.stage ?? null,
      stuckMs,
      progress: g.key === 'sandbox' && state === 'running' ? props.progress ?? null : null,
    }
  })
})

const overallFailed = computed(() => groups.value.some(g => g.state === 'failed'))

function fmtMs(ms: number | null): string {
  if (ms == null) return ''
  if (ms < 1000) return `${ms}ms`
  const s = ms / 1000
  return s < 60 ? `${s.toFixed(1)}s` : `${Math.floor(s / 60)}m${Math.round(s % 60)}s`
}

const stateText: Record<State, string> = {
  success: '完成',
  failed: '失败',
  skipped: '跳过',
  running: '进行中',
  pending: '等待',
}
</script>

<template>
  <div class="pipeline" :class="{ failed: overallFailed }" data-testid="stage-pipeline">
    <template v-for="(g, i) in groups" :key="g.key">
      <div class="node" :class="g.state">
        <div class="node-icon">
          <span v-if="g.state === 'success'" class="ico ok">✓</span>
          <span v-else-if="g.state === 'failed'" class="ico fail">✕</span>
          <span v-else-if="g.state === 'skipped'" class="ico skip">—</span>
          <span v-else-if="g.state === 'running'" class="ico run" />
          <span v-else class="ico pending" />
        </div>
        <div class="node-label">{{ g.label }}</div>
        <div class="node-sub">
          <template v-if="g.state === 'running'">
            <span class="live-dot" />已停留 {{ fmtMs(g.stuckMs) }}
            <template v-if="g.progress != null"> · 进度 {{ g.progress }}%</template>
          </template>
          <template v-else-if="g.state === 'success'">{{ fmtMs(g.durationMs) }}</template>
          <template v-else-if="g.state === 'failed'">
            <span class="neg">{{ g.failedStage ?? 'FAILED' }}</span>
          </template>
          <template v-else-if="g.state === 'skipped'">跳过</template>
          <template v-else>等待</template>
        </div>
      </div>
      <div v-if="i < groups.length - 1" class="connector"
           :class="{ filled: groups[i].state === 'success' || groups[i].state === 'skipped' }" />
    </template>
  </div>
</template>

<style scoped>
.pipeline {
  display: flex; align-items: flex-start; gap: 0;
  padding: 14px 12px 10px; margin: 6px 0 4px;
  background: #fafbfd; border: 1px solid #eef0f6; border-radius: 10px;
  overflow-x: auto;
}
.pipeline.failed { background: #fffbfa; border-color: #f4d9d6; }
.node { display: flex; flex-direction: column; align-items: center; min-width: 76px; flex: none; }
.node-icon {
  width: 34px; height: 34px; border-radius: 50%;
  display: grid; place-items: center;
  border: 2px solid #d5d9e4; background: #fff; font-size: 14px;
  transition: all .2s;
}
.node-icon .ico.ok { color: #12b76a; font-weight: 800; }
.node-icon .ico.fail { color: #f04438; font-weight: 800; }
.node-icon .ico.skip { color: #98a2b3; }
.node-icon .ico.pending { width: 8px; height: 8px; border-radius: 50%; background: #d5d9e4; }
.node.success .node-icon { border-color: #12b76a; background: #f0faf5; }
.node.failed .node-icon { border-color: #f04438; background: #feecea; }
.node.skipped .node-icon { border-color: #e5e7ee; background: #f8f9fc; opacity: .7; }
.node.running .node-icon { border-color: #2b7fff; background: #f0f6ff; }
/* 进行中：外圈脉冲 */
.node.running .ico.run {
  width: 10px; height: 10px; border-radius: 50%; background: #2b7fff;
  animation: pulse 1.4s ease-out infinite;
}
@keyframes pulse {
  0% { box-shadow: 0 0 0 0 rgba(43, 127, 255, .45); }
  70% { box-shadow: 0 0 0 10px rgba(43, 127, 255, 0); }
  100% { box-shadow: 0 0 0 0 rgba(43, 127, 255, 0); }
}
.node-label { font-size: 12px; font-weight: 700; margin-top: 6px; color: #344054; }
.node.running .node-label { color: #2b7fff; }
.node.failed .node-label { color: #f04438; }
.node-sub { font-size: 11px; color: #98a2b3; margin-top: 2px; white-space: nowrap; }
.node-sub .neg { color: #f04438; font-weight: 600; }
.live-dot {
  display: inline-block; width: 6px; height: 6px; border-radius: 50%;
  background: #2b7fff; margin-right: 3px; vertical-align: middle;
  animation: blink 1.2s infinite;
}
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: .2; } }
.connector {
  flex: 1; min-width: 22px; height: 2px; background: #e5e7ee;
  margin-top: 17px; position: relative;
}
.connector.filled { background: #12b76a; }
</style>
