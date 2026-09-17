<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { gatewayUserApi, type ExecutionView } from '../api'
import { useGatewayCredsStore } from '../stores/gwCreds'

const gwCreds = useGatewayCredsStore()
const taskId = ref('')
const execution = ref<ExecutionView | null>(null)
const loading = ref(false)

/** 零代码查结果（§10.2）：复用 §5.4 查询 API（用户 AppKey HMAC） */
async function query() {
  if (!taskId.value.trim()) return
  loading.value = true
  execution.value = null
  try {
    execution.value = await gatewayUserApi.execution(taskId.value.trim())
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

const statusMeta: Record<string, { label: string; type: 'success' | 'danger' | 'info' | 'warning' | 'primary' }> = {
  PENDING: { label: '排队中', type: 'info' },
  RUNNING: { label: '执行中', type: 'primary' },
  CANCELLING: { label: '取消中', type: 'warning' },
  SUCCEEDED: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  CANCELLED: { label: '已取消', type: 'info' },
}
</script>

<template>
  <div class="hero">
    <h1>任务查询</h1>
    <p>按 taskId 零代码查询执行状态、进度与产物（预签名下载，24h 有效）</p>
  </div>

  <div class="panel-card">
    <el-alert v-if="!gwCreds.ready" type="info" :closable="false"
              title="先到「APIKey 管理」配置 AppKey + AppSecret" />
    <template v-else>
      <div class="query-bar">
        <el-input v-model="taskId" placeholder="输入 taskId" size="large" clearable
                  data-testid="taskid-input" @keyup.enter="query">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" size="large" :loading="loading" @click="query"
                   data-testid="query-btn">查询</el-button>
      </div>

      <template v-if="execution">
        <el-descriptions :column="3" border data-testid="execution-detail">
          <el-descriptions-item label="taskId" class-name="mono">{{ execution.taskId }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusMeta[execution.status]?.type ?? 'info'">
              {{ statusMeta[execution.status]?.label ?? execution.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="进度">
            <el-progress v-if="execution.progress != null" :percentage="execution.progress"
                         :stroke-width="8" style="max-width: 150px" />
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="Skill">{{ execution.skillCode ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="版本">{{ execution.resolvedVersion ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="扣点">
            {{ execution.billing?.pointsCharged ?? '-' }}
          </el-descriptions-item>
        </el-descriptions>

        <el-alert v-if="execution.error" type="error" :closable="false" style="margin-top: 12px"
                  :title="`${execution.error.code}: ${execution.error.message}`" />

        <template v-if="execution.artifacts?.length">
          <h4 style="margin: 18px 0 10px">产物下载</h4>
          <ul class="artifacts" data-testid="artifacts">
            <li v-for="artifact in execution.artifacts" :key="artifact.artifactId">
              <a :href="artifact.url" target="_blank" rel="noopener">
                {{ artifact.artifactId }}（{{ artifact.type }}）
              </a>
            </li>
          </ul>
        </template>
      </template>
    </template>
  </div>
</template>

<style scoped>
.query-bar { display: flex; gap: 10px; max-width: 620px; margin-bottom: 18px; }
.artifacts { margin: 0; padding-left: 18px; line-height: 2; }
</style>
