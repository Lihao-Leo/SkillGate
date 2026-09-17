<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { gatewayApi, type AdminKeyPage } from '../api/gateway'
import { useCredentialsStore } from '../stores/credentials'

const router = useRouter()
const creds = useCredentialsStore()
const page = ref<AdminKeyPage | null>(null)
const query = reactive({ tenantId: '', pageNo: 1, pageSize: 20 })
const loading = ref(false)

// 重置 Secret：完整值仅此一次展示
const secretDialog = reactive({ visible: false, appKeyId: '', appSecret: '' })
// 人工入账（orderNo 幂等）
const rechargeDialog = reactive({
  visible: false, appKeyId: '', points: 100, orderNo: '', submitting: false,
})

async function load() {
  loading.value = true
  try {
    page.value = await gatewayApi.adminListKeys(query.tenantId || undefined,
      query.pageNo, query.pageSize)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function toggleStatus(appKeyId: string, current: number) {
  const next = current === 1 ? 0 : 1
  try {
    await gatewayApi.adminUpdateKeyStatus(appKeyId, next as 0 | 1)
    ElMessage.success(next === 1 ? '已启用' : '已禁用')
    // 禁用当前签名所用 key：业务面凭证即刻失效，清空并引导重配
    if (next === 0 && appKeyId === creds.appKey) {
      creds.clear()
      ElMessage.warning('已禁用当前使用的 AppKey，业务面凭证已失效，请重新配置')
      await router.push({ name: 'connect' })
      return
    }
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function resetSecret(appKeyId: string) {
  try {
    const result = await gatewayApi.adminResetSecret(appKeyId)
    secretDialog.appKeyId = result.appKeyId
    secretDialog.appSecret = result.appSecret
    secretDialog.visible = true
    // 轮换的若是当前签名所用 key：用一次性新 Secret 同步本地凭证，业务面无缝续用
    if (appKeyId === creds.appKey) {
      creds.save({ appKey: result.appKeyId, secret: result.appSecret, adminToken: creds.adminToken })
      ElMessage.success('当前凭证已同步轮换，业务面无需重新配置')
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function openRecharge(appKeyId = '') {
  rechargeDialog.appKeyId = appKeyId
  rechargeDialog.points = 100
  rechargeDialog.orderNo = ''
  rechargeDialog.visible = true
}

/** 一次性 Secret 弹窗内一键切换为业务面签名凭证 */
function useAsCredentials() {
  creds.save({
    appKey: secretDialog.appKeyId,
    secret: secretDialog.appSecret,
    adminToken: creds.adminToken,
  })
  ElMessage.success('已设为当前凭证，业务面立即生效')
  secretDialog.visible = false
}

async function submitRecharge() {
  rechargeDialog.submitting = true
  try {
    const result = await gatewayApi.adminRecharge(rechargeDialog.appKeyId,
      rechargeDialog.points, rechargeDialog.orderNo)
    ElMessage.success(result.alreadyCredited
      ? `该 orderNo 已入账（幂等命中），余额 ${result.balance}`
      : `入账成功，余额 ${result.balance}`)
    rechargeDialog.visible = false
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    rechargeDialog.submitting = false
  }
}
</script>

<template>
  <div class="page-header">
    <div>
      <h2>AppKey 与入账</h2>
      <p class="sub">管理面（X-Admin-Token）：key 启停 / Secret 轮换 / 点数人工入账（orderNo 幂等）</p>
    </div>
    <el-button type="primary" @click="openRecharge()">
      <el-icon style="margin-right: 4px"><Plus /></el-icon>点数入账
    </el-button>
  </div>

  <el-card>
    <div class="toolbar">
      <el-input v-model="query.tenantId" placeholder="按租户筛选" clearable
                style="width: 240px" data-testid="tenant-filter" @keyup.enter="load" />
      <el-button type="primary" @click="load" data-testid="search-btn">查询</el-button>
    </div>
    <el-table :data="page?.items ?? []" v-loading="loading" data-testid="keys-table">
      <el-table-column prop="appKeyId" label="AppKey" width="240" class-name="mono" />
      <el-table-column prop="tenantId" label="租户" width="200" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small" effect="light">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="180" />
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link :type="row.status === 1 ? 'danger' : 'success'" size="small"
                     @click="toggleStatus(row.appKeyId, row.status)"
                     :data-testid="`toggle-${row.appKeyId}`">
            {{ row.status === 1 ? '禁用' : '启用' }}
          </el-button>
          <el-button link type="primary" size="small" @click="resetSecret(row.appKeyId)"
                     :data-testid="`reset-${row.appKeyId}`">重置 Secret</el-button>
          <el-button link type="primary" size="small" @click="openRecharge(row.appKeyId)">入账</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="query.pageNo" :page-size="query.pageSize"
                   :total="page?.total ?? 0" layout="total, prev, pager, next"
                   @current-change="load" style="margin-top: 14px" />
  </el-card>

  <el-dialog v-model="secretDialog.visible" title="新 AppSecret（仅此一次展示）" width="520px">
    <el-alert type="warning" :closable="false"
              title="关闭后不再可见；丢失只能重置轮换（旧值立即失效）" />
    <p class="secret-line" data-testid="once-secret">{{ secretDialog.appKeyId }}</p>
    <p class="secret-line" data-testid="once-appsecret">{{ secretDialog.appSecret }}</p>
    <template #footer>
      <el-button v-if="secretDialog.appKeyId !== creds.appKey" type="primary"
                 @click="useAsCredentials" data-testid="use-as-creds">设为当前凭证</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="rechargeDialog.visible" title="点数入账（orderNo 幂等）" width="480px">
    <el-form label-width="100px">
      <el-form-item label="AppKey">
        <el-input v-model="rechargeDialog.appKeyId" data-testid="recharge-appkey" />
      </el-form-item>
      <el-form-item label="点数">
        <el-input-number v-model="rechargeDialog.points" :min="1" data-testid="recharge-points" />
      </el-form-item>
      <el-form-item label="订单号">
        <el-input v-model="rechargeDialog.orderNo" placeholder="幂等键，重复入账返回已入账流水"
                  data-testid="recharge-orderno" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="rechargeDialog.submitting"
                   @click="submitRecharge" data-testid="recharge-submit">入账</el-button>
      </el-form-item>
    </el-form>
  </el-dialog>
</template>
