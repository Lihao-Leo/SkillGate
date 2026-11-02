<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
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

// ---------- 飞书告警通知（统一维护：保存即热生效，无需重启） ----------
const alert = reactive({
  webhookUrl: '', secret: '', runningSeconds: 600, tokensThreshold: 0,
})
const alertSaving = ref(false)
const testing = ref(false)

async function loadAlert() {
  try {
    const config = await gatewayApi.adminAlertConfig()
    alert.webhookUrl = config.webhookUrl ?? ''
    alert.secret = config.secret ?? ''
    alert.runningSeconds = config.runningSeconds ?? 600
    alert.tokensThreshold = config.tokensThreshold ?? 0
  } catch { /* 留默认值 */ }
}

async function saveAlert() {
  alertSaving.value = true
  try {
    const saved = await gatewayApi.adminSaveAlertConfig({
      webhookUrl: alert.webhookUrl.trim(),
      secret: alert.secret.trim(),
      runningSeconds: Number(alert.runningSeconds) || 0,
      tokensThreshold: Number(alert.tokensThreshold) || 0,
    })
    alert.webhookUrl = saved.webhookUrl ?? ''
    alert.secret = saved.secret ?? ''
    ElMessage.success('已保存。worker 约 60 秒内拉取生效（无需重启）')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    alertSaving.value = false
  }
}

async function testAlert() {
  testing.value = true
  try {
    const result = await gatewayApi.adminTestAlertConfig()
    if (result.ok) {
      ElMessage.success(result.message)
    } else {
      ElMessage.error(result.message)
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    testing.value = false
  }
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
onMounted(() => { load(); loadAlert() })

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

<template>
  <div class="page-header">
    <div>
      <h2>系统配置</h2>
      <p class="sub">DB 配置覆盖 yml 默认值；留空 = 使用 yml 默认。修改后约 30 秒生效</p>
    </div>
  </div>
  <!-- 飞书告警通知（统一维护：保存热生效 + 连通性测试） -->
  <el-card style="max-width: 640px; margin-bottom: 14px">
    <template #header>
      <div style="display: flex; align-items: center; gap: 10px">
        <b>飞书告警通知</b>
        <span class="tag" :style="{ background: alert.webhookUrl ? '#e8f8ee' : '#f1f3f8',
                                     color: alert.webhookUrl ? '#12b76a' : '#98a2b3' }">
          {{ alert.webhookUrl ? '已配置' : '未配置' }}
        </span>
      </div>
    </template>
    <el-form label-width="150px" data-testid="alert-form">
      <el-form-item label="机器人 Webhook">
        <el-input v-model="alert.webhookUrl" placeholder="https://open.feishu.cn/open-apis/bot/v2/hook/…"
                  data-testid="alert-webhook" clearable />
      </el-form-item>
      <el-form-item label="加签 Secret">
        <el-input v-model="alert.secret" placeholder="机器人开启「加签」时填写，否则留空"
                  data-testid="alert-secret" clearable show-password />
      </el-form-item>
      <el-form-item label="长跑告警阈值（秒）">
        <el-input-number v-model="alert.runningSeconds" :min="0" :step="60" data-testid="alert-running" />
        <span class="hint">RUNNING 超过该时长告警一次，0 = 关闭</span>
      </el-form-item>
      <el-form-item label="Token 告警阈值">
        <el-input-number v-model="alert.tokensThreshold" :min="0" :step="10000" data-testid="alert-tokens" />
        <span class="hint">单任务 tokens 超过该值告警一次，0 = 关闭</span>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="alertSaving" @click="saveAlert" data-testid="alert-save">
          保存配置
        </el-button>
        <el-button :loading="testing" @click="testAlert" data-testid="alert-test">
          发送测试消息
        </el-button>
        <span class="hint">保存后 worker 约 60 秒内拉取生效，无需重启</span>
      </el-form-item>
    </el-form>
  </el-card>

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
.tag { font-size: 12px; border-radius: 6px; padding: 2px 10px; font-weight: 600; }
</style>
