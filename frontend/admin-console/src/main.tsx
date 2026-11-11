import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App as AntdApp, ConfigProvider, theme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { RouterProvider } from 'react-router-dom'
import { router } from './router'
import { useUiStore } from './stores/ui'
import './styles.css'

/** ConfigProvider 桥：暗色算法随 ui store 响应切换（sg-theme 键延续 Vue 版偏好）。 */
function ThemeBridge({ children }: { children: React.ReactNode }) {
  const dark = useUiStore((state) => state.dark)
  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        cssVar: true,
        algorithm: dark ? theme.darkAlgorithm : theme.defaultAlgorithm,
        token: { colorPrimary: '#2b7fff', colorInfo: '#2b7fff', borderRadius: 8 },
      }}
    >
      {children}
    </ConfigProvider>
  )
}

createRoot(document.getElementById('app')!).render(
  <StrictMode>
    <ThemeBridge>
      <AntdApp>
        <RouterProvider router={router} />
      </AntdApp>
    </ThemeBridge>
  </StrictMode>,
)
