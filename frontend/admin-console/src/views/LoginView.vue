<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Connection, Lock, User } from '@element-plus/icons-vue'
import { gatewayApi } from '../api/gateway'
import { useAuthStore } from '../stores/auth'
import { useCredentialsStore } from '../stores/credentials'
import { ApiError } from '../api/client'

const router = useRouter()
const auth = useAuthStore()
const creds = useCredentialsStore()

const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名与密码'
    return
  }
  loading.value = true
  try {
    const result = await gatewayApi.adminLogin(username.value.trim(), password.value)
    auth.login({
      token: result.token,
      username: result.username,
      displayName: result.displayName ?? result.username,
      role: result.role,
      roleName: result.roleName,
      permissions: result.permissions,
    })
    ElMessage.success(`欢迎，${auth.displayName}（${auth.roleName}）`)
    // 未绑定业务面 AppKey 时先进连接配置（AccountView 等业务面页面仍需要）
    router.push(creds.appKeyReady ? '/executions' : '/connect')
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <div class="login-card">
      <div class="brand">
        <span class="mark"><el-icon :size="18"><Connection /></el-icon></span>
        <b>SkillGate 管理后台</b>
        <span class="sub">Skill 统一接入与执行平台</span>
      </div>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="username" data-testid="login-username" :prefix-icon="User"
                    placeholder="登录名" autocomplete="username" @keyup.enter="submit" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" data-testid="login-password" :prefix-icon="Lock" type="password"
                    placeholder="密码" show-password autocomplete="current-password"
                    @keyup.enter="submit" />
        </el-form-item>
        <el-alert v-if="error" :title="error" type="error" :closable="false" data-testid="login-error" />
        <el-button type="primary" class="submit" data-testid="login-btn" :loading="loading"
                   native-type="submit" @click="submit">登 录</el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background:
    radial-gradient(1200px 600px at 20% -10%, rgba(43, 127, 255, 0.12), transparent),
    radial-gradient(1000px 500px at 110% 110%, rgba(18, 183, 106, 0.10), transparent);
}
.login-card {
  width: 380px;
  background: #fff;
  border: 1px solid #eef0f4;
  border-radius: 18px;
  padding: 34px 32px 30px;
  box-shadow: 0 18px 50px rgba(31, 36, 48, 0.10);
}
.brand { display: flex; flex-direction: column; gap: 6px; margin-bottom: 22px; }
.brand .mark {
  width: 38px; height: 38px; border-radius: 11px;
  background: var(--brand-gradient); color: #fff;
  display: grid; place-items: center;
  box-shadow: 0 6px 14px rgba(43, 127, 255, 0.3);
}
.brand b { font-size: 18px; }
.brand .sub { color: #98a2b3; font-size: 12.5px; }
.submit { width: 100%; margin-top: 8px; }
</style>
