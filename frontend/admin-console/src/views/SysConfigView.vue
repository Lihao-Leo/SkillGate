<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { gatewayApi } from '../api/gateway'

const form = reactive<Record<string, string>>({
  'quota.qps': '', 'quota.max-running': '', 'quota.daily-limit': '',
})
const saving = ref(false)

const descriptions: Record<string, string> = {
  'quota.qps': '单 AppKey 默认 QPS（yml 兜底 10）',
  'quota.max-running': '单 AppKey 默认最大并发 RUNNING（yml 兜底 50）',
  'quota.daily-limit': '单 AppKey 默认日调用量（yml 兜底 10000）',
}

async function load() {
  try {
    const all = await gatewayApi.adminSysConfig()
    for (const key of Object.keys(form)) {
      form[key] = all[key] ?? ''
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
onMounted(load)

async function save() {
  saving.value = true
  try {
    const items: Record<string, string> = {}
    for (const key of Object.keys(form)) {
      if (String(form[key]).trim() !== '') {
        items[key] = String(form[key]).trim()
      }
    }
    const all = await gatewayApi.adminUpdateSysConfig(items)
    ElMessage.success('已保存（30 秒内生效）')
    for (const key of Object.keys(form)) {
      form[key] = all[key] ?? ''
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<script lang="ts">
import { ref } from 'vue'
export default {}
</script>

<template>
  <div class="page-header">
    <div>
      <h2>系统配置</h2>
      <p class="sub">DB 配置覆盖 yml 默认值；留空 = 使用 yml 默认。修改后约 30 秒生效</p>
    </div>
  </div>
  <el-card style="max-width: 640px">
    <el-form label-width="220px" data-testid="sysconfig-form">
      <el-form-item v-for="(desc, key) in descriptions" :key="key" :label="key">
        <el-input v-model="form[key]" :placeholder="desc" style="max-width: 220px" />
        <span class="hint">{{ desc }}</span>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" @click="save" data-testid="sysconfig-save">
          保存
        </el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<style scoped>
.hint { color: var(--text-sub); font-size: 12px; margin-left: 12px; }
</style>
