<script setup lang="ts">
import { ref, watch } from 'vue'
import { gatewayApi, type ModelCallView } from '../api/gateway'

/**
 * 模型调用明细表（V2.1 执行抽屉内复用）：逐次 LLM 调用的 token 细分与费率折算点数。
 * 挂载即按 taskId 拉取；查询失败容忍（任务可能无 LLM 调用）。
 */
const props = defineProps<{ taskId: string }>()

const rows = ref<ModelCallView[]>([])
const loading = ref(false)

async function load() {
  if (!props.taskId) return
  loading.value = true
  try {
    rows.value = await gatewayApi.adminModelCalls(props.taskId)
  } catch { /* 明细查询失败容忍 */ } finally {
    loading.value = false
  }
}

watch(() => props.taskId, load, { immediate: true })

function fmtNum(value: number | null | undefined): string {
  return value === null || value === undefined ? '-' : String(value)
}
</script>

<template>
  <el-table :data="rows" v-loading="loading" size="small" data-testid="model-call-table">
    <el-table-column label="#" width="46">
      <template #default="{ $index }">{{ $index + 1 }}</template>
    </el-table-column>
    <el-table-column prop="alias" label="模型 alias" min-width="110" />
    <el-table-column prop="modelName" label="模型" min-width="130" show-overflow-tooltip />
    <el-table-column label="输入 tokens" width="100" align="right">
      <template #default="{ row }">{{ fmtNum(row.promptTokens) }}</template>
    </el-table-column>
    <el-table-column label="输出 tokens" width="100" align="right">
      <template #default="{ row }">{{ fmtNum(row.completionTokens) }}</template>
    </el-table-column>
    <el-table-column label="合计" width="80" align="right">
      <template #default="{ row }">{{ fmtNum(row.totalTokens) }}</template>
    </el-table-column>
    <el-table-column label="点数" width="80" align="right">
      <template #default="{ row }">{{ row.costPoints ?? '-' }}</template>
    </el-table-column>
    <el-table-column label="耗时" width="90" align="right">
      <template #default="{ row }">
        {{ row.durationMs == null ? '-' : `${row.durationMs}ms` }}
      </template>
    </el-table-column>
    <el-table-column label="状态" width="70">
      <template #default="{ row }">
        <el-tag :type="row.status === 'OK' ? 'success' : 'danger'" size="small" effect="light">
          {{ row.status }}
        </el-tag>
      </template>
    </el-table-column>
    <template #empty>该任务暂无模型调用明细（或未走 LLM）</template>
  </el-table>
</template>
