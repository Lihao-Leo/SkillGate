<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import QRCode from 'qrcode'
import { rechargeApi, gatewayUserApi, type Sku, type OrderView } from '../api'
import { useGatewayCredsStore } from '../stores/gwCreds'

const gwCreds = useGatewayCredsStore()
const skus = ref<Sku[]>([])
const selectedSkuId = ref<string>('')
const channel = ref('MOCK')
const customPoints = ref<number | null>(null)
const order = ref<OrderView | null>(null)
const qrDataUrl = ref('')
const polling = ref(false)
const dialogVisible = ref(false)
const balance = ref<{ balance: number; frozen: number } | null>(null)
let timer: ReturnType<typeof setInterval> | null = null
let pollCount = 0

const UNIT_PRICE = 0.083 // 参考单价（元/点），按最低档展示

const selectedSku = computed(() => skus.value.find(s => s.skuId === selectedSkuId.value) ?? null)
const customAmountYuan = computed(() =>
  customPoints.value && customPoints.value > 0
    ? Math.round(customPoints.value * UNIT_PRICE * 100) / 100
    : null)

function giftOf(name: string): string | null {
  const m = name.match(/赠\s*(\d+)\s*点?/)
  return m ? `赠 ${m[1]} 点` : null
}
function pointsOf(sku: Sku): number {
  const m = sku.name.match(/含赠送\s*(\d+)/)
  return m ? Number(m[1]) : 0
}
function yuan(fen: number): string {
  return `¥${(fen / 100).toFixed(2)}`
}

onMounted(async () => {
  try {
    skus.value = await rechargeApi.skus()
    if (skus.value.length > 0) {
      selectedSkuId.value = skus.value[Math.min(2, skus.value.length - 1)].skuId
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
  if (gwCreds.ready) {
    try {
      balance.value = await gatewayUserApi.balance()
    } catch { /* 忽略 */ }
  }
})
onUnmounted(stopPolling)

function stopPolling() {
  if (timer) clearInterval(timer)
  timer = null
  polling.value = false
}

const canPay = computed(() => Boolean(selectedSkuId.value) || (customPoints.value ?? 0) > 0)
const payLabel = computed(() => selectedSku.value ? yuan(selectedSku.value.priceFen)
  : customPoints.value ? `¥${(customPoints.value * UNIT_PRICE).toFixed(2)}` : '—')

function selectSku(sku: Sku) {
  selectedSkuId.value = sku.skuId
  customPoints.value = null
}

async function buySku(sku: Sku) {
  await create(() => rechargeApi.createOrder(channel.value, sku.skuId))
}

async function pay() {
  console.log('[dbg3] pay enter: channel=', channel.value, 'selectedSkuId=', selectedSkuId.value, 'customPoints=', customPoints.value)
  console.log('[dbg] pay:', selectedSkuId.value, '| skus:', JSON.stringify(skus.value), '| selectedSku:', JSON.stringify(selectedSku.value))
  if (selectedSkuId.value) return buySku(selectedSku.value!)
  const points = customPoints.value
  if (!points || points <= 0) {
    ElMessage.warning('请选择套餐或输入购买点数')
    return
  }
  await create(() => rechargeApi.createOrder(channel.value, undefined, Math.round(points * 100)))
}

async function create(action: () => Promise<OrderView>) {
  try {
    order.value = await action()
    qrDataUrl.value = order.value.codeUrl
      ? await QRCode.toDataURL(order.value.codeUrl, { width: 220, margin: 1 })
      : ''
    dialogVisible.value = true
    startPolling()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function startPolling() {
  stopPolling()
  polling.value = true
  pollCount = 0
  timer = setInterval(poll, 3000)
}

async function poll() {
  if (!order.value) return stopPolling()
  pollCount++
  try {
    const view = await rechargeApi.viewOrder(order.value.orderNo)
    order.value = view
    if (view.status === 'CREDITED' || view.status === 'EXPIRED') stopPolling()
  } catch (e) {
    if (pollCount % 10 === 0) ElMessage.warning((e as Error).message)
  }
}
</script>

<template>
  <div class="crumb">充值中心 / 点数充值</div>
  <h1 class="title">点数充值</h1>
  <p class="subtitle">点数用于 Skill 执行计费 · 支付成功自动入账 · 首次购买自动签发 APIKey</p>

  <div class="grid">
    <!-- 左：选择套餐 -->
    <div class="panel-card">
      <div class="block-head">
        <b>选择套餐</b>
        <span class="tag-green">多买多赠</span>
      </div>
      <div class="skus" data-testid="sku-list">
        <div v-for="sku in skus" :key="sku.skuId" class="sku"
             :class="{ selected: selectedSkuId === sku.skuId }"
             :data-testid="`sku-${sku.skuId}`"
             @click="selectSku(sku)">
          <span v-if="selectedSkuId === sku.skuId" class="check">✓</span>
          <span v-if="giftOf(sku.name)" class="gift">{{ giftOf(sku.name) }}</span>
          <div class="sku-points">{{ sku.points.toLocaleString() }} <small>点</small></div>
          <div class="sku-price">{{ yuan(sku.priceFen) }}</div>
          <div class="sku-unit">¥{{ (sku.priceFen / sku.points).toFixed(3) }} / 点</div>
        </div>
      </div>

      <div class="custom">
        <div class="custom-label"><b>自定义金额</b></div>
        <div class="custom-box" data-testid="custom-box" @click="selectedSkuId = ''">
          <span class="custom-hint">购买点数</span>
          <el-input-number v-model="customPoints" :min="1" :max="10000" :step="100"
                           :controls="false" data-testid="custom-amount" style="width: 200px" />
          <span v-if="customAmountYuan" class="custom-calc">
            ≈ ¥{{ customAmountYuan.toFixed(2) }} · ¥{{ UNIT_PRICE }} / 点
          </span>
        </div>
      </div>

      <div class="block-head" style="margin-top: 26px">
        <b>支付方式</b>
        <span class="tag-green">均支持扫码支付</span>
      </div>
      <div class="channels" data-testid="channel">
        <div class="channel-card" :class="{ on: channel === 'MOCK' }" @click="channel = 'MOCK'">
          <span class="ch-icon" style="background: linear-gradient(135deg,#22c55e,#16a34a)">联</span>
          <div><b>联调渠道</b><div class="ch-sub">本地 Mock · 立即到账</div></div>
        </div>
        <div class="channel-card disabled">
          <span class="ch-icon" style="background: linear-gradient(135deg,#22c55e,#15803d)">微</span>
          <div><b>微信支付</b><div class="ch-sub">扫码支付 / H5 拉起</div></div>
          <span class="radio-dot" />
        </div>
      </div>

      <button class="cta" data-testid="buy-selected" :disabled="!canPay" @click="pay">
        立即支付 · {{ payLabel }}
      </button>

      <ul class="notes">
        <li>· 订单号幂等防重，重复支付自动去重</li>
        <li>· 入账即生效，冻结点数可在「流水」按任务对账</li>
        <li>· APIKey 完整值仅签发/重置时展示一次</li>
      </ul>
    </div>
  </div>

  <el-dialog v-model="dialogVisible" :title="`订单 ${order?.orderNo ?? ''}`"
             width="420px" align-center :close-on-click-modal="false" @close="stopPolling">
    <template v-if="order">
      <div class="status-row">
        <span class="status" data-testid="order-status">{{ order.status }}</span>
        <span v-if="polling" class="polling-dot" />
      </div>
      <template v-if="['CREATED', 'PAYING', 'PAID'].includes(order.status)">
        <img v-if="qrDataUrl" :src="qrDataUrl" alt="支付二维码" class="qr" data-testid="qr">
        <p class="hint">{{ yuan(order.amountFen) }} · {{ order.points }} 点 · 二维码 15 分钟有效</p>
        <p class="hint">支付后自动确认，无需手动刷新</p>
      </template>
      <template v-else-if="order.status === 'CREDITED'">
        <el-result icon="success" :title="`到账 ${order.creditedPoints ?? order.points} 点`" data-testid="credited">
          <template #extra>
            <template v-if="order.newlyIssuedKey">
              <el-alert type="warning" :closable="false" title="首次购买已签发 APIKey，Secret 仅此一次展示" />
              <p class="secret" data-testid="new-key-id">{{ order.newlyIssuedKey.appKeyId }}</p>
              <p class="secret" data-testid="new-key-secret">{{ order.newlyIssuedKey.appSecret }}</p>
            </template>
          </template>
        </el-result>
      </template>
      <template v-else-if="order.status === 'EXPIRED'">
        <el-result icon="warning" title="订单已过期" sub-title="15 分钟未支付，请重新下单" data-testid="expired" />
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.crumb { color: #8a91a5; font-size: 12px; margin-bottom: 10px; }
.title { font-size: 24px; font-weight: 800; margin: 0 0 6px; }
.subtitle { color: #6b7280; font-size: 13px; margin: 0 0 20px; }
.grid { display: grid; grid-template-columns: 1.15fr 1fr; gap: 18px; }
.panel-card, .panel-card2 {
  background: #fff; border: 1px solid #e9ebf2; border-radius: 16px; padding: 22px 24px;
}
.block-head { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; font-size: 15px; }
.tag-green { background: #e8f8ee; color: #16a34a; font-size: 11px; border-radius: 999px; padding: 2px 10px; }

.skus { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.sku {
  position: relative; text-align: center; padding: 18px 10px 14px;
  border: 1.5px solid #e9ebf2; border-radius: 12px; cursor: pointer;
  transition: border-color .15s, background .15s, transform .15s;
}
.sku:hover { transform: translateY(-2px); }
.sku.selected { border-color: #2b7fff; background: #f0f6ff; }
.sku.selected::after {
  content: '✓'; position: absolute; top: -9px; right: -9px;
  width: 22px; height: 22px; border-radius: 50%;
  background: #2b7fff; color: #fff; font-size: 12px;
  display: grid; place-items: center;
}
.sku-points { font-size: 22px; font-weight: 800; }
.sku-points small { font-size: 12px; font-weight: 400; color: #6b7280; }
.sku-price { font-size: 15px; font-weight: 700; margin-top: 4px; }
.sku-unit { font-size: 11px; color: #8a91a5; margin: 2px 0 8px; }
.gift {
  position: absolute; bottom: 44px; left: 50%; transform: translateX(-50%);
  background: #e8f8ee; color: #16a34a; font-size: 11px;
  border-radius: 999px; padding: 2px 10px; white-space: nowrap;
}

.custom { margin-top: 22px; padding-top: 18px; border-top: 1px dashed #e5e7ee; }
.custom-label { font-size: 14px; font-weight: 600; margin-bottom: 10px; }
.custom-box {
  display: flex; align-items: center; gap: 12px;
  border: 1px dashed #d5d9e4; border-radius: 10px; padding: 12px 14px; flex-wrap: wrap;
}
.custom-hint { color: #6b7280; font-size: 13px; }
.custom-calc { color: #2b7fff; font-size: 13px; font-weight: 600; }

.channels { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 22px; padding-top: 18px; border-top: 1px dashed #e5e7ee; }
.channel-card {
  position: relative; display: flex; align-items: center; gap: 10px;
  border: 1.5px solid #e9ebf2; border-radius: 12px; padding: 12px 14px; cursor: pointer;
}
.channel-card.on { border-color: #2b7fff; background: #f0f6ff; }
.channel-card.disabled { opacity: 0.55; cursor: not-allowed; }
.ch-icon {
  width: 34px; height: 34px; border-radius: 8px; color: #fff;
  display: grid; place-items: center; font-size: 14px; font-weight: 700;
}
.channel-card b { font-size: 14px; }
.ch-sub { font-size: 11px; color: #8a91a5; }
.radio-dot {
  position: absolute; right: 12px; top: 50%; transform: translateY(-50%);
  width: 16px; height: 16px; border-radius: 50%; border: 5px solid #2b7fff; background: #fff;
}
.notes { list-style: none; padding: 0; margin: 16px 0 0; color: #8a91a5; font-size: 12px; line-height: 2; }

.cta {
  width: 100%; margin-top: 18px; padding: 14px 0;
  background: linear-gradient(180deg, #338bff, #1f6bff); color: #fff;
  border: none; border-radius: 12px; font-size: 16px; font-weight: 700; cursor: pointer;
  box-shadow: 0 10px 24px rgba(43, 127, 255, 0.35);
}
.cta:hover { filter: brightness(1.05); }
.cta:disabled { opacity: 0.5; cursor: not-allowed; }

.status-row { display: flex; align-items: center; justify-content: center; gap: 8px; margin-bottom: 14px; }
.status { font-weight: 700; font-size: 15px; }
.polling-dot { width: 8px; height: 8px; border-radius: 50%; background: #2b7fff; animation: pulse 1.2s infinite; }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }
.qr { display: block; margin: 0 auto 10px; border-radius: 12px; border: 1px solid #e9ebf2; }
.hint { color: #8a91a5; font-size: 13px; text-align: center; margin: 4px 0; }
.secret { font-family: ui-monospace, Menlo, monospace; word-break: break-all; margin: 8px 0; font-size: 13px; }

@media (max-width: 760px) {
  .grid { grid-template-columns: 1fr; }
  .skus { grid-template-columns: repeat(2, 1fr); }
}
</style>
