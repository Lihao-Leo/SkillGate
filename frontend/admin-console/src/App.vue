<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Box, Key, Monitor, Wallet, Setting, Connection, Cpu, Collection, Tools,
  Search, Bell,
} from '@element-plus/icons-vue'
import { useCredentialsStore } from './stores/credentials'

const route = useRoute()
const router = useRouter()
const creds = useCredentialsStore()

const activeMenu = computed(() => '/' + (route.path.split('/')[1] ?? 'executions'))

const menuGroups = [
  {
    title: '运营',
    items: [
      { path: '/executions', title: '执行监控', icon: Monitor },
      { path: '/skills', title: 'Skill 管理', icon: Box },
    ],
  },
  {
    title: '资产',
    items: [
      { path: '/app-keys', title: 'AppKey 与账户', icon: Key },
      { path: '/account', title: '账户流水', icon: Wallet },
    ],
  },
  {
    title: '平台',
    items: [
      { path: '/models', title: '模型与能力', icon: Cpu },
      { path: '/kb', title: '知识库', icon: Collection },
      { path: '/sysconfig', title: '系统配置', icon: Tools },
    ],
  },
  {
    title: '系统',
    items: [
      { path: '/connect', title: '连接配置', icon: Setting },
    ],
  },
]

const userInitial = '运'

function logout() {
  creds.clear()
  router.push({ name: 'connect' })
}
</script>

<template>
  <div class="layout">
    <header class="topbar">
      <div class="brand" @click="router.push('/executions')">
        <span class="mark"><el-icon :size="17"><Connection /></el-icon></span>
        <b>SkillGate</b>
        <span class="divider">·</span>
        <span class="product">Skill 统一接入网关 · 管理后台</span>
        <span class="env-badge">生产环境</span>
      </div>
      <div class="top-right">
        <el-icon class="icon-btn"><Search /></el-icon>
        <span class="bell">
          <el-icon class="icon-btn"><Bell /></el-icon>
          <span class="dot" />
        </span>
        <span class="avatar">{{ userInitial }}</span>
        <span class="user">{{ creds.appKeyReady ? '运营 · 管理员' : '未登录' }}</span>
        <el-button v-if="creds.appKeyReady" link size="small" class="logout" @click="logout">退出</el-button>
      </div>
    </header>

    <div class="body">
      <aside class="sidebar">
        <nav class="menu">
          <template v-for="group in menuGroups" :key="group.title">
            <div class="group-title">{{ group.title }}</div>
            <router-link v-for="item in group.items" :key="item.path" :to="item.path"
                         class="menu-item" :class="{ active: activeMenu === item.path }">
              <el-icon><component :is="item.icon" /></el-icon>
              <span>{{ item.title }}</span>
            </router-link>
          </template>
        </nav>
      </aside>

      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout { min-height: 100vh; display: flex; flex-direction: column; }

.topbar {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #eef0f4;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  position: sticky;
  top: 0;
  z-index: 30;
}
.brand { display: flex; align-items: center; gap: 8px; cursor: pointer; }
.brand .mark {
  width: 30px; height: 30px; border-radius: 9px;
  background: var(--brand-gradient); color: #fff;
  display: grid; place-items: center;
  box-shadow: 0 4px 10px rgba(43, 127, 255, 0.3);
}
.brand b { font-size: 16px; }
.divider { color: #c3c8d4; }
.product { color: #6b7280; font-size: 13px; }
.env-badge {
  background: #e8f8ee; color: #12b76a; font-size: 12px;
  border-radius: 6px; padding: 2px 8px; font-weight: 600;
}
.top-right { display: flex; align-items: center; gap: 12px; }
.icon-btn { color: #6b7280; cursor: pointer; font-size: 16px; }
.bell { position: relative; display: inline-flex; }
.bell .dot {
  position: absolute; top: -2px; right: -3px;
  width: 7px; height: 7px; border-radius: 50%; background: #f04438;
}
.avatar {
  width: 30px; height: 30px; border-radius: 50%;
  background: var(--brand-gradient); color: #fff;
  display: grid; place-items: center; font-size: 13px; font-weight: 600;
}
.user { color: #6b7280; font-size: 13px; }
.logout { color: #98a2b3; }

.body { display: flex; flex: 1; }
.sidebar {
  width: 190px;
  flex: none;
  background: #fbfbfd;
  border-right: 1px solid #eef0f4;
  padding: 14px 10px;
  position: sticky;
  top: 56px;
  height: calc(100vh - 56px);
  overflow-y: auto;
}
.menu { display: flex; flex-direction: column; }
.group-title { font-size: 11px; color: #98a2b3; padding: 10px 10px 6px; letter-spacing: 1px; }
.menu-item {
  display: flex; align-items: center; gap: 9px;
  padding: 8px 12px; margin: 2px 0;
  border-radius: 999px; color: #475467; text-decoration: none; font-size: 13.5px;
  transition: background 0.15s, color 0.15s;
}
.menu-item:hover { background: #f1f4f9; color: #1f2430; }
.menu-item.active {
  background: var(--brand-gradient); color: #fff; font-weight: 600;
  box-shadow: 0 4px 12px rgba(43, 127, 255, 0.28);
}

.content { flex: 1; padding: 20px 24px 48px; min-width: 0; }
</style>
