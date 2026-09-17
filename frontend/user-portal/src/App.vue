<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'
import { Coin, Key, Tickets, DocumentChecked, Reading, HomeFilled } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const activeMenu = computed(() => '/' + (route.path.split('/')[1] ?? 'dashboard'))

const menus = [
  { path: '/dashboard', title: '工作台', icon: HomeFilled },
  { path: '/recharge', title: '充值', icon: Coin },
  { path: '/keys', title: 'APIKey', icon: Key },
  { path: '/transactions', title: '流水', icon: Tickets },
  { path: '/task', title: '任务查询', icon: DocumentChecked },
  { path: '/docs', title: '文档', icon: Reading },
]

function logout() {
  auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <header class="nav">
    <div class="nav-inner">
      <span class="brand" @click="router.push('/dashboard')">
        <span class="mark">S</span>SkillGate
      </span>
      <nav class="links">
        <router-link v-for="m in menus" :key="m.path" :to="m.path"
                     :class="{ active: activeMenu === m.path }">
          <el-icon :size="15"><component :is="m.icon" /></el-icon>{{ m.title }}
        </router-link>
      </nav>
      <span class="spacer" />
      <template v-if="auth.loggedIn">
        <span class="user" data-testid="current-user">{{ auth.identifier }}</span>
        <el-button round size="small" @click="logout">退出</el-button>
      </template>
      <el-button v-else round type="primary" size="small" @click="router.push('/login')">
        登录 / 注册
      </el-button>
    </div>
  </header>
  <main class="page">
    <router-view />
  </main>
</template>

<style>
:root {
  --el-color-primary: #5b5bf6;
  --el-color-primary-light-3: #8a8af9;
  --el-color-primary-light-5: #b0b0fb;
  --el-color-primary-light-7: #d2d2fd;
  --el-color-primary-light-8: #e3e3fe;
  --el-color-primary-light-9: #f1f1ff;
  --el-color-primary-dark-2: #4949c5;

  --brand-gradient: linear-gradient(135deg, #5b5bf6 0%, #9d5bf5 100%);
  --page-bg: #f6f7fb;
  --text-title: #171a23;
  --text-sub: #6b7280;
}

* { box-sizing: border-box; }
body {
  margin: 0;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC',
    'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
  color: var(--text-title);
  background: var(--page-bg);
  -webkit-font-smoothing: antialiased;
}

.nav {
  position: sticky; top: 0; z-index: 20;
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid #e9eaf1;
}
.nav-inner {
  max-width: 1120px; margin: 0 auto; padding: 0 20px;
  display: flex; align-items: center; gap: 18px; height: 60px;
}
.brand {
  display: flex; align-items: center; gap: 8px;
  font-weight: 700; font-size: 16px; cursor: pointer; white-space: nowrap;
}
.brand .mark {
  width: 30px; height: 30px; border-radius: 9px;
  background: var(--brand-gradient); color: #fff;
  display: grid; place-items: center; font-size: 15px;
  box-shadow: 0 4px 10px rgba(91, 91, 246, 0.35);
}
.links { display: flex; gap: 2px; flex-wrap: wrap; }
.links a {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 7px 12px; border-radius: 999px;
  color: #4b5563; text-decoration: none; font-size: 14px;
  transition: background 0.15s, color 0.15s;
}
.links a:hover { background: #f1f1ff; color: var(--el-color-primary); }
.links a.active { background: var(--brand-gradient); color: #fff; font-weight: 500; }
.spacer { flex: 1; }
.user { color: var(--text-sub); font-size: 13px; }

.page { max-width: 1120px; margin: 0 auto; padding: 24px 20px 64px; }

/* 通用卡片 */
.panel-card {
  background: #fff;
  border: 1px solid #eceef4;
  border-radius: 16px;
  padding: 24px;
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.04);
}
.panel-title { font-size: 18px; font-weight: 700; margin: 0 0 4px; }
.panel-sub { color: var(--text-sub); font-size: 13px; margin: 0 0 18px; }

/* 渐变 Hero */
.hero {
  border-radius: 18px;
  background: var(--brand-gradient);
  color: #fff;
  padding: 26px 28px;
  margin-bottom: 20px;
  position: relative;
  overflow: hidden;
}
.hero::after {
  content: '';
  position: absolute; right: -60px; top: -80px;
  width: 260px; height: 260px; border-radius: 50%;
  background: rgba(255, 255, 255, 0.12);
}
.hero h1 { margin: 0 0 6px; font-size: 22px; }
.hero p { margin: 0; opacity: 0.85; font-size: 13px; }

/* 渐变主按钮 */
.btn-gradient {
  background: var(--brand-gradient);
  color: #fff;
  border: none;
  border-radius: 999px;
  padding: 9px 22px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 0.15s, transform 0.15s;
}
.btn-gradient:hover { opacity: 0.92; transform: translateY(-1px); }
.btn-gradient:disabled { opacity: 0.5; cursor: not-allowed; transform: none; }

.mono { font-family: 'SF Mono', ui-monospace, Menlo, Consolas, monospace; }
.secret-line {
  font-family: 'SF Mono', ui-monospace, Menlo, Consolas, monospace;
  word-break: break-all; margin: 10px 0;
  background: #f7f8fc; border: 1px dashed #d5d9e4;
  border-radius: 10px; padding: 12px 14px; font-size: 13px;
}
.pos { color: #12b76a; font-weight: 600; }
.neg { color: #f04438; font-weight: 600; }

@media (max-width: 640px) {
  .brand { font-size: 0; gap: 0; }
  .links a { padding: 6px 8px; }
  .hero { padding: 20px; }
}
</style>
