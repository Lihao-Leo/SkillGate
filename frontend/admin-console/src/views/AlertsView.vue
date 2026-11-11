<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { gatewayApi } from '../api/gateway'
import type { AlertRecordPage, AlertRecordView, AlertRuleView } from '../api/gateway'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const canManage = computed(() => auth.hasPerm('alert:manage'))

const RULE_TYPES = [
  { value: 'RUNNING_TIMEOUT', label: '任务运行超时（秒）' },
  { value: 'TOKEN_EXCESS', label: 'Token 消耗超量（个数）' },
  { value: 'TASK_FAILED', label: '任务失败（阈值无意义，填 0）' },
  { value: 'PENDING_STUCK', label: '排队积压（秒）' },
  { value: 'CALLBACK_DEAD_LETTER', label: '回调死信（次数）' },
  { value: 'WATCHDOG_INTERVENTION', label: '看门狗干预（次数）' },
]

// ---- 规则 ----
const rules = ref<AlertRuleView[]>([])
const rulesLoading = ref(false)
const ruleDialog = reactive({
  visible: false, id: null as number | null,
  ruleCode: '', ruleName: '', ruleType: 'RUNNING_TIMEOUT', skillCode: '',
  thresholdValue: 600, severity: 'WARN',
  channelSelection: ['feishu', 'record'] as string[],
  dedupWindowSeconds: 86400, enabledBool: true,
})

async function loadRules() {
  rulesLoading.value = true
  try {
    rules.value = await gatewayApi.adminListAlertRules()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    rulesLoading.value = false
  }
}

function openCreate() {
  Object.assign(ruleDialog, {
    visible: true, id: null, ruleCode: '', ruleName: '', ruleType: 'RUNNING_TIMEOUT',
    skillCode: '', thresholdValue: 600, severity: 'WARN',
    channelSelection: ['feishu', 'record'], dedupWindowSeconds: 86400, enabledBool: true,
  })
}

function openEdit(row: AlertRuleView) {
  Object.assign(ruleDialog, {
    visible: true, id: row.id, ruleCode: row.ruleCode, ruleName: row.ruleName,
    ruleType: row.ruleType, skillCode: row.skillCode ?? '', thresholdValue: row.thresholdValue ?? 0,
    severity: row.severity,
    channelSelection: (row.channels ?? '').split(',').map(s => s.trim()).filter(Boolean),
    dedupWindowSeconds: row.dedupWindowSeconds, enabledBool: row.enabled === 1,
  })
}

async function saveRule() {
  if (!ruleDialog.ruleCode.trim() || !ruleDialog.ruleName.trim()) {
    ElMessage.warning('规则编码与名称必填')
    return
  }
  const payload = {
    ruleCode: ruleDialog.ruleCode.trim(),
    ruleName: ruleDialog.ruleName.trim(),
    ruleType: ruleDialog.ruleType,
    skillCode: ruleDialog.skillCode.trim() || undefined,
    thresholdValue: Number(ruleDialog.thresholdValue),
    severity: ruleDialog.severity,
    channels: ruleDialog.channelSelection.length ? ruleDialog.channelSelection.join(',') : 'record',
    dedupWindowSeconds: Number(ruleDialog.dedupWindowSeconds),
    enabled: ruleDialog.enabledBool ? 1 : 0,
  }
  try {
    if (ruleDialog.id === null) {
      await gatewayApi.adminCreateAlertRule(payload)
      ElMessage.success('规则已创建（worker 60s 内热生效）')
    } else {
      await gatewayApi.adminUpdateAlertRule(ruleDialog.id, payload)
      ElMessage.success('规则已更新')
    }
    ruleDialog.visible = false
    await loadRules()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function toggleRule(row: AlertRuleView) {
  try {
    await gatewayApi.adminUpdateAlertRuleStatus(row.id, row.enabled === 1 ? 0 : 1)
    await loadRules()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function removeRule(row: AlertRuleView) {
  try {
    await ElMessageBox.confirm(
      `删除规则「${row.ruleName}」？删除后 worker 下一次拉取即失效。`,
      '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await gatewayApi.adminDeleteAlertRule(row.id)
    ElMessage.success('已删除')
    await loadRules()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---- 告警记录 ----
const records = ref<AlertRecordView[]>([])
const recordsLoading = ref(false)
const recordQuery = reactive({ status: 'PENDING', ruleType: '', taskId: '', pageNo: 1, pageSize: 20 })
const recordTotal = ref(0)
const pendingCount = ref(0)
const ackDialog = reactive({ visible: false, id: null as number | null, note: '' })

async function loadRecords() {
  recordsLoading.value = true
  try {
    const page: AlertRecordPage = await gatewayApi.adminListAlertRecords({
      status: recordQuery.status || undefined,
      ruleType: recordQuery.ruleType || undefined,
      taskId: recordQuery.taskId.trim() || undefined,
      pageNo: recordQuery.pageNo, pageSize: recordQuery.pageSize,
    })
    records.value = page.rows
    recordTotal.value = page.total
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    recordsLoading.value = false
  }
}

async function loadPendingCount() {
  try {
    pendingCount.value = (await gatewayApi.adminAlertPendingCount()).count
  } catch { /* 徽标失败容忍 */ }
}

async function openAck(row: AlertRecordView) {
  try {
    const { value } = await ElMessageBox.prompt('认领备注（可空）', `认领告警 #${row.id}`,
      { inputPlaceholder: '处理说明' })
    await gatewayApi.adminAckAlertRecord(row.id, (value ?? '').trim() || undefined)
    ElMessage.success('已认领')
    await loadRecords()
    await loadPendingCount()
  } catch { /* 取消 */ }
}

function ruleTypeText(type: string): string {
  return RULE_TYPES.find(item => item.value === type)?.label ?? type
}

let timer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  loadRules()
  loadRecords()
  loadPendingCount()
  timer = setInterval(loadPendingCount, 30000)
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<template>
  <div class="view-head">
    <div>
      <h2>告警中心</h2>
      <p class="sub">告警规则（worker 60s 热生效）+ 告警记录认领</p>
    </div>
    <div class="spacer" />
    <el-badge v-if="pendingCount > 0" :value="pendingCount" type="danger"
              data-testid="pending-badge">
      <el-tag type="danger" effect="plain">待处理告警</el-tag>
    </el-badge>
    <el-tag v-else type="success" effect="plain">无待处理告警</el-tag>
    <el-button v-if="canManage" type="primary" data-testid="create-rule"
               @click="openCreate">新建规则</el-button>
  </div>

  <el-card>
    <div class="list-head"><b>告警规则</b></div>
    <el-table :data="rules" v-loading="rulesLoading" size="small" data-testid="alert-rules-table">
      <el-table-column prop="ruleName" label="规则" min-width="150" />
      <el-table-column prop="ruleCode" label="编码" min-width="140" class-name="mono" />
      <el-table-column label="类型" min-width="150">
        <template #default="{ row }">{{ ruleTypeText(row.ruleType) }}</template>
      </el-table-column>
      <el-table-column label="限定 Skill" min-width="110">
        <template #default="{ row }">{{ row.skillCode ?? '全部' }}</template>
      </el-table-column>
      <el-table-column prop="thresholdValue" label="阈值" width="80" align="right" />
      <el-table-column label="级别" width="90">
        <template #default="{ row }">
          <el-tag :type="row.severity === 'CRITICAL' ? 'danger' : row.severity === 'INFO' ? 'info' : 'warning'"
                  size="small" effect="light">{{ row.severity }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="channels" label="通道" width="110" />
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-switch :model-value="row.enabled === 1" :disabled="!canManage"
                     data-testid="rule-toggle" @change="toggleRule(row)" />
        </template>
      </el-table-column>
      <el-table-column v-if="canManage" label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" :data-testid="`edit-${row.ruleCode}`"
                     @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="removeRule(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-card style="margin-top: 16px">
    <div class="list-head">
      <b>告警记录</b>
      <el-select v-model="recordQuery.status" size="small" style="width: 120px"
                 data-testid="record-status" @change="loadRecords">
        <el-option label="待处理" value="PENDING" />
        <el-option label="已认领" value="ACKED" />
        <el-option label="全部" value="" />
      </el-select>
      <el-input v-model="recordQuery.taskId" size="small" placeholder="按 taskId 过滤"
                clearable style="width: 220px" @keyup.enter="loadRecords" @clear="loadRecords" />
      <el-button size="small" data-testid="record-refresh" @click="loadRecords">刷新</el-button>
    </div>
    <el-table :data="records" v-loading="recordsLoading" size="small" data-testid="alert-records-table">
      <el-table-column prop="id" label="#" width="60" />
      <el-table-column label="级别" width="90">
        <template #default="{ row }">
          <el-tag :type="row.severity === 'CRITICAL' ? 'danger' : row.severity === 'INFO' ? 'info' : 'warning'"
                  size="small" effect="light">{{ row.severity }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
      <el-table-column prop="ruleCode" label="规则" min-width="140" class-name="mono" />
      <el-table-column prop="skillCode" label="Skill" min-width="130" />
      <el-table-column prop="taskId" label="任务" min-width="200" class-name="mono"
                       show-overflow-tooltip />
      <el-table-column prop="lastSeenAt" label="最近触发" min-width="150" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'PENDING' ? 'danger' : 'success'" size="small" effect="light">
            {{ row.status === 'PENDING' ? '待处理' : row.status === 'ACKED' ? '已认领' : row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'PENDING' && canManage" link type="primary" size="small"
                     :data-testid="`ack-${row.id}`" @click="openAck(row)">认领</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="recordQuery.pageNo" :page-size="recordQuery.pageSize"
                   :total="recordTotal" layout="total, prev, pager, next"
                   style="margin-top: 12px" @current-change="loadRecords" />
  </el-card>

  <el-dialog v-model="ruleDialog.visible" :title="ruleDialog.id === null ? '新建告警规则' : '编辑告警规则'"
             width="560px">
    <el-form label-width="110px">
      <el-form-item label="规则编码">
        <el-input v-model="ruleDialog.ruleCode" :disabled="ruleDialog.id !== null"
                  data-testid="rule-code" placeholder="如 trace-demo-timeout" />
      </el-form-item>
      <el-form-item label="规则名称">
        <el-input v-model="ruleDialog.ruleName" data-testid="rule-name" />
      </el-form-item>
      <el-form-item label="规则类型">
        <el-select v-model="ruleDialog.ruleType" data-testid="rule-type" style="width: 100%">
          <el-option v-for="t in RULE_TYPES" :key="t.value" :value="t.value" :label="t.label" />
        </el-select>
      </el-form-item>
      <el-form-item label="限定 Skill">
        <el-input v-model="ruleDialog.skillCode" placeholder="留空 = 全部 Skill" />
      </el-form-item>
      <el-form-item label="阈值">
        <el-input-number v-model="ruleDialog.thresholdValue" :min="0" data-testid="rule-threshold" />
        <span class="hint" style="margin-left: 8px">单位随类型：秒 / token 数 / 次数</span>
      </el-form-item>
      <el-form-item label="级别">
        <el-radio-group v-model="ruleDialog.severity" data-testid="rule-severity">
          <el-radio-button value="INFO">INFO</el-radio-button>
          <el-radio-button value="WARN">WARN</el-radio-button>
          <el-radio-button value="CRITICAL">CRITICAL</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="通知通道">
        <el-checkbox-group v-model="ruleDialog.channelSelection" data-testid="rule-channels">
          <el-checkbox value="feishu">飞书 webhook</el-checkbox>
          <el-checkbox value="record">仅记录（告警中心）</el-checkbox>
        </el-checkbox-group>
      </el-form-item>
      <el-form-item label="去重窗口">
        <el-input-number v-model="ruleDialog.dedupWindowSeconds" :min="60" :step="60" />
        <span class="hint" style="margin-left: 8px">秒；同任务同规则窗口内只告警一次</span>
      </el-form-item>
      <el-form-item label="启用">
        <el-switch v-model="ruleDialog.enabledBool" data-testid="rule-enabled" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="ruleDialog.visible = false">取消</el-button>
      <el-button type="primary" data-testid="rule-save" @click="saveRule">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.view-head { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.view-head h2 { font-size: 17px; margin: 0; }
.sub { color: var(--text-sub); font-size: 12.5px; margin-top: 4px; }
.spacer { flex: 1; }
.list-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.hint { color: var(--text-sub); font-size: 12px; }
</style>
