<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Coin, Key, DocumentChecked, Reading, Wallet } from '@element-plus/icons-vue'
import { gatewayUserApi, type BalanceView } from '../api'
import { useGatewayCredsStore } from '../stores/gwCreds'

const router = useRouter()
const gwCreds = useGatewayCredsStore()
const balance = ref<BalanceView | null>(null)
const balanceError = ref('')

async function loadBalance() {
  if (!gwCreds.ready) return
  try {
    balance.value = await gatewayUserApi.balance()
    balanceError.value = ''
  } catch (e) {
    balanceError.value = (e as Error).message
  }
}
onMounted(loadBalance)

const entries = [
  { path: '/recharge', title: '充值', desc: '套餐 / 自定义金额', icon: Coin,
    chip: 'linear-gradient(135deg,#f79009,#f04438)' },
  { path: '/keys', title: 'APIKey 管理', desc: '签发 / 重置 / 禁用', icon: Key,
    chip: 'linear-gradient(135deg,#3b5bf5,#7b5bf5)' },
  { path: '/task', title: '任务查询', desc: '零代码查结果与产物', icon: DocumentChecked,
    chip: 'linear-gradient(135deg,#06aed4,#1570ef)' },
  { path: '/docs', title: '文档中心', desc: '接入指南 / 错误码', icon: Reading,
    chip: 'linear-gradient(135deg,#12b76a,#0e9f6e)' },
]
</script>

<template>
  <div class="hero">
    <h1>{{ gwCreds.ready ? '我的工作台' : '欢迎使用 Skill 平台' }}</h1>
    <p>{{ gwCreds.ready
      ? '点数账户与快捷入口'
      : '充值获得 AppKey 后即可调用 Skill 执行 API' }}</p>
    <template v-if="gwCreds.ready">
      <div class="balance-row">
        <div class="balance-item">
          <el-icon><Wallet /></el-icon>
          可用 <b data-testid="balance">{{ balance?.balance ?? '-' }}</b> 点
        </div>
        <div class="balance-item">
          冻结中 <b data-testid="frozen">{{ balance?.frozen ?? '-' }}</b> 点
        </div>
      </div>
    </template>
  </div>

  <el-alert v-if="balanceError" type="warning" :title="balanceError" :closable="false"
            style="margin-bottom: 16px" />
  <el-alert v-if="!gwCreds.ready" type="info" :closable="false"
            title="尚未配置执行平台调用凭证"
            description="到「APIKey 管理」录入 AppKey + AppSecret 后可查看余额、流水与任务结果"
            style="margin-bottom: 16px" />

  <div class="entries">
    <div v-for="entry in entries" :key="entry.path" class="entry" @click="router.push(entry.path)">
      <span class="chip" :style="{ background: entry.chip }">
        <el-icon :size="20"><component :is="entry.icon" /></el-icon>
      </span>
      <b>{{ entry.title }}</b>
      <p>{{ entry.desc }}</p>
      <span class="arrow">→</span>
    </div>
  </div>
</template>

<style scoped>
.balance-row { display: flex; gap: 26px; margin-top: 16px; position: relative; z-index: 1; }
.balance-item { display: flex; align-items: center; gap: 8px; font-size: 14px; opacity: 0.95; }
.balance-item b { font-size: 24px; font-weight: 800; }

.entries { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 16px; }
.entry {
  background: #fff; border: 1px solid #eceef4; border-radius: 16px;
  padding: 20px; cursor: pointer; position: relative;
  transition: transform 0.15s, box-shadow 0.15s;
}
.entry:hover { transform: translateY(-3px); box-shadow: 0 12px 28px rgba(23, 26, 35, 0.08); }
.entry .chip {
  width: 44px; height: 44px; border-radius: 13px; color: #fff;
  display: grid; place-items: center; margin-bottom: 14px;
}
.entry b { font-size: 16px; }
.entry p { color: var(--text-sub); font-size: 13px; margin: 6px 0 0; }
.entry .arrow {
  position: absolute; right: 18px; top: 22px; color: #c3c8d4;
  transition: right 0.15s, color 0.15s;
}
.entry:hover .arrow { right: 14px; color: var(--el-color-primary); }
</style>
