import { useEffect, useRef, useState } from 'react'
import { Alert, App as AntdApp, Button, InputNumber, Modal, Result } from 'antd'
import QRCode from 'qrcode'
import { gatewayUserApi, rechargeApi } from '../api'
import type { OrderView, Sku } from '../api'
import { gwCredsReady, useGatewayCredsStore } from '../stores/gwCreds'

const UNIT_PRICE = 0.083 // 参考单价（元/点），按最低档展示

function giftOf(name: string): string | null {
  const m = name.match(/赠\s*(\d+)\s*点?/)
  return m ? `赠 ${m[1]} 点` : null
}
function yuan(fen: number): string {
  return `¥${(fen / 100).toFixed(2)}`
}

export default function RechargePage() {
  const { message } = AntdApp.useApp()
  const gwReady = gwCredsReady(useGatewayCredsStore.getState())
  const [skus, setSkus] = useState<Sku[]>([])
  const [selectedSkuId, setSelectedSkuId] = useState('')
  const [channel] = useState('MOCK')
  const [customPoints, setCustomPoints] = useState<number | null>(null)
  const [order, setOrder] = useState<OrderView | null>(null)
  const [qrDataUrl, setQrDataUrl] = useState('')
  const [dialogOpen, setDialogOpen] = useState(false)
  const [balance, setBalance] = useState<{ balance: number; frozen: number } | null>(null)

  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const pollCountRef = useRef(0)

  const selectedSku = skus.find((s) => s.skuId === selectedSkuId) ?? null
  const customAmountYuan = customPoints && customPoints > 0
    ? Math.round(customPoints * UNIT_PRICE * 100) / 100
    : null
  const canPay = Boolean(selectedSkuId) || (customPoints ?? 0) > 0
  const payLabel = selectedSku
    ? yuan(selectedSku.priceFen)
    : customPoints ? `¥${(customPoints * UNIT_PRICE).toFixed(2)}` : '—'

  function stopPolling() {
    if (pollRef.current) clearInterval(pollRef.current)
    pollRef.current = null
  }

  useEffect(() => {
    (async () => {
      try {
        const list = await rechargeApi.skus()
        setSkus(list)
        if (list.length > 0) {
          setSelectedSkuId(list[Math.min(2, list.length - 1)].skuId)
        }
      } catch (e) {
        message.error((e as Error).message)
      }
      if (gwReady) {
        try {
          setBalance(await gatewayUserApi.balance())
        } catch { /* 忽略 */ }
      }
    })()
    return stopPolling
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** 支付状态轮询：3s 一拍，终态 CREDITED/EXPIRED 停（useEffect cleanup，StrictMode 安全）。 */
  useEffect(() => {
    if (!dialogOpen || !order) return
    async function poll() {
      if (!order) return
      pollCountRef.current += 1
      try {
        const view = await rechargeApi.viewOrder(order.orderNo)
        setOrder(view)
        if (view.status === 'CREDITED' || view.status === 'EXPIRED') stopPolling()
      } catch (e) {
        if (pollCountRef.current % 10 === 0) message.warning((e as Error).message)
      }
    }
    pollRef.current = setInterval(poll, 3000)
    return stopPolling
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dialogOpen, order?.orderNo])

  function selectSku(sku: Sku) {
    setSelectedSkuId(sku.skuId)
    setCustomPoints(null)
  }

  async function create(action: () => Promise<OrderView>) {
    try {
      const created = await action()
      setOrder(created)
      setQrDataUrl(created.codeUrl
        ? await QRCode.toDataURL(created.codeUrl, { width: 220, margin: 1 })
        : '')
      setDialogOpen(true)
      pollCountRef.current = 0
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function pay() {
    if (selectedSkuId && selectedSku) {
      await create(() => rechargeApi.createOrder(channel, selectedSku.skuId))
      return
    }
    const points = customPoints
    if (!points || points <= 0) {
      message.warning('请选择套餐或输入购买点数')
      return
    }
    await create(() => rechargeApi.createOrder(channel, undefined, Math.round(points * 100)))
  }

  return (
    <div>
      <div className="crumb">充值中心 / 点数充值</div>
      <h1 className="title">点数充值</h1>
      <p className="subtitle">
        点数用于 Skill 执行计费 · 支付成功自动入账 · 首次购买自动签发 APIKey
      </p>

      <div className="panel-card">
        <div className="block-head">
          <b>选择套餐</b>
          <span className="tag-green">多买多赠</span>
          {balance && (
            <span style={{ marginLeft: 'auto', color: 'var(--ant-color-text-tertiary)', fontSize: 12.5 }}>
              当前余额 {balance.balance} 点 / 冻结 {balance.frozen}
            </span>
          )}
        </div>
        <div className="skus" data-testid="sku-list">
          {skus.map((sku) => {
            const gift = giftOf(sku.name)
            const selected = selectedSkuId === sku.skuId
            return (
              <div key={sku.skuId}
                   className={`sku${selected ? ' selected' : ''}`}
                   data-testid={`sku-${sku.skuId}`}
                   onClick={() => selectSku(sku)}>
                {selected && <span className="check">✓</span>}
                {gift && <span className="gift">{gift}</span>}
                <div className="sku-points">{sku.points.toLocaleString()} <small>点</small></div>
                <div className="sku-price">{yuan(sku.priceFen)}</div>
                <div className="sku-unit">¥{(sku.priceFen / sku.points).toFixed(3)} / 点</div>
              </div>
            )
          })}
        </div>

        <div className="custom">
          <div className="custom-label"><b>自定义金额</b></div>
          <div className="custom-box" data-testid="custom-box"
               onClick={() => setSelectedSkuId('')}>
            <span className="custom-hint">购买点数</span>
            <InputNumber data-testid="custom-amount" min={1} max={10000} step={100}
                         controls={false} style={{ width: 200 }} value={customPoints}
                         onChange={(v) => setCustomPoints(v)} />
            {customAmountYuan && (
              <span className="custom-calc">
                ≈ ¥{customAmountYuan.toFixed(2)} · ¥{UNIT_PRICE} / 点
              </span>
            )}
          </div>
        </div>

        <div className="block-head" style={{ marginTop: 26 }}>
          <b>支付方式</b>
          <span className="tag-green">均支持扫码支付</span>
        </div>
        <div className="channels" data-testid="channel">
          <div className="channel-card on">
            <span className="ch-icon" style={{ background: 'linear-gradient(135deg,#22c55e,#16a34a)' }}>联</span>
            <div><b>联调渠道</b><div className="ch-sub">本地 Mock · 立即到账</div></div>
          </div>
          <div className="channel-card disabled">
            <span className="ch-icon" style={{ background: 'linear-gradient(135deg,#22c55e,#15803d)' }}>微</span>
            <div><b>微信支付</b><div className="ch-sub">扫码支付 / H5 拉起</div></div>
            <span className="radio-dot" />
          </div>
        </div>

        <button className="cta" data-testid="buy-selected" disabled={!canPay} onClick={pay}>
          立即支付 · {payLabel}
        </button>

        <ul className="notes">
          <li>· 订单号幂等防重，重复支付自动去重</li>
          <li>· 入账即生效，冻结点数可在「流水」按任务对账</li>
          <li>· APIKey 完整值仅签发/重置时展示一次</li>
        </ul>
      </div>

      <Modal
        open={dialogOpen}
        title={`订单 ${order?.orderNo ?? ''}`}
        width={420}
        onCancel={() => setDialogOpen(false)}
        afterClose={stopPolling}
        maskClosable={false}
        footer={null}
      >
        {order && (
          <>
            <div className="status-row">
              <span className="status" data-testid="order-status">{order.status}</span>
            </div>
            {['CREATED', 'PAYING', 'PAID'].includes(order.status) && (
              <>
                {qrDataUrl && (
                  <img src={qrDataUrl} alt="支付二维码" className="qr" data-testid="qr" />
                )}
                <p className="hint">
                  {yuan(order.amountFen)} · {order.points} 点 · 二维码 15 分钟有效
                </p>
                <p className="hint">支付后自动确认，无需手动刷新</p>
              </>
            )}
            {order.status === 'CREDITED' && (
              <div data-testid="credited">
                <Result status="success" title={`到账 ${order.creditedPoints ?? order.points} 点`} />
                {order.newlyIssuedKey && (
                  <div style={{ marginTop: -12 }}>
                    <Alert type="warning" showIcon={false}
                           message="首次购买已签发 APIKey，Secret 仅此一次展示" />
                    <p className="secret" data-testid="new-key-id">{order.newlyIssuedKey.appKeyId}</p>
                    <p className="secret" data-testid="new-key-secret">{order.newlyIssuedKey.appSecret}</p>
                  </div>
                )}
              </div>
            )}
            {order.status === 'EXPIRED' && (
              <div data-testid="expired">
                <Result status="warning" title="订单已过期" subTitle="15 分钟未支付，请重新下单" />
              </div>
            )}
          </>
        )}
        <div style={{ textAlign: 'center', marginTop: 8 }}>
          <Button onClick={() => setDialogOpen(false)}>关闭</Button>
        </div>
      </Modal>
    </div>
  )
}
