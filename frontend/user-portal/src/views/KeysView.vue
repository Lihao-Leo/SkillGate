<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { rechargeApi, type UserKeyItem } from '../api'
import { useGatewayCredsStore } from '../stores/gwCreds'

const keys = ref<UserKeyItem[]>([])
const loading = ref(false)
const gwCreds = useGatewayCredsStore()

// 一次性 Secret 展示 + 录入执行平台凭证
const onceSecret = reactive({ visible: false, appKeyId: '', appSecret: '' })
const credsForm = reactive({ appKey: '', secret: '' })

async function load() {
  loading.value = true
  try {
    keys.value = await rechargeApi.listKeys()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

function showOnce(appKeyId: string, appSecret: string) {
  onceSecret.appKeyId = appKeyId
  onceSecret.appSecret = appSecret
  onceSecret.visible = true
  credsForm.appKey = appKeyId
  credsForm.secret = appSecret
}

async function issue() {
  try {
    const result = await rechargeApi.issueKey()
    showOnce(result.appKeyId, result.appSecret)
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function resetSecret(appKeyId: string) {
  try {
    const result = await rechargeApi.resetSecret(appKeyId)
    showOnce(result.appKeyId, result.appSecret)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function disable(appKeyId: string) {
  try {
    await rechargeApi.disableKey(appKeyId)
    ElMessage.success('已禁用')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

/** 把签发/重置得到（或用户粘贴）的 key+secret 配置为执行平台调用凭证 */
function useAsGatewayCreds() {
  if (!credsForm.appKey || !credsForm.secret) {
    ElMessage.warning('请先在上面复制 AppKey 与 AppSecret')
    return
  }
  gwCreds.save(credsForm.appKey, credsForm.secret)
  ElMessage.success('已配置：余额 / 流水 / 任务查询可用')
}
</script>

<template>
  <div class="hero">
    <h1>APIKey 管理</h1>
    <p>完整 Secret 仅签发 / 重置时展示一次；重置 = 轮换，旧值立即失效</p>
  </div>

  <div class="panel-card">
    <div class="head-row">
      <div>
        <h3 class="panel-title">我的 Key</h3>
        <p class="panel-sub">用于调用执行平台 API（HMAC 请求签名）</p>
      </div>
      <el-button type="primary" round @click="issue" data-testid="issue-btn">
        <el-icon style="margin-right: 4px"><Plus /></el-icon>签发新 Key
      </el-button>
    </div>

    <el-table :data="keys" v-loading="loading" data-testid="keys-table">
      <el-table-column prop="appKeyId" label="AppKey（脱敏）" width="240" class-name="mono" />
      <el-table-column label="主 key" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.isPrimary" size="small" effect="light">主</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" />
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="resetSecret(row.appKeyId)"
                     :data-testid="`reset-${row.appKeyId}`">重置 Secret</el-button>
          <el-button link type="danger" size="small" @click="disable(row.appKeyId)"
                     :data-testid="`disable-${row.appKeyId}`">禁用</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>

  <div class="panel-card" style="margin-top: 16px">
    <h3 class="panel-title">执行平台调用凭证</h3>
    <p class="panel-sub">配置后可使用余额 / 流水 / 任务查询（保存在你的浏览器本地）</p>
    <div class="creds-row">
      <el-tag :type="gwCreds.ready ? 'success' : 'info'" data-testid="gw-creds-state">
        {{ gwCreds.ready ? `已配置 ${gwCreds.appKey}` : '未配置' }}
      </el-tag>
      <el-input v-model="credsForm.appKey" placeholder="AppKey" style="width: 240px"
                data-testid="creds-appkey" />
      <el-input v-model="credsForm.secret" placeholder="AppSecret" type="password" show-password
                style="width: 240px" data-testid="creds-secret" />
      <el-button type="primary" round @click="useAsGatewayCreds" data-testid="creds-save">
        配置
      </el-button>
      <el-button v-if="gwCreds.ready" link type="danger" @click="gwCreds.clear()">清除</el-button>
    </div>
  </div>

  <el-dialog v-model="onceSecret.visible" title="AppSecret（仅此一次展示）" width="520px">
    <el-alert type="warning" :closable="false"
              title="关闭后不再可见；丢失只能重置轮换（旧值立即失效）" />
    <p class="secret-line" data-testid="once-appkey">{{ onceSecret.appKeyId }}</p>
    <p class="secret-line" data-testid="once-appsecret">{{ onceSecret.appSecret }}</p>
  </el-dialog>
</template>

<style scoped>
.head-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.creds-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
</style>
