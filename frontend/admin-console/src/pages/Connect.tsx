import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, App as AntdApp, Button, Card, Form, Input } from 'antd'
import { ClusterOutlined } from '@ant-design/icons'
import { gatewayApi } from '../api/gateway'
import { saveCredentials, useCredentialsStore } from '../stores/credentials'

type ConnectForm = { appKey: string; secret: string; adminToken: string }

/** 业务凭证绑定（登录后）：业务面 HMAC AppKey/Secret + 管理面 legacy AdminToken（过渡兼容）。 */
export default function ConnectPage() {
  const navigate = useNavigate()
  const { message } = AntdApp.useApp()
  const current = useCredentialsStore.getState()
  const [form] = Form.useForm<ConnectForm>()
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState('')
  const [issuing, setIssuing] = useState(false)

  function currentValues(): ConnectForm {
    return form.getFieldsValue() as ConnectForm
  }

  /** 双通道连通性校验：业务面（HMAC 调 balance）+ 管理面（token 调 key 列表） */
  async function verify() {
    setTesting(true)
    setTestResult('')
    const values = currentValues()
    saveCredentials({
      appKey: values.appKey ?? '',
      secret: values.secret ?? '',
      adminToken: values.adminToken ?? '',
    })
    const results: string[] = []
    try {
      const balance = await gatewayApi.balance()
      results.push(`业务面 OK（余额 ${balance.balance} / 冻结 ${balance.frozen}）`)
    } catch (e) {
      results.push(`业务面失败：${(e as Error).message}`)
    }
    if (values.adminToken) {
      try {
        const page = await gatewayApi.adminListKeys(undefined, 1, 1)
        results.push(`管理面 OK（共 ${page.total} 把 key）`)
      } catch (e) {
        results.push(`管理面失败：${(e as Error).message}`)
      }
    } else {
      results.push('管理面未配置 AdminToken（跳过）')
    }
    setTestResult(results.join('；'))
    setTesting(false)
  }

  /** 冷启动自助签发：仅有 AdminToken 时即可经管理面签发一把新 key（Secret 仅此一次返回） */
  async function issueKey() {
    const values = currentValues()
    if (!values.adminToken) {
      message.warning('签发需先填 AdminToken（管理面鉴权）')
      return
    }
    saveCredentials({
      appKey: values.appKey ?? '',
      secret: values.secret ?? '',
      adminToken: values.adminToken,
    })
    setIssuing(true)
    try {
      const issued = await gatewayApi.adminIssueKey('admin-console')
      form.setFieldsValue({ appKey: issued.appKeyId, secret: issued.appSecret })
      message.success('已签发并填入；Secret 仅此一次返回，请立即点「保存并进入」')
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setIssuing(false)
    }
  }

  function save() {
    const values = currentValues()
    if (!values.appKey || !values.secret) {
      message.warning('AppKey 与 AppSecret 必填（业务面 HMAC 签名）')
      return
    }
    saveCredentials({
      appKey: values.appKey,
      secret: values.secret,
      adminToken: values.adminToken ?? '',
    })
    message.success('凭证已保存')
    navigate('/skills')
  }

  return (
    <div style={{ maxWidth: 520, margin: '32px auto 0' }}>
      <div style={{ textAlign: 'center', marginBottom: 20 }}>
        <span
          style={{
            width: 52, height: 52, borderRadius: 14, margin: '0 auto 12px',
            background: 'var(--brand-gradient)', color: '#fff',
            display: 'grid', placeItems: 'center', fontSize: 22,
            boxShadow: '0 8px 24px rgba(59,91,245,0.35)',
          }}
        >
          <ClusterOutlined />
        </span>
        <h2 style={{ margin: '0 0 4px' }}>Skill 平台管理后台</h2>
        <p style={{ margin: 0, color: 'var(--ant-color-text-tertiary)', fontSize: 13 }}>
          录入运营凭证以接入执行平台
        </p>
      </div>
      <Card>
        <Form
          form={form}
          labelCol={{ style: { width: 110 } }}
          initialValues={{
            appKey: current.appKey,
            secret: current.secret,
            adminToken: current.adminToken,
          }}
        >
          <Form.Item label="AppKey" required name="appKey">
            <Input data-testid="appkey-input" placeholder="sk-..." />
          </Form.Item>
          <Form.Item label="AppSecret" required name="secret">
            <Input.Password data-testid="secret-input"
                            placeholder="签发时一次性展示的完整 Secret" />
          </Form.Item>
          <Form.Item label="AdminToken" name="adminToken">
            <Input.Password data-testid="admintoken-input"
                            placeholder="管理面 /api/v1/admin/**（AppKey 管理/入账）" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" data-testid="save-btn" onClick={save}>保存并进入</Button>
            <Button data-testid="verify-btn" loading={testing} onClick={verify}
                    style={{ marginLeft: 8 }}>连通性校验</Button>
            <Button data-testid="issue-key-btn" loading={issuing} onClick={issueKey}
                    style={{ marginLeft: 8 }}>签发新 Key 并填入</Button>
          </Form.Item>
        </Form>
        {testResult && <Alert type="info" showIcon message={testResult} data-testid="verify-result" />}
        <p style={{
          color: 'var(--ant-color-text-tertiary)', fontSize: 12,
          margin: '12px 0 0', lineHeight: 1.6,
        }}>
          业务面（Skill/执行/账户）用 AppKey+Secret 做 HMAC 请求签名；管理面（AppKey 管理/入账）用
          AdminToken，两者独立。没有 AppKey 时：填好 AdminToken 后点「签发新 Key
          并填入」即可自助签发。
        </p>
      </Card>
    </div>
  )
}
