<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { gatewayApi } from '../api/gateway'
import StagePipeline from '../components/StagePipeline.vue'

/**
 * 监控大屏（V2.1 指标化）：KPI 卡行（运行总数/运行中/成功率/失败数/平均耗时/token 费用/
 * 待处理告警）+ TOP skill 执行次数排行 + 任务流水线墙。10s 轮询，深色全屏。
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

const tasks = ref<WallTask[]>([])
const board = ref<any>(null)
const pendingAlerts = ref(0)
const updated = ref('')
const PAGE_SIZE = 8
const TOP_N = 10

const topSkills = computed<any[]>(() => board.value?.skillUsageTop ?? [])

interface Kpi {
  key: string
  label: string
  value: string
  tone: 'blue' | 'green' | 'red' | 'amber' | 'plain'
  pulse?: boolean
}

const kpis = computed<Kpi[]>(() => {
  const b = board.value ?? {}
  const cost = typeof b.costPointsWindow === 'number' ? b.costPointsWindow : 0
  const avgSec = b.avgDurationMsWindow ? (b.avgDurationMsWindow / 1000).toFixed(1) : '0.0'
  return [
    { key: 'total', label: '运行总数（窗口）', value: String(b.totalToday ?? 0), tone: 'blue' },
    { key: 'running', label: '运行中', value: String(b.running ?? 0), tone: 'blue',
      pulse: Number(b.running ?? 0) > 0 },
    { key: 'successRate', label: '成功率',
      value: b.successRateToday == null ? '—' : `${b.successRateToday}%`, tone: 'green' },
    { key: 'failed', label: '失败数', value: String(b.failedToday ?? b.statusCountsToday?.FAILED ?? 0),
      tone: 'red', pulse: Number(b.failedToday ?? 0) > 0 },
    { key: 'avgDuration', label: '平均耗时', value: `${avgSec}s`, tone: 'plain' },
    { key: 'tokenCost', label: 'Token 费用（点）', value: cost.toFixed(2), tone: 'amber' },
    { key: 'pendingAlerts', label: '待处理告警', value: String(pendingAlerts.value),
      tone: pendingAlerts.value > 0 ? 'red' : 'plain', pulse: pendingAlerts.value > 0 },
  ]
})

const maxTopCount = computed(() =>
  Math.max(1, ...topSkills.value.map((row) => Number(row.count ?? 0))))

async function load() {
  try {
    const [page, metrics] = await Promise.all([
      gatewayApi.adminListExecutions(24, { pageNo: 1, pageSize: PAGE_SIZE }),
      gatewayApi.adminMetricsDashboard(24, TOP_N),
    ])
    board.value = metrics
    const rows = page.records
    const loaded: WallTask[] = []
    for (const row of rows) {
      let events: any[] = []
      try {
        events = await gatewayApi.adminExecutionEvents(row.taskId)
      } catch { /* 单条失败不影响整墙 */ }
      loaded.push({ ...row, events })
    }
    tasks.value = loaded
    try {
      pendingAlerts.value = (await gatewayApi.adminAlertPendingCount()).count
    } catch { /* 徽标失败容忍 */ }
    updated.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch { /* 网络抖动保留上一帧 */ }
}

const timer = setInterval(load, 10000)
onMounted(load)
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="wall">
    <header class="wall-head">
      <div>
        <h1>SkillGate · 执行监控大屏</h1>
        <p>KPI + TOP{{ TOP_N }} Skill 排行 + 最近 {{ PAGE_SIZE }} 条任务 · 每 10 秒自动刷新<template v-if="updated"> · 更新于 {{ updated }}</template></p>
      </div>
      <button class="back" @click="$router.push('/executions')">返回管理台</button>
    </header>

    <!-- KPI 卡行（7 项） -->
    <div class="kpi-row" data-testid="wall-kpis">
      <div v-for="kpi in kpis" :key="kpi.key" class="kpi" :data-tone="kpi.tone"
           :class="{ pulse: kpi.pulse }" :data-testid="`kpi-${kpi.key}`">
        <span class="kpi-label">{{ kpi.label }}</span>
        <span class="kpi-value">{{ kpi.value }}</span>
      </div>
    </div>

    <div class="wall-body">
      <!-- TOP skill 执行次数排行 -->
      <div class="top-panel" data-testid="wall-top">
        <h3>TOP{{ TOP_N }} Skill 执行排行（24h）</h3>
        <div v-for="(row, index) in topSkills" :key="row.skillCode" class="top-row">
          <span class="rank" :class="`rank-${index + 1}`">{{ index + 1 }}</span>
          <span class="top-skill">{{ row.skillCode }}</span>
          <div class="bar-wrap">
            <div class="bar" :style="{ width: `${Math.max(6, (Number(row.count) / maxTopCount) * 100)}%` }" />
          </div>
          <span class="top-count mono">{{ row.count }}</span>
        </div>
        <div v-if="topSkills.length === 0" class="top-empty">窗口内暂无执行</div>
      </div>

      <!-- 任务流水线墙 -->
      <div class="wall-grid">
        <div v-for="t in tasks" :key="t.taskId" class="cell" :class="t.status.toLowerCase()">
          <div class="cell-head">
            <span class="mono tid">{{ t.taskId.slice(-14) }}</span>
            <span class="skill">{{ t.skillCode }}</span>
            <span class="badge" :data-status="t.status">{{ t.status }}</span>
          </div>
          <StagePipeline :events="t.events" :status="t.status"
                         :progress="t.progress" :callback-url="null" />
          <div class="cell-foot mono">
            <span>{{ t.tenantId }}</span>
            <span v-if="t.durationMs">{{ (t.durationMs / 1000).toFixed(1) }}s</span>
          </div>
        </div>
        <div v-if="tasks.length === 0" class="empty">近 24 小时暂无任务</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.wall {
  min-height: 100vh; margin: -20px -24px -48px;
  background: linear-gradient(160deg, #10141f 0%, #171e2e 100%);
  color: #e6eaf2; padding: 26px 30px;
}
.wall-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 18px; }
.wall-head h1 { font-size: 22px; margin: 0 0 4px; letter-spacing: 1px; }
.wall-head p { margin: 0; color: #8a94aa; font-size: 13px; }
.back {
  background: transparent; color: #8a94aa; border: 1px solid #2b3448;
  border-radius: 8px; padding: 7px 14px; cursor: pointer; font-size: 13px;
}
.back:hover { color: #e6eaf2; border-color: #475a7d; }

/* KPI 卡行 */
.kpi-row { display: grid; grid-template-columns: repeat(7, 1fr); gap: 12px; margin-bottom: 18px; }
.kpi {
  background: #1b2334; border: 1px solid #2b3448; border-radius: 12px;
  padding: 12px 16px; display: flex; flex-direction: column; gap: 6px;
}
.kpi-label { font-size: 12px; color: #8a94aa; }
.kpi-value { font-size: 24px; font-weight: 700; font-family: ui-monospace, Menlo, monospace; }
.kpi[data-tone='blue'] .kpi-value { color: #60a5fa; }
.kpi[data-tone='green'] .kpi-value { color: #34d399; }
.kpi[data-tone='red'] .kpi-value { color: #f87171; }
.kpi[data-tone='amber'] .kpi-value { color: #fbbf24; }
.kpi.pulse { animation: pulse 1.6s ease-in-out infinite; }
@keyframes pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(240, 68, 56, 0); }
  50% { box-shadow: 0 0 0 4px rgba(240, 68, 56, 0.25); }
}

.wall-body { display: grid; grid-template-columns: 320px 1fr; gap: 16px; }

/* TOP 排行 */
.top-panel {
  background: #1b2334; border: 1px solid #2b3448; border-radius: 14px;
  padding: 16px 18px; align-self: start;
}
.top-panel h3 { margin: 0 0 14px; font-size: 15px; }
.top-row { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.rank {
  width: 22px; height: 22px; border-radius: 7px; flex: none;
  display: grid; place-items: center; font-size: 12px; font-weight: 700;
  background: #232d42; color: #8a94aa;
}
.rank-1 { background: #f5c542; color: #10141f; }
.rank-2 { background: #c0c8d4; color: #10141f; }
.rank-3 { background: #d09a6a; color: #10141f; }
.top-skill { width: 108px; font-size: 12.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bar-wrap { flex: 1; background: #141b29; border-radius: 5px; height: 14px; overflow: hidden; }
.bar { height: 100%; background: linear-gradient(90deg, #2b7fff, #6aa5ff); border-radius: 5px; }
.top-count { width: 44px; text-align: right; font-size: 12px; color: #c3cbd9; }
.top-empty { color: #8a94aa; text-align: center; padding: 30px 0; font-size: 13px; }

/* 任务墙 */
.wall-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px; align-content: start; }
.cell { background: #1b2334; border: 1px solid #2b3448; border-radius: 14px; padding: 14px 16px; }
.cell.succeeded { border-color: #1e5b43; }
.cell.failed { border-color: #7d2f2a; background: #2a1d20; }
.cell-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; font-size: 13px; }
.cell-head .tid { color: #8a94aa; }
.cell-head .skill { flex: 1; font-weight: 600; }
.badge { font-size: 11px; border-radius: 6px; padding: 2px 8px; font-weight: 700; }
.badge[data-status='SUCCEEDED'] { background: #12301f; color: #34d399; }
.badge[data-status='FAILED'] { background: #3c1815; color: #f87171; }
.badge[data-status='RUNNING'], .badge[data-status='PENDING'] { background: #16304f; color: #60a5fa; }
.badge[data-status='CANCELLED'] { background: #2a2f3c; color: #98a2b3; }
.cell-foot { display: flex; justify-content: space-between; margin-top: 8px; font-size: 11px; color: #8a94aa; }
.empty { grid-column: 1 / -1; text-align: center; color: #8a94aa; padding: 80px 0; font-size: 15px; }
.cell :deep(.pipeline) { background: #141b29; border-color: #2b3448; }
.cell :deep(.node-label) { color: #c3cbd9; }
.cell :deep(.node.running .node-label) { color: #60a5fa; }
.cell :deep(.node.failed .node-label) { color: #f87171; }
.cell :deep(.node-sub) { color: #6d7891; }
.mono { font-family: ui-monospace, Menlo, monospace; }
@media (max-width: 1100px) { .kpi-row { grid-template-columns: repeat(4, 1fr); } .wall-body { grid-template-columns: 1fr; } .wall-grid { grid-template-columns: 1fr; } }
</style>
