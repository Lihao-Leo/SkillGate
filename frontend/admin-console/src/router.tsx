import { createBrowserRouter, Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { hasPerm, useAuthStore } from './stores/auth'
import { appKeyReady, useCredentialsStore } from './stores/credentials'
import AdminLayout from './layouts/AdminLayout'
import LoginPage from './pages/Login'
import ConnectPage from './pages/Connect'
import SkillsPage from './pages/Skills'
import AppKeysPage from './pages/AppKeys'
import ExecutionsPage from './pages/Executions'
import WallPage from './pages/Wall'
import AccountPage from './pages/Account'
import ModelProvidersPage from './pages/ModelProviders'
import KbPage from './pages/Kb'
import AlertsPage from './pages/Alerts'
import SysconfigPage from './pages/Sysconfig'
import UsersPage from './pages/Users'

/** 未登录 → /login（对齐 vue-router 守卫第 1 条）。 */
function RequireAuth({ children }: { children: ReactNode }) {
  const loggedIn = useAuthStore((state) => state.session !== null)
  if (!loggedIn) {
    return <Navigate to="/login" replace />
  }
  return children
}

/** 权限点不满足 → 静默回跳 /executions（对齐拍板行为，无 403 页）。 */
function RequirePerm({ perm, children }: { perm: string; children: ReactNode }) {
  const session = useAuthStore((state) => state.session)
  if (!hasPerm(session, perm)) {
    return <Navigate to="/executions" replace />
  }
  return children
}

/** 业务面凭证缺失 → /connect（仅 /account 使用）。 */
function RequireAppKey({ children }: { children: ReactNode }) {
  const appKey = useCredentialsStore((state) => state.appKey)
  const secret = useCredentialsStore((state) => state.secret)
  if (!appKeyReady({ appKey, secret })) {
    return <Navigate to="/connect" replace />
  }
  return children
}

/** 已登录访问 /login → /executions（对齐守卫第 2 条）；组件内自行处理。 */

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    element: (
      <RequireAuth>
        <AdminLayout />
      </RequireAuth>
    ),
    children: [
      { path: '/', element: <Navigate to="/executions" replace /> },
      { path: '/connect', element: <ConnectPage /> },
      {
        path: '/skills',
        element: (
          <RequirePerm perm="skill:read">
            <SkillsPage />
          </RequirePerm>
        ),
      },
      {
        path: '/app-keys',
        element: (
          <RequirePerm perm="key:read">
            <AppKeysPage />
          </RequirePerm>
        ),
      },
      {
        path: '/executions',
        element: (
          <RequirePerm perm="execution:read">
            <ExecutionsPage />
          </RequirePerm>
        ),
      },
      {
        path: '/account',
        element: (
          <RequireAppKey>
            <AccountPage />
          </RequireAppKey>
        ),
      },
      {
        path: '/models',
        element: (
          <RequirePerm perm="model:read">
            <ModelProvidersPage />
          </RequirePerm>
        ),
      },
      {
        path: '/kb',
        element: (
          <RequirePerm perm="kb:manage">
            <KbPage />
          </RequirePerm>
        ),
      },
      {
        path: '/alerts',
        element: (
          <RequirePerm perm="alert:read">
            <AlertsPage />
          </RequirePerm>
        ),
      },
      {
        path: '/sysconfig',
        element: (
          <RequirePerm perm="sysconfig:read">
            <SysconfigPage />
          </RequirePerm>
        ),
      },
      {
        path: '/users',
        element: (
          <RequirePerm perm="user:manage">
            <UsersPage />
          </RequirePerm>
        ),
      },
      { path: '*', element: <Navigate to="/executions" replace /> },
    ],
  },
  // 大屏独立路由：不带 ProLayout，恒深色
  {
    path: '/wall',
    element: (
      <RequireAuth>
        <RequirePerm perm="metrics:read">
          <WallPage />
        </RequirePerm>
      </RequireAuth>
    ),
  },
])

/** 面包屑用（ProLayout 需要 location）。 */
export function usePathname(): string {
  return useLocation().pathname
}
