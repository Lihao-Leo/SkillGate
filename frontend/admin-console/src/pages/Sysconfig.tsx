import { useEffect, useState } from 'react'
import { App as AntdApp, Button, Card, Form, Input, InputNumber, Space, Tag } from 'antd'
import { gatewayApi } from '../api/gateway'

/** 系统配置：飞书告警通知（结构化四键 + 连通性测试）+ 配额三键（DB 覆盖 yml 默认）。 */
export default function SysconfigPage() {
  const { message } = AntdApp.useApp()
  const [quota, setQuota] = useState<Record<string, string>>({})
  const [alert, setAlert] = useState({
    webhookUrl: '', secret: '', runningSeconds: 600, tokensThreshold: 0,
  })
  const [alertConfigured, setAlertConfigured] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    (async () => {
      try {
        const config = await gatewayApi.adminSysConfig()
        setQuota(config)
        const alertConfig = await gatewayApi.adminAlertConfig()
        setAlert({
          webhookUrl: alertConfig.webhookUrl ?? '',
          secret: alertConfig.secret ?? '',
          runningSeconds: alertConfig.runningSeconds ?? 600,
          tokensThreshold: alertConfig.tokensThreshold ?? 0,
        })
        setAlertConfigured(Boolean(alertConfig.webhookUrl))
      } catch (e) {
        message.error((e as Error).message)
      }
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function saveAlert() {
    setSaving(true)
    try {
      await gatewayApi.adminSaveAlertConfig(alert)
      setAlertConfigured(Boolean(alert.webhookUrl))
      message.success('告警配置已保存（worker 60s 内热生效）')
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  async function testAlert() {
    setTesting(true)
    setTestResult('')
    try {
      const result = await gatewayApi.adminTestAlertConfig()
      setTestResult(`${result.ok ? '发送成功' : '发送失败'}：${result.message}`)
    } catch (e) {
      setTestResult(`发送失败：${(e as Error).message}`)
    } finally {
      setTesting(false)
    }
  }

  async function saveQuota() {
    const updates: Record<string, string> = {}
    for (const key of ['quota.qps', 'quota.max-running', 'quota.daily-limit']) {
      if (quota[key] !== undefined && quota[key] !== '') {
        updates[key] = quota[key]
      }
    }
    try {
      await gatewayApi.adminUpdateSysConfig(updates)
      message.success('配额已保存（留空项继续使用网关默认值）')
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  return (
    <div>
      <div className="page-header">
        <div>
          <h2>系统配置</h2>
          <p className="sub">运行时配置：sys_config 统一维护，DB 值覆盖 yml 默认（worker/gateway 60s 内热生效）</p>
        </div>
      </div>

      <Card
        title={<span>飞书告警通知 <Tag color={alertConfigured ? 'success' : 'default'}>
          {alertConfigured ? '已配置' : '未配置'}</Tag></span>}
        style={{ marginBottom: 16 }}
      >
        <Form labelCol={{ style: { width: 150 } }}>
          <Form.Item label="webhook 地址">
            <Input data-testid="alert-webhook" value={alert.webhookUrl}
                   placeholder="https://open.feishu.cn/open-apis/bot/v2/hook/..."
                   onChange={(e) => setAlert({ ...alert, webhookUrl: e.target.value })} />
          </Form.Item>
          <Form.Item label="加签 secret">
            <Input.Password data-testid="alert-secret" value={alert.secret}
                            onChange={(e) => setAlert({ ...alert, secret: e.target.value })} />
          </Form.Item>
          <Form.Item label="长跑告警阈值（秒）">
            <InputNumber data-testid="alert-running" min={0} step={60} value={alert.runningSeconds}
                         onChange={(v) => setAlert({ ...alert, runningSeconds: v ?? 600 })} />
          </Form.Item>
          <Form.Item label="Token 告警阈值">
            <InputNumber data-testid="alert-tokens" min={0} value={alert.tokensThreshold}
                         onChange={(v) => setAlert({ ...alert, tokensThreshold: v ?? 0 })} />
            <span className="upload-hint" style={{ marginLeft: 8 }}>
              0 = 关闭（V2.1 起推荐用「告警中心」的规则替代全局阈值）
            </span>
          </Form.Item>
          <Space>
            <Button data-testid="alert-test" loading={testing} onClick={testAlert}>发送测试消息</Button>
            <Button type="primary" data-testid="alert-save" loading={saving} onClick={saveAlert}>
              保存告警配置
            </Button>
          </Space>
          {testResult && <p style={{ marginTop: 10 }}>{testResult}</p>}
        </Form>
      </Card>

      <Card title="配额（全局受理闸门）">
        <Form labelCol={{ style: { width: 220 } }}>
          <Form.Item label="QPS 上限（quota.qps）" tooltip="单 AppKey 受理限流">
            <Input data-testid="quota-qps" value={quota['quota.qps'] ?? ''}
                   placeholder="留空 = 网关默认（10）"
                   onChange={(e) => setQuota({ ...quota, 'quota.qps': e.target.value })} />
          </Form.Item>
          <Form.Item label="最大并行执行（quota.max-running）">
            <Input data-testid="quota-max-running" value={quota['quota.max-running'] ?? ''}
                   placeholder="留空 = 网关默认"
                   onChange={(e) => setQuota({ ...quota, 'quota.max-running': e.target.value })} />
          </Form.Item>
          <Form.Item label="单日执行上限（quota.daily-limit）">
            <Input data-testid="quota-daily" value={quota['quota.daily-limit'] ?? ''}
                   placeholder="留空 = 网关默认"
                   onChange={(e) => setQuota({ ...quota, 'quota.daily-limit': e.target.value })} />
          </Form.Item>
          <Button type="primary" data-testid="quota-save" onClick={saveQuota}>保存配额</Button>
        </Form>
      </Card>
    </div>
  )
}
