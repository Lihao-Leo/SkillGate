import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { App as AntdApp, ConfigProvider } from 'antd'
import { MemoryRouter } from 'react-router-dom'

const { rechargeApi } = vi.hoisted(() => ({
  rechargeApi: {
    sendCode: vi.fn(), login: vi.fn(), skus: vi.fn(), listKeys: vi.fn(),
    issueKey: vi.fn(), resetSecret: vi.fn(), disableKey: vi.fn(),
    createOrder: vi.fn(), viewOrder: vi.fn(),
  },
}))
vi.mock('../src/api', () => ({ rechargeApi, gatewayUserApi: {} }))
vi.mock('qrcode', () => ({ default: { toDataURL: vi.fn().mockResolvedValue('data:image/png;base64,qr') } }))

import LoginPage from '../src/pages/Login'
import KeysPage from '../src/pages/Keys'
import RechargePage from '../src/pages/Recharge'
import { loginSession, useAuthStore } from '../src/stores/auth'
import { saveGwCreds, useGatewayCredsStore } from '../src/stores/gwCreds'

function flushPromises() {
  return act(async () => {})
}

function renderWithProviders(ui: React.ReactElement) {
  return render(
    <ConfigProvider>
      <AntdApp>
        <MemoryRouter initialEntries={['/']}>{ui}</MemoryRouter>
      </AntdApp>
    </ConfigProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  logoutAll()
})

function logoutAll() {
  useAuthStore.setState({ token: '', identifier: '' })
  useGatewayCredsStore.setState({ appKey: '', secret: '' })
}

describe('LoginPage', () => {
  it('发送验证码展示联调回显码；登录写入会话', async () => {
    rechargeApi.sendCode.mockResolvedValue({ devEchoCode: '886677', expiresInMinutes: 5 })
    rechargeApi.login.mockResolvedValue({ token: 'jwt-1', userId: 7, identifier: '138****8000' })
    renderWithProviders(<LoginPage />)

    fireEvent.change(screen.getByTestId('identifier-input').querySelector('input')
      ?? screen.getByTestId('identifier-input'), { target: { value: '13800008000' } })
    fireEvent.click(screen.getByTestId('send-code-btn'))
    await flushPromises()
    expect(rechargeApi.sendCode).toHaveBeenCalledWith('13800008000')
    expect(screen.getByTestId('dev-echo-code').textContent).toContain('886677')

    fireEvent.change(screen.getByTestId('code-input').querySelector('input')
      ?? screen.getByTestId('code-input'), { target: { value: '886677' } })
    fireEvent.click(screen.getByTestId('login-btn'))
    await flushPromises()
    expect(useAuthStore.getState().token).toBe('jwt-1')
  })
})

describe('KeysPage', () => {
  it('签发 key → Secret 一次性展示；录入执行平台凭证', async () => {
    rechargeApi.listKeys.mockResolvedValue([
      { appKeyId: 'sk-***-1', isPrimary: true, hasPendingSecret: false, createdAt: '2026-09-15' },
    ])
    rechargeApi.issueKey.mockResolvedValue({ appKeyId: 'sk-full-1', appSecret: 'SEC-ONCE' })
    renderWithProviders(<KeysPage />)
    await flushPromises()

    fireEvent.click(screen.getByTestId('issue-btn'))
    // antd Modal portal
    await screen.findByTestId('once-appkey')
    expect(screen.getByTestId('once-appkey').textContent).toBe('sk-full-1')
    expect(screen.getByTestId('once-appsecret').textContent).toBe('SEC-ONCE')

    // 关闭弹窗后录入执行平台凭证
    fireEvent.click(screen.getByRole('button', { name: '我已保存' }))
    await flushPromises()
    fireEvent.change(screen.getByTestId('creds-appkey'), { target: { value: 'sk-full-1' } })
    fireEvent.change(screen.getByTestId('creds-secret'), { target: { value: 'SEC-ONCE' } })
    fireEvent.click(screen.getByTestId('creds-save'))
    await flushPromises()
    expect(useGatewayCredsStore.getState().appKey).toBe('sk-full-1')
    expect(useGatewayCredsStore.getState().secret).toBe('SEC-ONCE')
    expect(screen.getByTestId('gw-creds-state').textContent).toContain('sk-full-1')
  })

  it('重置 Secret 走轮换接口', async () => {
    rechargeApi.listKeys.mockResolvedValue([
      { appKeyId: 'sk-***-1', isPrimary: true, hasPendingSecret: false, createdAt: '2026-09-15' },
    ])
    rechargeApi.resetSecret.mockResolvedValue({ appKeyId: 'sk-***-1', appSecret: 'NEW-SEC' })
    renderWithProviders(<KeysPage />)
    await flushPromises()

    fireEvent.click(screen.getByTestId('reset-sk-***-1'))
    await flushPromises()
    expect(rechargeApi.resetSecret).toHaveBeenCalledWith('sk-***-1')
    expect(screen.getByTestId('once-appsecret').textContent).toBe('NEW-SEC')
  })
})

describe('RechargePage', () => {
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

    renderWithProviders(<RechargePage />)
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    await flushPromises()
    expect(screen.getByTestId('sku-list').textContent).toContain('1,200')

    // 点选旗舰套餐卡 → CTA 下单
    fireEvent.click(screen.getByTestId('sku-sku-1200'))
    await flushPromises()
    const cta = screen.getByTestId('buy-selected') as HTMLButtonElement
    expect(cta.disabled).toBe(false)
    fireEvent.click(cta)
    await flushPromises()
    expect(rechargeApi.createOrder).toHaveBeenCalledWith('MOCK', 'sku-1200')

    // 弹窗打开（antd Modal portal；fake timers 下 findBy 会卡死，用同步断言）
    expect(document.body.innerHTML).toContain('order-status')

    // 两个轮询周期：第一拍 PAYING，第二拍 CREDITED
    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
    expect(rechargeApi.viewOrder).toHaveBeenCalledTimes(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
    expect(rechargeApi.viewOrder).toHaveBeenCalledTimes(2)

    // CREDITED 后停止轮询
    await act(async () => { await vi.advanceTimersByTimeAsync(9000) })
    expect(rechargeApi.viewOrder).toHaveBeenCalledTimes(2)

    const body = document.body.innerHTML
    expect(body).toContain('CREDITED')
    expect(body).toContain('sk-new-1')
    expect(body).toContain('SEC-FIRST')
  })

  it('自定义金额按元转分下单', async () => {
    vi.useFakeTimers()
    rechargeApi.skus.mockResolvedValue([])
    rechargeApi.createOrder.mockResolvedValue({
      orderNo: 'R2', status: 'PAYING', channel: 'MOCK', points: 10,
      amountFen: 1000, codeUrl: 'mockpay://qr/R2', expiresAt: '2026-09-15T12:00:00',
      paidAt: null, creditedAt: null,
    })
    renderWithProviders(<RechargePage />)
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    await flushPromises()

    fireEvent.click(screen.getByTestId('custom-box'))
    // antd InputNumber：data-testid 直接落在内层 input
    const input = screen.getByTestId('custom-amount') as HTMLInputElement
    fireEvent.change(input, { target: { value: '100' } })
    await flushPromises()
    fireEvent.click(screen.getByTestId('buy-selected'))
    await flushPromises()

    expect(rechargeApi.createOrder).toHaveBeenCalledWith('MOCK', undefined, 10000)
  })
})
