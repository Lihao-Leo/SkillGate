<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { gatewayApi, type ArtifactView, type ArtifactContentView } from '../api/gateway'

/**
 * 产物在线预览弹窗（V2.1）：结构化产物（json/markdown/html/text）拉取内容展示，
 * 超上限截断时提示走下载。JSON 自动 pretty-print。
 */
const visible = ref(false)
const loading = ref(false)
const current = ref<ArtifactView | null>(null)
const view = ref<ArtifactContentView | null>(null)

async function open(taskId: string, artifact: ArtifactView) {
  current.value = artifact
  visible.value = true
  loading.value = true
  view.value = null
  try {
    view.value = await gatewayApi.adminArtifactContent(taskId, artifact.artifactId)
  } catch (e) {
    ElMessage.error((e as Error).message)
    visible.value = false
  } finally {
    loading.value = false
  }
}

function pretty(content: string, contentType: string | null | undefined): string {
  const mime = (contentType ?? '').toLowerCase()
  if (!mime.includes('json')) return content
  try {
    return JSON.stringify(JSON.parse(content), null, 2)
  } catch {
    return content
  }
}

defineExpose({ open })
</script>

<template>
  <el-dialog v-model="visible" :title="`产物预览 · ${current?.artifactId ?? ''}`" width="720px"
             data-testid="artifact-preview-dialog" :close-on-click-modal="true">
    <div v-loading="loading" style="min-height: 120px">
      <template v-if="view">
        <div class="meta">
          <el-tag size="small" effect="light">{{ view.type }}</el-tag>
          <span class="mono">{{ view.contentType ?? '-' }}</span>
          <span>{{ view.sizeBytes ?? '-' }} B</span>
          <el-tag v-if="view.truncated" type="warning" size="small" effect="light">
            内容超上限已截断，完整内容请走下载
          </el-tag>
        </div>
        <pre class="content" data-testid="artifact-preview-content">{{ pretty(view.content, view.contentType) }}</pre>
      </template>
    </div>
    <template #footer>
      <el-button v-if="current?.url" tag="a" :href="current.url" target="_blank" rel="noopener">
        下载完整产物
      </el-button>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.meta { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; font-size: 12.5px; }
.content {
  max-height: 480px;
  overflow: auto;
  background: #f6f8fb;
  border: 1px solid #eef0f4;
  border-radius: 10px;
  padding: 14px;
  font-size: 12.5px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
