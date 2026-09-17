import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'

const { rechargeApi } = vi.hoisted(() => ({
  rechargeApi: {
    sendCode: vi.fn(), login: vi.fn(), skus: vi.fn(), listKeys: vi.fn(),
    issueKey: vi.fn(), resetSecret: vi.fn(), disableKey: vi.fn(),
    createOrder: vi.fn(), viewOrder: vi.fn(),
  },
}))
vi.mock('../src/api', () => ({ rechargeApi, gatewayUserApi: {} }))
vi.mock('qrcode', () => ({ default: { toDataURL: vi.fn().mockResolvedValue('data:image/png;base64,qr') } }))

import LoginView from '../src/views/LoginView.vue'
import KeysView from '../src/views/KeysView.vue'
import RechargeView from '../src/views/RechargeView.vue'
import { useAuthStore } from '../src/stores/auth'
import { useGatewayCredsStore } from '../src/stores/gwCreds'
import { createRouter, createMemoryHistory } from 'vue-router'

function testRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', redirect: '/login' },
      { path: '/login', name: 'login', component: { template: '<div/>' } },
      { path: '/dashboard', name: 'dashboard', component: { template: '<div/>' } },
    ],
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  setActivePinia(createPinia())
})

describe('LoginView', () => {
  it('发送验证码展示联调回显码；登录写入会话', async () => {
    rechargeApi.sendCode.mockResolvedValue({ devEchoCode: '886677', expiresInMinutes: 5 })
    rechargeApi.login.mockResolvedValue({ token: 'jwt-1', userId: 7, identifier: '138****8000' })
    const wrapper = mount(LoginView, {
      global: { plugins: [ElementPlus, testRouter()] },
    })

    await wrapper.find('[data-testid="identifier-input"]').setValue('13800008000')
    await wrapper.find('[data-testid="send-code-btn"]').trigger('click')
    await flushPromises()
    expect(rechargeApi.sendCode).toHaveBeenCalledWith('13800008000')
    expect(wrapper.find('[data-testid="dev-echo-code"]').text()).toContain('886677')

    await wrapper.find('[data-testid="code-input"]').setValue('886677')
    await wrapper.find('[data-testid="login-btn"]').trigger('click')
    await flushPromises()
    expect(useAuthStore().token).toBe('jwt-1')
  })
})

describe('KeysView', () => {
  it('签发 key → Secret 一次性展示；录入执行平台凭证', async () => {
    rechargeApi.listKeys.mockResolvedValue([
      { appKeyId: 'sk-***-1', isPrimary: true, hasPendingSecret: false, createdAt: '2026-09-15' },
    ])
    rechargeApi.issueKey.mockResolvedValue({ appKeyId: 'sk-full-1', appSecret: 'SEC-ONCE' })
    const wrapper = mount(KeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.find('[data-testid="issue-btn"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="once-appkey"]').text()).toBe('sk-full-1')
    expect(wrapper.find('[data-testid="once-appsecret"]').text()).toBe('SEC-ONCE')

    await wrapper.find('[data-testid="creds-appkey"]').setValue('sk-full-1')
    await wrapper.find('[data-testid="creds-secret"]').setValue('SEC-ONCE')
    await wrapper.find('[data-testid="creds-save"]').trigger('click')
    await flushPromises()
    expect(useGatewayCredsStore().ready).toBe(true)
    expect(wrapper.find('[data-testid="gw-creds-state"]').text()).toContain('sk-full-1')
  })

  it('重置 Secret 走轮换接口', async () => {
    rechargeApi.listKeys.mockResolvedValue([
      { appKeyId: 'sk-***-1', isPrimary: true, hasPendingSecret: false, createdAt: '2026-09-15' },
    ])
    rechargeApi.resetSecret.mockResolvedValue({ appKeyId: 'sk-***-1', appSecret: 'NEW-SEC' })
    const wrapper = mount(KeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.find('[data-testid="reset-sk-***-1"]').trigger('click')
    await flushPromises()
    expect(rechargeApi.resetSecret).toHaveBeenCalledWith('sk-***-1')
    expect(wrapper.find('[data-testid="once-appsecret"]').text()).toBe('NEW-SEC')
  })
})

describe('RechargeView', () => {
  afterEach(() => vi.useRealTimers())

  it('点选套餐 → CTA 下单 → 轮询 PAYING → CREDITED 展示到账与一次性 key', async () => {
    vi.useFakeTimers()
    rechargeApi.skus.mockResolvedValue([
      { skuId: 'sku-100', name: '入门包', points: 100, priceFen: 9900 },
      { skuId: 'sku-1200', name: '旗舰包（含赠送 200）', points: 1200, priceFen: 10000 },
    ])
    rechargeApi.createOrder.mockResolvedValue({
      orderNo: 'R20260915001', status: 'PAYING', channel: 'MOCK', points: 1200,
      amountFen: 10000, codeUrl: 'mockpay://qr/R20260915001',
      expiresAt: '2026-09-15T12:00:00', paidAt: null, creditedAt: null,
    })
    rechargeApi.viewOrder
      .mockResolvedValueOnce({
        orderNo: 'R20260915001', status: 'PAYING', channel: 'MOCK', points: 1200,
        amountFen: 10000, codeUrl: 'mockpay://qr/R20260915001',
        expiresAt: '2026-09-15T12:00:00', paidAt: null, creditedAt: null,
      })
      .mockResolvedValueOnce({
        orderNo: 'R20260915001', status: 'CREDITED', channel: 'MOCK', points: 1200,
        amountFen: 10000, codeUrl: null, expiresAt: null,
        paidAt: '2026-09-15T11:46:00', creditedAt: '2026-09-15T11:46:05',
        creditedPoints: 1200,
        newlyIssuedKey: { appKeyId: 'sk-new-1', appSecret: 'SEC-FIRST' },
      })

    const wrapper = mount(RechargeView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="sku-list"]').text()).toContain('1,200')

    // 点选旗舰套餐卡 → CTA 下单
    await wrapper.find('[data-testid="sku-sku-1200"]').trigger('click')
    await flushPromises()
    const cta = wrapper.find('[data-testid="buy-selected"]')
    console.log('[dbg] cta disabled=', cta.attributes('disabled'), '| exists=', cta.exists())
    await cta.trigger('click')
    await flushPromises()
    console.log('[dbg] createOrder calls=', rechargeApi.createOrder.mock.calls.length)
    expect(rechargeApi.createOrder).toHaveBeenCalledWith('MOCK', 'sku-1200')

    // 两个轮询周期：第一拍 PAYING，第二拍 CREDITED
    await vi.advanceTimersByTimeAsync(3000)
    expect(rechargeApi.viewOrder).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(3000)
    expect(rechargeApi.viewOrder).toHaveBeenCalledTimes(2)

    // CREDITED 后停止轮询
    await vi.advanceTimersByTimeAsync(9000)
    expect(rechargeApi.viewOrder).toHaveBeenCalledTimes(2)

    const body = document.body.innerHTML + wrapper.html()
    expect(body).toContain('CREDITED')
    expect(body).toContain('sk-new-1')
    expect(body).toContain('SEC-FIRST')
  })

  it('自定义金额按元转分下单', async () => {
    rechargeApi.skus.mockResolvedValue([])
    rechargeApi.createOrder.mockResolvedValue({
      orderNo: 'R2', status: 'PAYING', channel: 'MOCK', points: 10,
      amountFen: 1000, codeUrl: 'mockpay://qr/R2', expiresAt: '2026-09-15T12:00:00',
      paidAt: null, creditedAt: null,
    })
    rechargeApi.viewOrder.mockResolvedValue({
      orderNo: 'R2', status: 'EXPIRED', channel: 'MOCK', points: 10, amountFen: 1000,
      codeUrl: null, expiresAt: null, paidAt: null, creditedAt: null,
    })
    const wrapper = mount(RechargeView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    // 点自定义框清空套餐选择，再设置金额 10 元 → 1000 分
    await wrapper.find('[data-testid="custom-box"]').trigger('click')
    const amountInput = wrapper.find('[data-testid="custom-amount"] input')
    await amountInput.setValue(10)
    await wrapper.find('[data-testid="buy-selected"]').trigger('click')
    await flushPromises()
    expect(rechargeApi.createOrder).toHaveBeenCalledWith('MOCK', undefined, 1000)
  })
})
