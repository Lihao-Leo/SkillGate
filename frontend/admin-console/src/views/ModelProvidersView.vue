<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { gatewayApi, type ModelProviderView } from '../api/gateway'

const items = ref<ModelProviderView[]>([])
const loading = ref(false)
const dialog = reactive({
  visible: false, editing: false,
  alias: '', provider: '', modelName: '', endpoint: '', apiKey: '', fallbackAlias: '',
  maxQps: 10, costPer1kInput: '', costPer1kOutput: '', costPerCall: '', status: 1,
})

async function load() {
  loading.value = true
  try {
    items.value = await gatewayApi.adminListModelProviders()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

function openCreate() {
  Object.assign(dialog, {
    visible: true, editing: false, alias: '', provider: '', modelName: '', endpoint: '',
    apiKey: '', fallbackAlias: '', maxQps: 10, costPer1kInput: '', costPer1kOutput: '',
    costPerCall: '', status: 1,
  })
}

function openEdit(row: ModelProviderView) {
  Object.assign(dialog, {
    visible: true, editing: true, alias: row.alias, provider: row.provider,
    modelName: row.modelName, endpoint: row.endpoint, apiKey: '',
    fallbackAlias: row.fallbackAlias ?? '', maxQps: row.maxQps,
    costPer1kInput: row.costPer1kInputTokens ?? '', costPer1kOutput: row.costPer1kOutputTokens ?? '',
    costPerCall: row.costPerCall ?? '', status: row.status,
  })
}

async function submit() {
  try {
    await gatewayApi.adminUpsertModelProvider({
      alias: dialog.alias, provider: dialog.provider, modelName: dialog.modelName,
      endpoint: dialog.endpoint || undefined,
      apiKey: dialog.apiKey || undefined,
      fallbackAlias: dialog.fallbackAlias || undefined,
      maxQps: dialog.maxQps,
      costPer1kInput: dialog.costPer1kInput || undefined,
      costPer1kOutput: dialog.costPer1kOutput || undefined,
      costPerCall: dialog.costPerCall || undefined,
      status: dialog.status,
    })
    ElMessage.success('已保存（Skill 按 alias 引用）')
    dialog.visible = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function remove(alias: string) {
  try {
    await ElMessageBox.confirm(`确定删除模型「${alias}」？引用该别名的技能将无法再调用它。`,
      '删除确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch {
    return
  }
  try {
    await gatewayApi.adminDeleteModelProvider(alias)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function toggleStatus(row: ModelProviderView) {
  try {
    await gatewayApi.adminModelProviderStatus(row.alias, row.status === 1 ? 0 : 1)
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>

<template>
  <div class="page-header">
    <div>
      <h2>模型与能力</h2>
      <p class="sub">模型别名路由：Skill 只认 alias；API Key AES 加密存储、永不回显</p>
    </div>
    <el-button type="primary" @click="openCreate" data-testid="create-provider">
      <el-icon style="margin-right: 4px"><Plus /></el-icon>新增模型
    </el-button>
  </div>

  <el-card>
    <el-table :data="items" v-loading="loading" data-testid="providers-table">
      <el-table-column prop="alias" label="alias" width="180" class-name="mono" />
      <el-table-column prop="provider" label="供应商" width="110" />
      <el-table-column prop="modelName" label="模型" width="180" />
      <el-table-column prop="fallbackAlias" label="回退" width="150" />
      <el-table-column prop="maxQps" label="QPS" width="70" />
      <el-table-column label="成本(入/出 /1k)" width="160">
        <template #default="{ row }">
          {{ row.costPer1kInputTokens ?? '-' }} / {{ row.costPer1kOutputTokens ?? '-' }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small" effect="light">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link :type="row.status === 1 ? 'danger' : 'success'" size="small"
                     @click="toggleStatus(row)">{{ row.status === 1 ? '禁用' : '启用' }}</el-button>
          <el-button link type="danger" size="small" @click="remove(row.alias)"
                     :data-testid="`delete-${row.alias}`">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="dialog.visible" :title="dialog.editing ? '编辑模型' : '新增模型'"
             width="560px">
    <el-form label-width="130px">
      <el-form-item label="alias" required>
        <el-input v-model="dialog.alias" :disabled="dialog.editing"
                  placeholder="如 llm-text（Skill 引用名）" />
      </el-form-item>
      <el-form-item label="供应商" required>
        <el-input v-model="dialog.provider" placeholder="openai / anthropic / ..." />
      </el-form-item>
      <el-form-item label="模型名" required>
        <el-input v-model="dialog.modelName" placeholder="gpt-4o-mini / ..." />
      </el-form-item>
      <el-form-item label="Endpoint">
        <el-input v-model="dialog.endpoint" placeholder="API 端点地址" />
      </el-form-item>
      <el-form-item label="API Key">
        <el-input v-model="dialog.apiKey" type="password" show-password
                  :placeholder="dialog.editing ? '留空 = 不修改' : 'AES 加密存储，永不回显'" />
      </el-form-item>
      <el-form-item label="回退 alias">
        <el-input v-model="dialog.fallbackAlias" placeholder="失败时的回退模型（可空）" />
      </el-form-item>
      <el-form-item label="最大 QPS">
        <el-input-number v-model="dialog.maxQps" :min="1" />
      </el-form-item>
      <el-form-item label="成本/1k 输入">
        <el-input v-model="dialog.costPer1kInput" placeholder="点数，如 0.01（可空）" />
      </el-form-item>
      <el-form-item label="成本/1k 输出">
        <el-input v-model="dialog.costPer1kOutput" placeholder="点数，如 0.02（可空）" />
      </el-form-item>
      <el-form-item label="状态">
        <el-switch v-model="dialog.status" :active-value="1" :inactive-value="0"
                   active-text="启用" inactive-text="禁用" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="submit" data-testid="provider-submit">保存</el-button>
      </el-form-item>
    </el-form>
  </el-dialog>
</template>
