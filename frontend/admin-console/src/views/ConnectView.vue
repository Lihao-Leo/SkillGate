<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Connection } from '@element-plus/icons-vue'
import { useCredentialsStore } from '../stores/credentials'
import { gatewayApi } from '../api/gateway'

const router = useRouter()
const store = useCredentialsStore()
const form = reactive({ ...store.$state })
const testing = ref(false)
const testResult = ref('')

/** 双通道连通性校验：业务面（HMAC 调 balance）+ 管理面（token 调 key 列表） */
async function verify() {
  testing.value = true
  testResult.value = ''
  const results: string[] = []
  store.save({ ...form })
  try {
    const balance = await gatewayApi.balance()
    results.push(`业务面 OK（余额 ${balance.balance} / 冻结 ${balance.frozen}）`)
  } catch (e) {
    results.push(`业务面失败：${(e as Error).message}`)
  }
  if (form.adminToken) {
    try {
      const page = await gatewayApi.adminListKeys(undefined, 1, 1)
      results.push(`管理面 OK（共 ${page.total} 把 key）`)
    } catch (e) {
      results.push(`管理面失败：${(e as Error).message}`)
    }
  } else {
    results.push('管理面未配置 AdminToken（跳过）')
  }
  testResult.value = results.join('；')
  testing.value = false
}

const issuing = ref(false)

/** 冷启动自助签发：仅有 AdminToken 时即可经管理面签发一把新 key（Secret 仅此一次返回） */
async function issueKey() {
  if (!form.adminToken) {
    ElMessage.warning('签发需先填 AdminToken（管理面鉴权）')
    return
  }
  store.save({ appKey: form.appKey || '', secret: form.secret || '', adminToken: form.adminToken })
  issuing.value = true
  try {
    const issued = await gatewayApi.adminIssueKey('admin-console')
    form.appKey = issued.appKeyId
    form.secret = issued.appSecret
    ElMessage.success('已签发并填入；Secret 仅此一次返回，请立即点「保存并进入」')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    issuing.value = false
  }
}

function save() {
  if (!form.appKey || !form.secret) {
    ElMessage.warning('AppKey 与 AppSecret 必填（业务面 HMAC 签名）')
    return
  }
  store.save({ ...form })
  ElMessage.success('凭证已保存')
  router.push({ name: 'skills' })
}
</script>

<template>
  <div class="connect">
    <div class="brand-hero">
      <span class="mark"><el-icon :size="22"><Connection /></el-icon></span>
      <h2>Skill 平台管理后台</h2>
      <p>录入运营凭证以接入执行平台</p>
    </div>
    <el-card>
      <el-form label-width="110px" data-testid="connect-form">
        <el-form-item label="AppKey" required>
          <el-input v-model="form.appKey" placeholder="sk-..." data-testid="appkey-input" />
        </el-form-item>
        <el-form-item label="AppSecret" required>
          <el-input v-model="form.secret" type="password" show-password
                    placeholder="签发时一次性展示的完整 Secret" data-testid="secret-input" />
        </el-form-item>
        <el-form-item label="AdminToken">
          <el-input v-model="form.adminToken" type="password" show-password
                    placeholder="管理面 /api/v1/admin/**（AppKey 管理/入账）" data-testid="admintoken-input" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="save" data-testid="save-btn">保存并进入</el-button>
          <el-button :loading="testing" @click="verify" data-testid="verify-btn">连通性校验</el-button>
          <el-button :loading="issuing" @click="issueKey" data-testid="issue-key-btn">
            签发新 Key 并填入
          </el-button>
        </el-form-item>
      </el-form>
      <el-alert v-if="testResult" :title="testResult" :closable="false" data-testid="verify-result" />
      <p class="tip">业务面（Skill/执行/账户）用 AppKey+Secret 做 HMAC 请求签名；管理面（AppKey 管理/入账）用 AdminToken，两者独立。没有 AppKey 时：填好 AdminToken 后点「签发新 Key 并填入」即可自助签发。</p>
    </el-card>
  </div>
</template>

<style scoped>
.connect { max-width: 520px; margin: 40px auto; }
.brand-hero { text-align: center; margin-bottom: 20px; }
.brand-hero .mark {
  width: 52px; height: 52px; border-radius: 14px; margin: 0 auto 12px;
  background: var(--brand-gradient); color: #fff; display: grid; place-items: center;
  box-shadow: 0 8px 24px rgba(59, 91, 245, 0.35);
}
.brand-hero h2 { margin: 0 0 4px; }
.brand-hero p { margin: 0; color: var(--text-sub); font-size: 13px; }
.tip { color: var(--text-sub); font-size: 12px; margin: 12px 0 0; line-height: 1.6; }
</style>
