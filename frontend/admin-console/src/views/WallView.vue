<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { gatewayApi } from '../api/gateway'
import StagePipeline from '../components/StagePipeline.vue'

/** 监控大屏（办公区电视）：最近任务流水线墙，10s 轮询，深色全屏。 */
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
const updated = ref('')
const PAGE_SIZE = 8

async function load() {
  try {
    const page = await gatewayApi.adminListExecutions(24, {
      pageNo: 1, pageSize: PAGE_SIZE,
    })
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
        <p>最近 {{ PAGE_SIZE }} 条任务 · 每 10 秒自动刷新<template v-if="updated"> · 更新于 {{ updated }}</template></p>
      </div>
      <button class="back" @click="$router.push('/executions')">返回管理台</button>
    </header>
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
</template>

<style scoped>
.wall {
  min-height: 100vh; margin: -20px -24px -48px;
  background: linear-gradient(160deg, #10141f 0%, #171e2e 100%);
  color: #e6eaf2; padding: 26px 30px;
}
.wall-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.wall-head h1 { font-size: 22px; margin: 0 0 4px; letter-spacing: 1px; }
.wall-head p { margin: 0; color: #8a94aa; font-size: 13px; }
.back {
  background: transparent; color: #8a94aa; border: 1px solid #2b3448;
  border-radius: 8px; padding: 7px 14px; cursor: pointer; font-size: 13px;
}
.back:hover { color: #e6eaf2; border-color: #475a7d; }
.wall-grid {
  display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px;
}
.cell {
  background: #1b2334; border: 1px solid #2b3448; border-radius: 14px;
  padding: 14px 16px;
}
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
@media (max-width: 1100px) { .wall-grid { grid-template-columns: 1fr; } }
</style>
