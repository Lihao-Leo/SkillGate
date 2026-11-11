import { useEffect, useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { Alert, App as AntdApp, Button, Input } from 'antd'
import { rechargeApi } from '../api'
import { ApiError } from '../api'
import { authLoggedIn, loginSession, useAuthStore } from '../stores/auth'

/** 验证码登录/注册一体（未注册自动建号）；dev 环境回显验证码。已登录直接回工作台。 */
export default function LoginPage() {
  const navigate = useNavigate()
  const { message } = AntdApp.useApp()
  const loggedIn = authLoggedIn(useAuthStore.getState())
  const [identifier, setIdentifier] = useState('')
  const [code, setCode] = useState('')
  const [sending, setSending] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [echoCode, setEchoCode] = useState('')
  const [countdown, setCountdown] = useState(0)

  // 60s 倒计时（useEffect cleanup，StrictMode 安全；修复旧版 interval 无清理）
  useEffect(() => {
    if (countdown <= 0) return
    const timer = setInterval(() => setCountdown((prev) => Math.max(0, prev - 1)), 1000)
    return () => clearInterval(timer)
  }, [countdown > 0])

  if (loggedIn) {
    return <Navigate to="/dashboard" replace />
  }

  async function sendCode() {
    if (!identifier.trim()) {
      message.warning('请输入手机号或邮箱')
      return
    }
    setSending(true)
    try {
      const result = await rechargeApi.sendCode(identifier.trim())
      setCountdown(60)
      if (result.devEchoCode) {
        setEchoCode(result.devEchoCode)
        message.info(`联调环境验证码回显：${result.devEchoCode}`)
      } else {
        message.success(`验证码已发送（${result.expiresInMinutes} 分钟内有效）`)
      }
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : '发送失败，请稍后重试')
    } finally {
      setSending(false)
    }
  }

  async function submit() {
    if (!identifier.trim() || !code.trim()) {
      message.warning('请输入手机号/邮箱与验证码')
      return
    }
    setSubmitting(true)
    try {
      const result = await rechargeApi.login(identifier.trim(), code.trim())
      loginSession(result.token, result.identifier)
      navigate('/dashboard')
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : '登录失败，请稍后重试')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="login-wrap">
      <div className="login-card">
        <div className="login-brand">
          <span className="portal-mark">S</span>
          <b>SkillGate 用户中心</b>
          <span className="login-sub">点数充值 · APIKey 管理 · 执行查询</span>
        </div>
        <div className="login-field">
          <label>手机号 / 邮箱</label>
          <Input data-testid="identifier-input" value={identifier}
                 placeholder="用于接收验证码与账号标识"
                 onChange={(e) => setIdentifier(e.target.value)} />
        </div>
        <div className="login-field">
          <label>验证码</label>
          <div style={{ display: 'flex', gap: 8 }}>
            <Input data-testid="code-input" value={code} placeholder="6 位验证码"
                   maxLength={6}
                   onChange={(e) => setCode(e.target.value)}
                   onPressEnter={submit} />
            <Button data-testid="send-code-btn" disabled={countdown > 0 || sending}
                    loading={sending} onClick={sendCode}>
              {countdown > 0 ? `${countdown}s` : '发送验证码'}
            </Button>
          </div>
        </div>
        {echoCode && (
          <Alert type="info" showIcon data-testid="dev-echo-code"
                 message={`联调环境验证码回显：${echoCode}`} style={{ marginBottom: 12 }} />
        )}
        <Button type="primary" block data-testid="login-btn" loading={submitting}
                onClick={submit}>登录 / 注册</Button>
        <p className="login-tip">未注册的账号将在首次登录时自动创建</p>
      </div>
    </div>
  )
}
