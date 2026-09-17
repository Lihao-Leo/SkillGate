<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { rechargeApi } from '../api'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const form = reactive({ identifier: '', code: '' })
const sending = ref(false)
const countdown = ref(0)
const devEchoCode = ref('')

async function sendCode() {
  if (!form.identifier.trim()) {
    ElMessage.warning('请输入手机号 / 邮箱')
    return
  }
  sending.value = true
  try {
    const result = await rechargeApi.sendCode(form.identifier.trim())
    devEchoCode.value = result.devEchoCode ?? ''
    ElMessage.success(`验证码已发送（${result.expiresInMinutes} 分钟内有效）`)
    countdown.value = 60
    const timer = setInterval(() => {
      countdown.value--
      if (countdown.value <= 0) clearInterval(timer)
    }, 1000)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    sending.value = false
  }
}

async function login() {
  try {
    const result = await rechargeApi.login(form.identifier.trim(), form.code.trim())
    auth.login(result.token, result.identifier)
    ElMessage.success('登录成功')
    router.push({ name: 'dashboard' })
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>

<template>
  <div class="login">
    <div class="card">
      <div class="side">
        <div class="logo">S</div>
        <h2>Skill 执行平台</h2>
        <p>点数计费的 Skill 执行云</p>
        <ul>
          <li>· 充值即用，按次 / 按量计费</li>
          <li>· AppKey 签发，Secret 一次展示</li>
          <li>· 零代码查询任务结果</li>
        </ul>
      </div>
      <div class="form">
        <h3>登录 / 注册</h3>
        <p class="sub">未注册账号将在首次登录时自动创建</p>
        <el-input v-model="form.identifier" size="large" placeholder="手机号 / 邮箱"
                  data-testid="identifier-input" class="field" />
        <div class="code-row field">
          <el-input v-model="form.code" size="large" placeholder="6 位验证码"
                    data-testid="code-input" />
          <el-button size="large" :loading="sending" :disabled="countdown > 0"
                     @click="sendCode" data-testid="send-code-btn">
            {{ countdown > 0 ? `${countdown}s` : '获取验证码' }}
          </el-button>
        </div>
        <button class="btn-gradient block" data-testid="login-btn" @click="login">
          登 录
        </button>
        <el-alert v-if="devEchoCode" type="info" :closable="false"
                  :title="`联调环境验证码：${devEchoCode}`" data-testid="dev-echo-code"
                  style="margin-top: 14px" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.login { display: grid; place-items: center; min-height: calc(100vh - 160px); padding: 20px 0 40px; }
.card {
  display: flex; width: 760px; max-width: 100%;
  background: #fff; border-radius: 20px; overflow: hidden;
  border: 1px solid #eceef4;
  box-shadow: 0 20px 60px rgba(23, 26, 35, 0.08);
}
.side {
  flex: 1; padding: 40px 34px; color: #fff; position: relative;
  background: linear-gradient(150deg, #5b5bf6 0%, #8b5cf6 55%, #b45bf5 100%);
}
.side .logo {
  width: 44px; height: 44px; border-radius: 13px;
  background: rgba(255, 255, 255, 0.18);
  display: grid; place-items: center; font-size: 20px; font-weight: 700;
  margin-bottom: 22px;
}
.side h2 { margin: 0 0 8px; font-size: 22px; }
.side p { margin: 0 0 22px; opacity: 0.85; font-size: 14px; }
.side ul { list-style: none; padding: 0; margin: 0; font-size: 13px; line-height: 2.1; opacity: 0.9; }
.form { flex: 1; padding: 40px 36px; }
.form h3 { margin: 0 0 4px; font-size: 20px; }
.form .sub { color: var(--text-sub); font-size: 13px; margin: 0 0 22px; }
.field { margin-bottom: 14px; }
.code-row { display: flex; gap: 8px; }
.block { width: 100%; padding: 13px 0; font-size: 15px; margin-top: 6px; }
@media (max-width: 700px) { .side { display: none; } }
</style>
