import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Statistic } from 'antd'
import { gatewayUserApi } from '../api'
import { gwCredsReady, useGatewayCredsStore } from '../stores/gwCreds'

/** 工作台：余额概览 + 快捷入口。 */
export default function DashboardPage() {
  const navigate = useNavigate()
  const ready = gwCredsReady(useGatewayCredsStore.getState())
  const [balance, setBalance] = useState<{ balance: number; frozen: number } | null>(null)

  useEffect(() => {
    if (!ready) return
    gatewayUserApi.balance()
      .then(setBalance)
      .catch(() => { /* 凭证失效等忽略 */ })
  }, [ready])

  const entries = [
    { path: '/recharge', title: '点数充值', desc: '套餐 / 自定义金额，支付后自动入账' },
    { path: '/keys', title: 'APIKey 管理', desc: '签发 / 轮换 / 录入执行平台凭证' },
    { path: '/task', title: '任务查询', desc: '按 taskId 查执行状态与产物' },
    { path: '/docs', title: '接入文档', desc: 'execute API 与签名算法说明' },
  ]

  return (
    <div>
      <div className="crumb">工作台</div>
      <h1 className="title">欢迎使用 SkillGate</h1>
      <p className="subtitle">统一 Skill 执行平台的用户侧入口</p>

      {!ready && (
        <Alert
          type="info" showIcon style={{ marginBottom: 16 }}
          message="尚未配置执行平台凭证"
          description="先到「APIKey」页签发 Key 并录入凭证，即可查询余额 / 流水 / 任务。"
          action={<Button size="small" onClick={() => navigate('/keys')}>去配置</Button>}
        />
      )}

      <div style={{ display: 'flex', gap: 16, marginBottom: 18 }}>
        <Card style={{ flex: 1 }}>
          <Statistic title="可用点数" value={balance?.balance ?? '—'} />
        </Card>
        <Card style={{ flex: 1 }}>
          <Statistic title="冻结点数" value={balance?.frozen ?? '—'} />
        </Card>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12 }}>
        {entries.map((entry) => (
          <Card key={entry.path} hoverable style={{ cursor: 'pointer' }}
                onClick={() => navigate(entry.path)}>
            <b>{entry.title}</b>
            <div style={{ color: 'var(--ant-color-text-tertiary)', fontSize: 12.5, marginTop: 6 }}>
              {entry.desc}
            </div>
          </Card>
        ))}
      </div>
    </div>
  )
}
