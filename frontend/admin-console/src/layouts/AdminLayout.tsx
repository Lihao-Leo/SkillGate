import { useMemo } from 'react'
import { Link, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { ProLayout } from '@ant-design/pro-components'
import type { ProLayoutProps } from '@ant-design/pro-components'
import {
  AlertOutlined, ApiOutlined, AppstoreOutlined, BellOutlined, DatabaseOutlined,
  FundViewOutlined, KeyOutlined, LogoutOutlined, MoonOutlined, PieChartOutlined,
  SettingOutlined, SunOutlined, UserOutlined, WalletOutlined,
} from '@ant-design/icons'
import { Dropdown } from 'antd'
import { clearSession, hasPerm, useAuthStore } from '../stores/auth'
import { clearCredentials, useCredentialsStore } from '../stores/credentials'
import { toggleTheme, useUiStore } from '../stores/ui'

/** 菜单定义（与旧版 menuGroups 同构；perm 缺省 = 登录即可见）。 */
interface MenuItem { path: string; name: string; icon: React.ReactNode; perm?: string }
interface MenuGroup { title: string; items: MenuItem[] }

const MENU_GROUPS: MenuGroup[] = [
  {
    title: '运营',
    items: [
      { path: '/executions', name: '执行监控', icon: <FundViewOutlined />, perm: 'execution:read' },
      { path: '/skills', name: 'Skill 管理', icon: <AppstoreOutlined />, perm: 'skill:read' },
    ],
  },
  {
    title: '资产',
    items: [
      { path: '/app-keys', name: 'AppKey 与账户', icon: <KeyOutlined />, perm: 'key:read' },
      { path: '/account', name: '账户流水', icon: <WalletOutlined /> },
    ],
  },
  {
    title: '平台',
    items: [
      { path: '/consumption', name: '消耗看板', icon: <PieChartOutlined />, perm: 'consumption:read' },
      { path: '/models', name: '模型与能力', icon: <ApiOutlined />, perm: 'model:read' },
      { path: '/kb', name: '知识库', icon: <DatabaseOutlined />, perm: 'kb:manage' },
      { path: '/alerts', name: '告警中心', icon: <AlertOutlined />, perm: 'alert:read' },
      { path: '/sysconfig', name: '系统配置', icon: <SettingOutlined />, perm: 'sysconfig:read' },
    ],
  },
  {
    title: '系统',
    items: [
      { path: '/users', name: '用户与角色', icon: <UserOutlined />, perm: 'user:manage' },
      { path: '/connect', name: '业务凭证', icon: <SettingOutlined /> },
    ],
  },
]

/** ProLayout 路由数据：按 hasPerm 过滤后的可见菜单（SUPER_ADMIN 通配）。 */
function useVisibleRouteData(): ProLayoutProps['route'] {
  const session = useAuthStore((state) => state.session)
  return useMemo(() => {
    const children = MENU_GROUPS.map((group) => ({
      path: `group-${group.title}`,
      name: group.title,
      icon: null,
      children: group.items
        .filter((item) => !item.perm || hasPerm(session, item.perm))
        .map((item) => ({ path: item.path, name: item.name, icon: item.icon })),
    })).filter((group) => (group.children ?? []).length > 0)
    return { path: '/', routes: children }
  }, [session])
}

export default function AdminLayout() {
  const location = useLocation()
  const navigate = useNavigate()
  const dark = useUiStore((state) => state.dark)
  const session = useAuthStore((state) => state.session)
  const appKey = useCredentialsStore((state) => state.appKey)

  const routeData = useVisibleRouteData()

  function logout() {
    clearSession()
    clearCredentials()
    navigate('/login')
  }

  if (!session) {
    return <Navigate to="/login" replace />
  }

  return (
    <ProLayout
      layout="mix"
      fixedHeader
      siderWidth={190}
      title="SkillGate"
      logo={
        <div
          style={{
            width: 30, height: 30, borderRadius: 9,
            background: 'var(--brand-gradient)', color: '#fff',
            display: 'grid', placeItems: 'center', fontWeight: 700, fontSize: 15,
            boxShadow: '0 4px 10px rgba(43,127,255,0.3)',
          }}
        >
          S
        </div>
      }
      route={routeData}
      location={{ pathname: location.pathname }}
      menuItemRender={(item, dom) => (item.path ? <Link to={item.path}>{dom}</Link> : dom)}
      headerContentRender={() => (
        <span style={{ color: 'var(--ant-color-text-tertiary)', fontSize: 13 }}>
          Skill 统一接入与执行平台 · 管理后台
          <span
            style={{
              marginLeft: 10, background: 'var(--ant-color-success-bg, #e8f8ee)',
              color: 'var(--ant-color-success, #12b76a)', fontSize: 12,
              borderRadius: 6, padding: '2px 8px', fontWeight: 600,
            }}
          >
            生产环境
          </span>
        </span>
      )}
      avatarProps={{
        icon: <UserOutlined />,
        style: { backgroundColor: 'var(--brand-gradient)' },
        render: (_props, dom) => (
          <Dropdown
            menu={{
              items: [
                {
                  key: 'profile',
                  label: `${session.displayName} · ${session.roleName}`,
                  disabled: true,
                },
                { type: 'divider' },
                { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: logout },
              ],
            }}
          >
            {dom}
          </Dropdown>
        ),
      }}
      actionsRender={() => [
        <span
          key="theme"
          data-testid="theme-toggle"
          onClick={toggleTheme}
          style={{ cursor: 'pointer', fontSize: 16, color: 'var(--ant-color-text-tertiary)' }}
        >
          {dark ? <SunOutlined /> : <MoonOutlined />}
        </span>,
        <FundViewOutlined
          key="wall"
          data-testid="wall-link"
          onClick={() => navigate('/wall')}
          style={{ cursor: 'pointer', fontSize: 16, color: 'var(--ant-color-text-tertiary)' }}
        />,
        <span key="bell" style={{ position: 'relative', display: 'inline-flex' }}>
          <BellOutlined style={{ fontSize: 16, color: 'var(--ant-color-text-tertiary)' }} />
          <span
            style={{
              position: 'absolute', top: -2, right: -3, width: 7, height: 7,
              borderRadius: '50%', background: '#f04438',
            }}
          />
        </span>,
      ]}
      token={{ bgLayout: '#f5f6fa' }}
    >
      <Outlet />
    </ProLayout>
  )
}
