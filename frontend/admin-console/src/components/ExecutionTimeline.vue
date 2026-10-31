<script setup lang="ts">
import { computed } from 'vue'
import type { ExecutionEventView } from '../api/gateway'

const props = defineProps<{ events: ExecutionEventView[] }>()

// stage 中文释义（技术方案 v2.0 §1.3 词表）
const stageLabels: Record<string, string> = {
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

const serviceLabels: Record<string, string> = {
  gateway: '网关',
  worker: '执行器',
  billing: '计费',
}

const rows = computed(() => props.events.map((e, i) => {
  const prev = i > 0 ? props.events[i - 1] : null
  return {
    ...e,
    label: stageLabels[e.stage] ?? e.stage,
    serviceLabel: serviceLabels[e.service] ?? e.service,
    gapMs: prev && e.occurredAt && prev.occurredAt
      ? Math.max(0, new Date(e.occurredAt).getTime() - new Date(prev.occurredAt).getTime())
      : null,
    statusOk: e.status === 'OK',
    statusSkipped: e.status === 'SKIPPED',
  }
}))

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

const hasFailed = computed(() => props.events.some(e => e.status === 'FAILED'))
</script>

<template>
  <div class="timeline" :class="{ failed: hasFailed }" data-testid="execution-timeline">
    <div v-for="(row, i) in rows" :key="row.seq" class="tl-row" :class="{
      failed: row.status === 'FAILED',
      skipped: row.statusSkipped,
    }" :data-testid="`tl-${row.stage}`">
      <div class="tl-rail">
        <span class="tl-dot" :class="{ ok: row.statusOk, fail: row.status === 'FAILED' }" />
        <span v-if="i < rows.length - 1" class="tl-line" />
      </div>
      <div class="tl-body">
        <div class="tl-head">
          <b>{{ row.label }}</b>
          <span class="mono stage-tag">{{ row.stage }}</span>
          <span class="svc">{{ row.serviceLabel }}</span>
          <span v-if="row.status === 'FAILED'" class="tag fail-tag">FAILED</span>
          <span v-else-if="row.statusSkipped" class="tag skip-tag">SKIPPED</span>
          <span class="dur">{{ fmtDuration(row.durationMs) }}</span>
        </div>
        <div class="tl-sub">
          <span class="mono time">{{ fmtTime(row.occurredAt) }}</span>
          <span v-if="row.gapMs != null && row.gapMs > 0" class="gap">距上节点 {{ fmtDuration(row.gapMs) }}</span>
        </div>
        <div v-if="fmtDetail(row.detail)" class="tl-detail mono">{{ fmtDetail(row.detail) }}</div>
      </div>
    </div>
    <div v-if="rows.length === 0" class="dim" style="padding: 8px 0">暂无事件</div>
  </div>
</template>

<style scoped>
.timeline { display: flex; flex-direction: column; margin-top: 8px; }
.tl-row { display: flex; gap: 12px; }
.tl-row.failed .tl-body { background: #fff5f4; }
.tl-row.skipped .tl-body { opacity: 0.65; }
.tl-rail { display: flex; flex-direction: column; align-items: center; width: 14px; flex: none; }
.tl-dot {
  width: 10px; height: 10px; border-radius: 50%; margin-top: 5px; flex: none;
  background: #98a2b3;
}
.tl-dot.ok { background: #12b76a; }
.tl-dot.fail { background: #f04438; box-shadow: 0 0 0 3px #feecea; }
.tl-line { width: 2px; flex: 1; background: #e9ebf2; min-height: 14px; }
.tl-body { flex: 1; padding: 2px 10px 10px 2px; border-radius: 8px; }
.tl-head { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.tl-head b { font-weight: 700; }
.stage-tag {
  font-size: 11px; color: #667085; background: #f1f3f8;
  border-radius: 4px; padding: 1px 6px;
}
.svc { color: #98a2b3; font-size: 11px; }
.tag { font-size: 11px; border-radius: 999px; padding: 1px 8px; }
.fail-tag { background: #feecea; color: #f04438; }
.skip-tag { background: #f1f3f8; color: #98a2b3; }
.dur { margin-left: auto; font-weight: 700; font-size: 12px; color: #475467; }
.tl-sub { display: flex; gap: 10px; font-size: 11px; color: #98a2b3; margin-top: 1px; }
.gap { color: #c2c7d0; }
.tl-detail {
  font-size: 11px; color: #667085; background: #f8f9fc;
  border-radius: 6px; padding: 3px 8px; margin-top: 4px;
  word-break: break-all; white-space: pre-wrap;
}
.mono { font-family: ui-monospace, Menlo, monospace; }
.dim { color: #98a2b3; font-size: 12px; }
</style>
