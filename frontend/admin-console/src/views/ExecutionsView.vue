<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { gatewayApi, type ExecutionEventView, type ExecutionView } from '../api/gateway'
import ExecutionTimeline from '../components/ExecutionTimeline.vue'
import StagePipeline from '../components/StagePipeline.vue'

const taskId = ref('')
const execution = ref<ExecutionView | null>(null)
const loading = ref(false)

// ---------- 看板 ----------
const range = ref<24 | 168 | 720>(24)
const board = ref<any>(null)
const recent = reactive({ pageNo: 1, pageSize: 10, items: [] as any[], total: 0 })
const activeTab = ref('list')
const recentLoading = ref(false)

// ---------- 列表筛选 ----------
const filters = reactive({ status: '', skill: '', tenant: '', onlyFailed: false })

async function loadBoard() {
  try {
    board.value = await gatewayApi.adminMetricsDashboard(range.value)
  } catch {
    board.value = null
  }
}

async function loadRecent() {
  recentLoading.value = true
  try {
    const page = await gatewayApi.adminListExecutions(range.value, {
      status: filters.onlyFailed ? '' : filters.status,
      skill: filters.skill.trim() || undefined,
      tenant: filters.tenant.trim() || undefined,
      onlyFailed: filters.onlyFailed,
      pageNo: recent.pageNo,
      pageSize: recent.pageSize,
    })
    recent.items = page.records
    recent.total = page.total
  } catch {
    recent.items = []
  } finally {
    recentLoading.value = false
  }
}

function applyFilters() {
  recent.pageNo = 1
  loadRecent()
}

function setRange(r: 24 | 168 | 720) {
  range.value = r
  loadBoard()
  loadRecent()
}

onMounted(() => { loadBoard(); loadRecent() })

// ---------- 任务详情（右侧抽屉） ----------
const drawerVisible = ref(false)

async function queryById(id: string) {
  loading.value = true
  execution.value = null
  drawerVisible.value = true
  try {
    // 后管为全平台视角：详情走 admin 端点（无租户过滤），与任务列表口径一致
    execution.value = await gatewayApi.adminExecutionDetail(id)
    await loadEvents(id)
    armRefresh(id)
  } catch (e) {
    ElMessage.error((e as Error).message)
    drawerVisible.value = false
  } finally {
    loading.value = false
  }
}

function onDrawerClosed() {
  stopRefresh()
  execution.value = null
  events.value = []
}

// ---------- 执行链路（时间线 + RUNNING 自动刷新） ----------
const events = ref<ExecutionEventView[]>([])
let refreshTimer: ReturnType<typeof setInterval> | null = null

// ---------- 入参查看 ----------
const inputVisible = ref(false)
const inputLoading = ref(false)
const inputJson = ref('')

async function showInput() {
  if (!execution.value) return
  inputVisible.value = true
  inputLoading.value = true
  try {
    const data = await gatewayApi.adminExecutionInput(execution.value.taskId)
    inputJson.value = JSON.stringify(data, null, 2)
  } catch (e) {
    inputVisible.value = false
    ElMessage.error((e as Error).message)
  } finally {
    inputLoading.value = false
  }
}

async function copyInput() {
  try {
    await navigator.clipboard.writeText(inputJson.value)
    ElMessage.success('已复制入参 JSON')
  } catch {
    ElMessage.error('复制失败（浏览器剪贴板权限）')
  }
}

function stopRefresh() {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}

async function loadEvents(id: string) {
  try {
    events.value = await gatewayApi.adminExecutionEvents(id)
  } catch {
    events.value = []
  }
}

/** 非终态任务每 5s 刷新状态与事件链；终态即停 */
function armRefresh(id: string) {
  stopRefresh()
  const status = execution.value?.status
  if (status === 'PENDING' || status === 'RUNNING' || status === 'CANCELLING') {
    refreshTimer = setInterval(async () => {
      if (!execution.value) return stopRefresh()
      try {
        execution.value = await gatewayApi.adminExecutionDetail(id)
        await loadEvents(id)
        const now = execution.value.status
        if (now === 'SUCCEEDED' || now === 'FAILED' || now === 'CANCELLED') stopRefresh()
      } catch { /* 查询抖动忽略 */ }
    }, 5000)
  }
}

onUnmounted(stopRefresh)

function query() {
  if (!taskId.value.trim()) return
  queryById(taskId.value.trim())
}

function pick(row: any) {
  taskId.value = row.taskId
  queryById(row.taskId)
}

/** 失败任务重试：按原入参重放一次完整受理（新 taskId） */
async function retryTask(taskIdToRetry: string) {
  try {
    const result = await gatewayApi.retryExecution(taskIdToRetry)
    ElMessage.success(`已重新受理：${result.taskId}`)
    taskId.value = result.taskId
    await queryById(result.taskId)
    await loadRecent()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function cancelTask(taskIdToCancel: string) {
  try {
    const result = await gatewayApi.cancelExecution(taskIdToCancel)
    ElMessage.success(result.cancelled ? '已受理取消' : `当前状态 ${result.status} 不可取消`)
    if (execution.value?.taskId === taskIdToCancel) await queryById(taskIdToCancel)
    await loadRecent()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

const statusMeta: Record<string, { label: string; color: string }> = {
  PENDING: { label: 'PENDING', color: '#98a2b3' },
  RUNNING: { label: 'RUNNING', color: '#2b7fff' },
  CANCELLING: { label: 'CANCELLING', color: '#f79009' },
  SUCCEEDED: { label: 'SUCCEEDED', color: '#12b76a' },
  FAILED: { label: 'FAILED', color: '#f04438' },
  CANCELLED: { label: 'CANCELLED', color: '#98a2b3' },
}

function fmtDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

function fmtDuration(ms: number | null | undefined): string {
  if (!ms) return '—'
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s}s`
  return `${Math.floor(s / 60)}m${String(s % 60).padStart(2, '0')}s`
}

// ---------- 迷你趋势 sparkline（已随统计卡修正移除） ----------

const rangeLabel = computed(() => range.value === 24 ? '今日' : range.value === 168 ? '近 7 天' : '近 30 天')

// ---------- 吞吐折线图 ----------
const throughput = computed<any[]>(() => board.value?.throughput ?? [])
const chartW = 640
const chartH = 170
function linePath(key: 'accepted' | 'completed'): string {
  const values = throughput.value.map(t => Number(t[key]))
  if (!values.length) return ''
  const max = Math.max(...values, 4)
  const step = (chartW - 40) / Math.max(values.length - 1, 1)
  return values.map((v, i) =>
    `${i === 0 ? 'M' : 'L'}${(20 + i * step).toFixed(1)},${(chartH - 26 - (v / max) * (chartH - 46)).toFixed(1)}`).join(' ')
}
const hourLabels = computed(() => {
  const values = throughput.value
  if (!values.length) return []
  return values.filter((_, i) => i % Math.ceil(values.length / 5) === 0).map(t => t.hour)
})

// ---------- 状态分布横条 ----------
const statusBars = computed(() => {
  const counts = board.value?.statusCountsToday
  if (!counts) return []
  const total = Math.max(Number(counts.SUCCEEDED ?? 0) + Number(counts.RUNNING ?? 0)
    + Number(counts.FAILED ?? 0) + Number(counts.PENDING ?? 0), 1)
  return [
    { label: 'SUCCEEDED', value: Number(counts.SUCCEEDED ?? 0), color: '#12b76a' },
    { label: 'RUNNING', value: Number(counts.RUNNING ?? 0), color: '#2b7fff' },
    { label: 'FAILED', value: Number(counts.FAILED ?? 0), color: '#f04438' },
    { label: 'PENDING', value: Number(counts.PENDING ?? 0), color: '#98a2b3' },
  ].map(item => ({ ...item, pct: Math.round((item.value / total) * 100) }))
})

// ---------- Skill 用量 Top5 ----------
const skillTop = computed<any[]>(() => board.value?.skillUsageTop ?? [])
const maxTop = computed(() => Math.max(...skillTop.value.map(s => Number(s.count)), 1))

// ---------- 失败原因 Top5 ----------
const failureTop = computed<any[]>(() => board.value?.failureTop ?? [])
const failureTopMax = computed(() => Math.max(...failureTop.value.map(f => Number(f.count)), 1))

// ---------- 异常处理台 ----------
const deadLetters = computed(() => recent.items.filter(i => i.callbackStatus === 'FAILED'))

async function resendCallback(taskIdToResend: string) {
  try {
    await gatewayApi.adminResendCallback(taskIdToResend)
    ElMessage.success('已重投回调')
    await loadRecent()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
async function giveUpCallback(taskIdToGiveUp: string) {
  try {
    await gatewayApi.adminGiveUpCallback(taskIdToGiveUp)
    ElMessage.success('已放弃补投')
    await loadRecent()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>

<template>
  <div class="page-header">
    <div>
      <h2>执行监控</h2>
      <p class="sub">skill-execute 链路实时视图 · 数据延迟 &lt; 30s</p>
    </div>
    <div class="range-pills">
      <button class="pill" :class="{ active: range === 24 }" @click="setRange(24)">今日</button>
      <button class="pill" :class="{ active: range === 168 }" @click="setRange(168)">近 7 天</button>
      <button class="pill" :class="{ active: range === 720 }" @click="setRange(720)">近 30 天</button>
      <button class="pill wall-link" data-testid="wall-link" @click="$router.push('/wall')">📺 大屏模式</button>
    </div>
  </div>

  <!-- 4 统计卡（sparkline 已移除：原曲线复用吞吐数据，语义不实） -->
  <div class="stat-grid">
    <div class="stat-card">
      <div>
        <div class="label">队列深度（PENDING）</div>
        <div class="value">{{ board?.queueDepth ?? '—' }} <small>条</small></div>
        <div class="foot ok">正常 <span class="dim">阈值 1000</span></div>
      </div>
    </div>
    <div class="stat-card">
      <div>
        <div class="label">PENDING 年龄 P99</div>
        <div class="value">{{ board?.pendingAgeP99Minutes ?? '—' }} <small>min</small></div>
        <div class="foot ok">正常 <span class="dim">阈值 5min</span></div>
      </div>
    </div>
    <div class="stat-card">
      <div>
        <div class="label">成功率（{{ rangeLabel }}）</div>
        <div class="value">{{ board?.successRateToday ?? '—' }} <small>%</small></div>
        <div class="foot ok">▲ 完成 {{ board?.statusCountsToday?.SUCCEEDED ?? 0 }} · ▼ 失败 {{ board?.statusCountsToday?.FAILED ?? 0 }}</div>
      </div>
    </div>
    <div class="stat-card">
      <div>
        <div class="label">失败 Top 错误码（{{ rangeLabel }}）</div>
        <div class="value mono" style="font-size: 18px">{{ failureTop[0]?.errorCode ?? '—' }}</div>
        <div class="foot warn">{{ failureTop[0] ? `× ${failureTop[0].count}` : '无失败' }}
          <span v-if="failureTop.length > 1" class="dim">共 {{ failureTop.length }} 种 · 明细见状态分布</span></div>
      </div>
    </div>
  </div>

  <!-- 吞吐图 + 状态分布 -->
  <el-row :gutter="14" class="chart-row">
    <el-col :span="13">
      <el-card header="任务吞吐（受理 / 完成）">
        <svg :viewBox="`0 0 ${chartW} ${chartH}`" class="chart">
          <defs>
            <linearGradient id="fillBlue" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stop-color="#2b7fff" stop-opacity="0.18" />
              <stop offset="100%" stop-color="#2b7fff" stop-opacity="0" />
            </linearGradient>
          </defs>
          <path v-if="throughput.length > 1" :d="`${linePath('accepted')} L${chartW - 20},${chartH - 26} L20,${chartH - 26} Z`" fill="url(#fillBlue)" stroke="none" />
          <path v-if="throughput.length > 1" :d="linePath('accepted')" fill="none" stroke="#2b7fff" stroke-width="2" />
          <path v-if="throughput.length > 1" :d="linePath('completed')" fill="none" stroke="#12b76a" stroke-width="2" />
          <text v-if="throughput.length > 1" v-for="(label, i) in hourLabels" :key="label" :x="20 + i * ((chartW - 40) / 5)" :y="chartH - 6"
                font-size="10" fill="#98a2b3">{{ label }}</text>
        </svg>
        <div class="legend">
          <span><i class="dot-line" style="background:#2b7fff" />受理</span>
          <span><i class="dot-line" style="background:#12b76a" />完成</span>
        </div>
      </el-card>
    </el-col>
    <el-col :span="11">
      <el-card :header="`任务状态分布（${rangeLabel}）`">
        <div v-for="bar in statusBars" :key="bar.label" class="bar-row">
          <span class="bar-label">{{ bar.label }}</span>
          <div class="bar-track"><div class="bar-fill" :style="{ width: bar.pct + '%', background: bar.color }" /></div>
          <span class="bar-val">{{ bar.value.toLocaleString() }}</span>
        </div>
        <template v-if="failureTop.length">
          <div class="sub-head">失败原因 Top5</div>
          <div v-for="f in failureTop" :key="f.errorCode" class="bar-row">
            <span class="bar-label mono" style="width: 130px">{{ f.errorCode }}</span>
            <div class="bar-track">
              <div class="bar-fill" :style="{ width: (Number(f.count) / failureTopMax * 100) + '%', background: '#f04438' }" />
            </div>
            <span class="bar-val">{{ Number(f.count).toLocaleString() }}</span>
          </div>
        </template>
      </el-card>
    </el-col>
  </el-row>

  <!-- Skill 用量 Top5 -->
  <el-row :gutter="14" class="chart-row">
    <el-col :span="24">
      <el-card header="Skill 用量 Top 5">
        <div v-for="s in skillTop" :key="s.skillCode" class="bar-row">
          <span class="bar-label skill">{{ s.skillCode }}</span>
          <div class="bar-track"><div class="bar-fill top" :style="{ width: (Number(s.count) / maxTop * 100) + '%' }" /></div>
          <span class="bar-val">{{ Number(s.count).toLocaleString() }}</span>
        </div>
        <el-empty v-if="skillTop.length === 0" description="暂无执行数据" :image-size="50" />
      </el-card>
    </el-col>
  </el-row>

  <!-- 任务列表 + 异常处理台（Tab 化：列表全宽，死信挂徽标） -->
  <el-card>
    <el-tabs v-model="activeTab">
      <el-tab-pane name="list">
        <template #label>任务列表（{{ recent.total }}）</template>
        <!-- 筛选栏 -->
        <div class="filter-bar">
          <el-select v-model="filters.status" placeholder="状态" clearable style="width: 130px"
                     :disabled="filters.onlyFailed" @change="applyFilters">
            <el-option v-for="(m, s) in statusMeta" :key="s" :label="m.label" :value="s" />
          </el-select>
          <el-input v-model="filters.skill" placeholder="Skill 搜索" clearable style="width: 170px"
                    @keyup.enter="applyFilters" @clear="applyFilters" />
          <el-input v-model="filters.tenant" placeholder="租户" clearable style="width: 150px"
                    @keyup.enter="applyFilters" @clear="applyFilters" />
          <el-checkbox v-model="filters.onlyFailed" label="仅看失败" @change="applyFilters" />
          <el-button type="primary" plain size="small" @click="applyFilters">筛选</el-button>
          <div class="toolbar" style="margin: 0 0 0 auto">
            <el-input v-model="taskId" placeholder="输入 taskId 查询详情" clearable
                      data-testid="taskid-input" @keyup.enter="query">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <el-button type="primary" :loading="loading" @click="query" data-testid="query-btn">查询</el-button>
          </div>
        </div>
        <el-table :data="recent.items" size="small" data-testid="recent-table"
                  v-loading="recentLoading" style="cursor:pointer; width: 100%" @row-click="pick">
          <el-table-column prop="taskId" label="taskId" width="250" class-name="mono" />
          <el-table-column prop="tenantId" label="租户" width="140" />
          <el-table-column prop="skillCode" label="Skill" min-width="170" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <span class="status-text" :style="{ color: statusMeta[row.status]?.color ?? '#98a2b3' }">
                ● {{ row.status }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="进度" width="110">
            <template #default="{ row }">
              <el-progress v-if="row.progress != null" :percentage="row.progress" :stroke-width="7" />
              <span v-else-if="row.status === 'PENDING'">排队中</span>
              <span v-else-if="row.errorCode" class="neg">{{ row.errorCode }}</span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="时长" width="80">
            <template #default="{ row }">{{ fmtDuration(row.durationMs) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click.stop="pick(row)">详情</el-button>
              <el-button v-if="row.status === 'FAILED'" link type="warning" size="small"
                         @click.stop="retryTask(row.taskId)"
                         :data-testid="`retry-${row.taskId}`">重试</el-button>
              <el-button v-if="row.status === 'RUNNING' || row.status === 'PENDING'" link
                         type="danger" size="small" @click.stop="cancelTask(row.taskId)">取消</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="暂无任务——按 README 快速开始发出第一个 /execute 请求即可看到执行链路"
                      :image-size="90" />
          </template>
        </el-table>
        <el-pagination v-model:current-page="recent.pageNo" :page-size="recent.pageSize"
                       :total="recent.total" layout="total, prev, pager, next"
                       @current-change="loadRecent" style="margin-top: 10px" />
      </el-tab-pane>
      <el-tab-pane name="dead">
        <template #label>
          <el-badge :value="deadLetters.length" :hidden="deadLetters.length === 0" type="danger">
            异常处理台（回调死信）
          </el-badge>
        </template>
        <div v-if="deadLetters.length === 0" class="dim" style="padding:8px 0">近期无回调死信</div>
        <div v-for="row in deadLetters" :key="row.taskId" class="dead-row">
          <span class="tag-red">死信</span>
          <div class="dead-body">
            <div>回调投递失败 · <span class="mono">{{ row.taskId }}</span></div>
            <div class="dim">{{ row.tenantId }}</div>
          </div>
          <el-button size="small" round @click="resendCallback(row.taskId)">重发</el-button>
          <el-button size="small" round type="danger" plain @click="giveUpCallback(row.taskId)">放弃</el-button>
        </div>
        <el-empty v-if="deadLetters.length === 0" :image-size="60"
                  description="回调全部送达。死信 = 3 次重试仍失败，可在此重发或放弃" />
        <p class="hint">重发 = 网关按回调契约重建 body 投 skill-callback-retry；放弃 = 标记 GIVE_UP 不再补投</p>
      </el-tab-pane>
    </el-tabs>
  </el-card>

  <!-- 任务详情（右侧抽屉）：流水线 + 节点明细 + 元信息 + 产物 + 操作 -->
  <el-drawer v-model="drawerVisible" size="680px" :title="`任务详情 · ${execution?.taskId ?? ''}`"
             @closed="onDrawerClosed">
    <template v-if="execution">
      <el-descriptions :column="2" border size="small" data-testid="execution-detail" class="exec-desc">
        <el-descriptions-item label="状态">
          <el-tag :type="statusMeta[execution.status]?.color === '#12b76a' ? 'success'
            : statusMeta[execution.status]?.color === '#f04438' ? 'danger'
            : statusMeta[execution.status]?.color === '#2b7fff' ? 'primary' : 'info'">
            {{ statusMeta[execution.status]?.label ?? execution.status }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="版本">{{ execution.resolvedVersion ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="扣点">{{ execution.billing?.pointsCharged ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="时长">{{ fmtDuration(execution.durationMs) }}</el-descriptions-item>
        <el-descriptions-item label="模型调用" :span="2">
          {{ execution.modelCalls ?? '—' }} 次 / {{ execution.tokensUsed ?? 0 }} tokens
        </el-descriptions-item>
        <el-descriptions-item label="回调">
          <el-tag size="small" effect="light" :type="execution.callbackStatus === 'SENT' ? 'success'
            : execution.callbackStatus === 'FAILED' ? 'danger' : 'info'">{{ execution.callbackStatus ?? '无回调' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="回调地址" :span="2">
          <span class="mono cb-url">{{ execution.callbackUrl ?? '—' }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <div class="detail-actions">
        <el-button size="small" plain data-testid="input-btn" @click="showInput">查看入参 JSON</el-button>
        <el-button v-if="execution.status === 'FAILED'" size="small" type="warning" plain
                   @click="retryTask(execution.taskId)">重试</el-button>
        <el-button v-if="execution.status === 'RUNNING' || execution.status === 'PENDING'"
                   size="small" type="danger" plain data-testid="cancel-btn"
                   @click="cancelTask(execution.taskId)">取消任务</el-button>
      </div>
      <el-alert v-if="execution.error" type="error" :closable="false" style="margin: 10px 0"
                :title="`${execution.error.code}: ${execution.error.message}`" data-testid="execution-error" />

      <!-- 执行链路（流程图 + 时间线，数据源 execution_event / MySQL） -->
      <StagePipeline :events="events" :status="execution.status"
                     :progress="execution.progress" :callback-url="execution.callbackUrl" />
      <div class="list-head" style="margin-top: 16px">
        <b>节点明细</b>
      </div>
      <ExecutionTimeline :events="events" />

      <template v-if="execution.artifacts?.length">
        <h4 style="margin: 16px 0 8px">产物（预签名下载，24h 有效）</h4>
        <el-table :data="execution.artifacts" size="small" data-testid="artifacts-table">
          <el-table-column prop="artifactId" label="产物" width="220" class-name="mono" />
          <el-table-column prop="type" label="类型" width="100" />
          <el-table-column prop="sizeBytes" label="大小 (B)" width="100" />
          <el-table-column label="下载">
            <template #default="{ row }">
              <a :href="row.url" target="_blank" rel="noopener">下载链接</a>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </template>
    <div v-else v-loading="loading" style="min-height: 200px" />
  </el-drawer>

  <!-- 入参 JSON 查看 -->
  <el-dialog v-model="inputVisible" title="受理入参（instructions / materials / context 原文）" width="640px">
    <pre class="input-json mono" data-testid="input-json">{{ inputJson }}</pre>
    <template #footer>
      <el-button @click="copyInput">复制 JSON</el-button>
      <el-button type="primary" @click="inputVisible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.range-pills { display: flex; gap: 4px; }
.pill {
  border: none; background: transparent; padding: 6px 14px;
  border-radius: 999px; font-size: 13px; color: #475467; cursor: pointer;
}
.pill.active { background: #1f2430; color: #fff; font-weight: 600; }
.pill.wall-link { border: 1px solid #d5d9e4; }
.pill.wall-link:hover { border-color: #2b7fff; color: #2b7fff; }
.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 14px; }
.stat-card {
  background: #fff; border: 1px solid var(--card-border); border-radius: 12px;
  padding: 16px 18px; display: flex; justify-content: space-between; align-items: center; gap: 10px;
}
.stat-card .label { font-size: 12px; color: var(--text-sub); margin-bottom: 4px; }
.stat-card .value { font-size: 24px; font-weight: 800; }
.stat-card .value small { font-size: 12px; font-weight: 400; color: var(--text-sub); }
.stat-card .foot { font-size: 11px; margin-top: 4px; }
.stat-card .foot.ok { color: #12b76a; }
.stat-card .foot.warn { color: #f79009; }
.stat-card .foot .dim { color: #98a2b3; }
.spark { width: 80px; height: 28px; flex: none; }
.chart-row { margin-bottom: 14px; }
.chart { width: 100%; height: auto; }
.legend { display: flex; gap: 16px; justify-content: flex-end; font-size: 12px; color: var(--text-sub); }
.dot-line { display: inline-block; width: 14px; height: 3px; border-radius: 2px; margin-right: 5px; vertical-align: middle; }
.bar-row { display: flex; align-items: center; gap: 10px; padding: 7px 0; }
.bar-label { width: 110px; font-size: 13px; color: #475467; flex: none; }
.bar-label.skill { font-family: ui-monospace, Menlo, monospace; font-size: 12px; }
.bar-track { flex: 1; height: 10px; background: #f1f3f8; border-radius: 999px; overflow: hidden; }
.bar-fill { height: 100%; border-radius: 999px; }
.bar-fill.top { background: linear-gradient(90deg, #2b7fff, #6aa2ff); }
.bar-val { width: 70px; text-align: right; font-size: 13px; font-weight: 600; }
.list-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 10px; }
.dim { color: #98a2b3; font-size: 12px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 10px; max-width: 480px; }
.status-text { font-size: 13px; font-weight: 600; }
.dead-row { display: flex; align-items: center; gap: 8px; padding: 9px 0; border-bottom: 1px dashed #eef0f4; }
.tag-red { background: #feecea; color: #f04438; font-size: 12px; border-radius: 6px; padding: 2px 8px; flex: none; }
.dead-body { flex: 1; font-size: 13px; line-height: 1.5; }
.hint { color: #98a2b3; font-size: 12px; margin: 10px 0 0; line-height: 1.6; }
.exec-desc { margin-top: 10px; }
.cb-url { font-size: 12px; word-break: break-all; }
.filter-bar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.detail-actions { display: flex; gap: 8px; margin: 12px 0 4px; }
.input-json {
  background: #f8f9fc; border: 1px solid #e9ebf2; border-radius: 8px;
  padding: 12px; max-height: 420px; overflow: auto;
  font-size: 12px; white-space: pre-wrap; word-break: break-all; margin: 0;
}
</style>
