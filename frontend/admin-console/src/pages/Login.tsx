import { useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { Alert, Button, Form, Input } from 'antd'
import { ClusterOutlined, LockOutlined, UserOutlined } from '@ant-design/icons'
import { gatewayApi } from '../api/gateway'
import { ApiError } from '../api/client'
import { saveSession, useAuthStore } from '../stores/auth'
import { appKeyReady, useCredentialsStore } from '../stores/credentials'

/** 后台登录（BCrypt 校验 → JWT + 会话画像）。已登录直接回执行监控。 */
export default function LoginPage() {
  const navigate = useNavigate()
  const loggedIn = useAuthStore((state) => state.session !== null)
  const appKeyReadyNow = appKeyReady(useCredentialsStore.getState())
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  if (loggedIn) {
    return <Navigate to="/executions" replace />
  }

  async function submit(values: { username: string; password: string }) {
    setError('')
    setLoading(true)
    try {
      const result = await gatewayApi.adminLogin(values.username.trim(), values.password)
      saveSession({
        token: result.token,
        username: result.username,
        displayName: result.displayName ?? result.username,
        role: result.role,
        roleName: result.roleName,
        permissions: result.permissions,
      })
      navigate(appKeyReadyNow ? '/executions' : '/connect')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '登录失败，请稍后重试')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div
      style={{
        minHeight: '100vh', display: 'grid', placeItems: 'center',
        background:
          'radial-gradient(1200px 600px at 20% -10%, rgba(43,127,255,0.12), transparent),'
          + ' radial-gradient(1000px 500px at 110% 110%, rgba(18,183,106,0.10), transparent)',
      }}
    >
      <div
        style={{
          width: 380, background: 'var(--ant-color-bg-container, #fff)',
          border: '1px solid var(--ant-color-border-secondary, #eef0f4)',
          borderRadius: 18, padding: '34px 32px 30px',
          boxShadow: '0 18px 50px rgba(31,36,48,0.10)',
        }}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 22 }}>
          <span
            style={{
              width: 38, height: 38, borderRadius: 11,
              background: 'var(--brand-gradient)', color: '#fff',
              display: 'grid', placeItems: 'center', fontSize: 18,
              boxShadow: '0 6px 14px rgba(43,127,255,0.3)',
            }}
          >
            <ClusterOutlined />
          </span>
          <b style={{ fontSize: 18 }}>SkillGate 管理后台</b>
          <span style={{ color: 'var(--ant-color-text-tertiary)', fontSize: 12.5 }}>
            Skill 统一接入与执行平台
          </span>
        </div>
        <Form layout="vertical" onFinish={submit}>
          <Form.Item label="用户名" name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input data-testid="login-username" prefix={<UserOutlined />} placeholder="登录名"
                   autoComplete="username" />
          </Form.Item>
          <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password data-testid="login-password" prefix={<LockOutlined />} placeholder="密码"
                            autoComplete="current-password" />
          </Form.Item>
          {error && (
            <Alert type="error" showIcon message={error}
                   data-testid="login-error" style={{ marginBottom: 12 }} />
          )}
          <Button type="primary" htmlType="submit" block data-testid="login-btn"
                  loading={loading}>
            登 录
          </Button>
        </Form>
      </div>
    </div>
  )
}
