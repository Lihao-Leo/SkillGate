import { createBrowserRouter, Navigate } from 'react-router-dom'
import PortalLayout, { RequireLogin } from './layouts/PortalLayout'
import LoginPage from './pages/Login'
import DashboardPage from './pages/Dashboard'
import RechargePage from './pages/Recharge'
import KeysPage from './pages/Keys'
import TaskQueryPage from './pages/TaskQuery'
import TransactionsPage from './pages/Transactions'
import DocsPage from './pages/Docs'

export const router = createBrowserRouter([
  {
    element: <PortalLayout />,
    children: [
      { path: '/', element: <Navigate to="/dashboard" replace /> },
      { path: '/login', element: <LoginPage /> },
      {
        path: '/dashboard',
        element: <RequireLogin><DashboardPage /></RequireLogin>,
      },
      {
        path: '/recharge',
        element: <RequireLogin><RechargePage /></RequireLogin>,
      },
      {
        path: '/keys',
        element: <RequireLogin><KeysPage /></RequireLogin>,
      },
      {
        path: '/transactions',
        element: <RequireLogin><TransactionsPage /></RequireLogin>,
      },
      {
        path: '/task',
        element: <RequireLogin><TaskQueryPage /></RequireLogin>,
      },
      // 文档公开（守卫白名单例外）
      { path: '/docs', element: <DocsPage /> },
      { path: '*', element: <Navigate to="/dashboard" replace /> },
    ],
  },
])
