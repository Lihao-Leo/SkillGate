import { Navigate, Outlet, useNavigate } from 'react-router-dom'
import { authLoggedIn, logoutSession, useAuthStore } from '../stores/auth'

/** 充值平台布局壳：顶部导航 + 内容（maxWidth 1120 居中）。 */
export default function PortalLayout() {
  const navigate = useNavigate()
  const loggedIn = authLoggedIn(useAuthStore.getState())
  const identifier = useAuthStore.getState().identifier

  const MENU = [
    { path: '/dashboard', label: '工作台' },
    { path: '/recharge', label: '充值' },
    { path: '/keys', label: 'APIKey' },
    { path: '/transactions', label: '流水' },
    { path: '/task', label: '任务查询' },
    { path: '/docs', label: '文档' },
  ]

  return (
    <div style={{ minHeight: '100vh' }}>
      <header className="portal-nav">
        <div className="portal-nav-inner">
          <span className="portal-brand">
            <span className="portal-mark">S</span>
            <b>SkillGate</b>
            <span className="portal-sub">用户中心</span>
          </span>
          <nav className="portal-menu">
            {MENU.map((item) => (
              <a key={item.path} className="portal-menu-item" href={item.path}
                 onClick={(e) => { e.preventDefault(); navigate(item.path) }}>
                {item.label}
              </a>
            ))}
          </nav>
          <span className="portal-user" data-testid="current-user">
            {loggedIn ? (
              <>
                <span>{identifier}</span>
                <button className="portal-logout" onClick={() => {
                  logoutSession()
                  navigate('/login')
                }}>退出</button>
              </>
            ) : (
              <a href="/login" onClick={(e) => { e.preventDefault(); navigate('/login') }}>
                登录 / 注册
              </a>
            )}
          </span>
        </div>
      </header>
      <main className="portal-content">
        <Outlet />
      </main>
    </div>
  )
}

/** 登录守卫：login / docs 公开，其余要求已登录（对齐旧 router 白名单）。 */
export function RequireLogin({ children }: { children: React.ReactNode }) {
  const loggedIn = authLoggedIn(useAuthStore.getState())
  if (!loggedIn) {
    return <Navigate to="/login" replace />
  }
  return children
}
