<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Wallet, Lock } from '@element-plus/icons-vue'
import { gatewayApi, type BalanceView, type TransactionPage } from '../api/gateway'

const balance = ref<BalanceView | null>(null)
const page = ref<TransactionPage | null>(null)
const query = reactive({ taskId: '', pageNo: 1, pageSize: 20 })
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    balance.value = await gatewayApi.balance()
    page.value = await gatewayApi.transactions(query.taskId || undefined,
      query.pageNo, query.pageSize)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

const typeText: Record<string, string> = {
  HOLD: '冻结', SETTLE: '结算', RELEASE: '释放', RECHARGE: '充值', ADJUST: '调整',
}
function fmtDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

const typeTag: Record<string, string> = {
  HOLD: 'warning', SETTLE: 'primary', RELEASE: 'info', RECHARGE: 'success', ADJUST: 'danger',
}
</script>

<template>
  <div class="page-header">
    <div>
      <h2>账户流水</h2>
      <p class="sub">当前 AppKey 的点数账户：可用 / 冻结与全部动账记录（HOLD/SETTLE/RELEASE/RECHARGE/ADJUST）</p>
    </div>
    <el-button @click="load">刷新</el-button>
  </div>

  <div class="stat-grid">
    <div class="stat-card">
      <span class="icon-chip" style="background: linear-gradient(135deg,#3b5bf5,#7b5bf5)">
        <el-icon :size="20"><Wallet /></el-icon>
      </span>
      <div>
        <div class="label">可用点数</div>
        <div class="value" data-testid="balance">{{ balance?.balance ?? '-' }}</div>
      </div>
    </div>
    <div class="stat-card">
      <span class="icon-chip" style="background: linear-gradient(135deg,#f79009,#f04438)">
        <el-icon :size="20"><Lock /></el-icon>
      </span>
      <div>
        <div class="label">冻结中</div>
        <div class="value" data-testid="frozen">{{ balance?.frozen ?? '-' }}</div>
      </div>
    </div>
  </div>

  <el-card>
    <div class="toolbar">
      <el-input v-model="query.taskId" placeholder="按 taskId 过滤" clearable
                style="width: 280px" @keyup.enter="load" data-testid="tx-taskid" />
      <el-button type="primary" @click="load">查询</el-button>
    </div>
    <el-table :data="page?.items ?? []" v-loading="loading" data-testid="tx-table">
      <el-table-column prop="txId" label="流水号" width="250" class-name="mono" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag :type="typeTag[row.type] ?? 'info'" size="small" effect="light">
            {{ typeText[row.type] ?? row.type }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="金额" width="100">
        <template #default="{ row }">
          <span :class="row.amount >= 0 ? 'pos' : 'neg'">{{ row.amount }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="taskId" label="任务" show-overflow-tooltip class-name="mono" />
      <el-table-column prop="balanceAfter" label="余额" width="100" />
      <el-table-column prop="remark" label="备注" show-overflow-tooltip />
      <el-table-column label="时间" width="160">
        <template #default="{ row }">{{ fmtDateTime(row.createdAt) }}</template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="query.pageNo" :page-size="query.pageSize"
                   :total="page?.total ?? 0" layout="total, prev, pager, next"
                   @current-change="load" style="margin-top: 14px" />
  </el-card>
</template>
