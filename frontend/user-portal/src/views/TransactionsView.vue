<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { gatewayUserApi, type TransactionView } from '../api'
import { useGatewayCredsStore } from '../stores/gwCreds'

const gwCreds = useGatewayCredsStore()
const items = ref<TransactionView[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 20 })
const loading = ref(false)

async function load() {
  if (!gwCreds.ready) return
  loading.value = true
  try {
    const page = await gatewayUserApi.transactions(query.pageNo, query.pageSize)
    items.value = page.items
    total.value = page.total
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

const typeText: Record<string, string> = {
  HOLD: '冻结', SETTLE: '结算扣点', RELEASE: '释放退回', RECHARGE: '充值', ADJUST: '调整',
}
const typeTag: Record<string, string> = {
  HOLD: 'warning', SETTLE: 'primary', RELEASE: 'info', RECHARGE: 'success', ADJUST: 'danger',
}
</script>

<template>
  <div class="hero">
    <h1>流水</h1>
    <p>充值 / 扣点 / 退款全量记录，按任务关联，供对账</p>
  </div>

  <div class="panel-card">
    <el-alert v-if="!gwCreds.ready" type="info" :closable="false"
              title="先到「APIKey 管理」配置 AppKey + AppSecret" />
    <template v-else>
      <el-table :data="items" v-loading="loading" data-testid="tx-table">
        <el-table-column prop="txId" label="流水号" width="250" class-name="mono" />
        <el-table-column label="类型" width="110">
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
        <el-table-column prop="taskId" label="关联任务" show-overflow-tooltip class-name="mono" />
        <el-table-column prop="balanceAfter" label="余额" width="90" />
        <el-table-column prop="createdAt" label="时间" width="180" />
      </el-table>
      <el-pagination v-model:current-page="query.pageNo" :page-size="query.pageSize"
                     :total="total" layout="total, prev, pager, next" @current-change="load"
                     style="margin-top: 14px" />
    </template>
  </div>
</template>
