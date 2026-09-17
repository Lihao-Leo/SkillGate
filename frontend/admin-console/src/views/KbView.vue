<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { gatewayApi, type KbDocumentView, type KbView } from '../api/gateway'

const kbs = ref<KbView[]>([])
const activeKb = ref<KbView | null>(null)
const documents = ref<KbDocumentView[]>([])
const loading = ref(false)
const createDialog = reactive({ visible: false, tenantId: '', name: '', embeddingAlias: '' })

async function load() {
  loading.value = true
  try {
    kbs.value = await gatewayApi.adminListKbs()
    if (activeKb.value) {
      const still = kbs.value.find(k => k.kbId === activeKb.value?.kbId)
      if (still) {
        await selectKb(still)
      } else {
        activeKb.value = null
        documents.value = []
      }
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function selectKb(kb: KbView) {
  activeKb.value = kb
  documents.value = await gatewayApi.adminListKbDocuments(kb.kbId)
}

async function create() {
  try {
    const kb = await gatewayApi.adminCreateKb(
      createDialog.tenantId.trim(), createDialog.name.trim(), createDialog.embeddingAlias.trim() || undefined)
    ElMessage.success(`已创建 ${kb.kbId}`)
    createDialog.visible = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function removeKb(kb: KbView) {
  try {
    await gatewayApi.adminDeleteKb(kb.kbId)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function uploadDoc(file: File) {
  if (!activeKb.value) return
  try {
    await gatewayApi.adminAddKbDocument(activeKb.value.kbId, file)
    ElMessage.success('已登记（待入库）')
    await selectKb(activeKb.value)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function onFileChange(event: Event) {
  const files = (event.target as HTMLInputElement).files
  if (files && files.length > 0) {
    uploadDoc(files[0])
  }
}

async function ingest(row: KbDocumentView) {
  try {
    const result = await gatewayApi.adminIngestKbDocument(row.kbId, row.docId)
    if (result.indexed) {
      ElMessage.success(`已入库 ${result.chunkCount} 块`)
    } else {
      ElMessage.warning(result.message ?? '已分块，向量化待 Milvus 接入')
    }
    await selectKb(activeKb.value!)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function removeDoc(row: KbDocumentView) {
  try {
    await gatewayApi.adminDeleteKbDocument(row.kbId, row.docId)
    await selectKb(activeKb.value!)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

const statusText: Record<number, string> = { 0: '待入库', 1: '已入库', 2: '失败' }
</script>

<template>
  <div class="page-header">
    <div>
      <h2>知识库</h2>
      <p class="sub">KB / 文档登记与入库状态（向量检索在 Milvus；未启用时入库明确标记 indexed=false）</p>
    </div>
    <div>
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="createDialog.visible = true" data-testid="create-kb">
        <el-icon style="margin-right: 4px"><Plus /></el-icon>新建知识库
      </el-button>
    </div>
  </div>

  <el-row :gutter="16">
    <el-col :span="8">
      <el-card header="知识库" v-loading="loading">
        <div v-for="kb in kbs" :key="kb.kbId" class="kb-row"
             :class="{ active: activeKb?.kbId === kb.kbId }"
             @click="selectKb(kb)" :data-testid="`kb-${kb.kbId}`">
          <div>
            <b>{{ kb.name }}</b>
            <div class="mono kb-id">{{ kb.kbId }} · {{ kb.tenantId }}</div>
          </div>
          <el-button link type="danger" size="small" @click.stop="removeKb(kb)">删除</el-button>
        </div>
        <el-empty v-if="kbs.length === 0" description="暂无知识库" :image-size="60" />
      </el-card>
    </el-col>
    <el-col :span="16">
      <el-card :header="activeKb ? `文档 · ${activeKb.name}` : '文档'">
        <template v-if="activeKb">
          <label class="upload-line">
            <input type="file" accept=".txt,.md" @change="onFileChange" data-testid="kb-upload" />
            <span class="hint">文本文件（.txt/.md），原文落 OSS 私有桶，登记后待入库</span>
          </label>
          <el-table :data="documents" size="small" data-testid="kb-docs-table" style="margin-top: 10px">
            <el-table-column prop="docId" label="docId" width="200" class-name="mono" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" effect="light"
                        :type="row.status === 1 ? 'success' : row.status === 2 ? 'danger' : 'info'">
                  {{ statusText[row.status] ?? row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="chunkCount" label="分块" width="70" />
            <el-table-column prop="createdAt" label="上传时间" />
            <el-table-column label="操作" width="150">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="ingest(row)">入库</el-button>
                <el-button link type="danger" size="small" @click="removeDoc(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else description="← 选择一个知识库" :image-size="60" />
      </el-card>
    </el-col>
  </el-row>

  <el-dialog v-model="createDialog.visible" title="新建知识库" width="480px">
    <el-form label-width="110px">
      <el-form-item label="租户" required>
        <el-input v-model="createDialog.tenantId" data-testid="kb-tenant" />
      </el-form-item>
      <el-form-item label="名称" required>
        <el-input v-model="createDialog.name" data-testid="kb-name" />
      </el-form-item>
      <el-form-item label="embedding">
        <el-input v-model="createDialog.embeddingAlias" placeholder="默认 embedding-default" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="create" data-testid="kb-create">创建</el-button>
      </el-form-item>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.kb-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 12px; border-radius: 8px; cursor: pointer; margin-bottom: 4px;
}
.kb-row:hover { background: #f5f7fa; }
.kb-row.active { background: var(--el-color-primary-light-9); }
.kb-id { font-size: 11px; color: var(--text-sub); }
.upload-line input { margin-right: 10px; }
.hint { color: var(--text-sub); font-size: 12px; }
</style>
