import { useRef, useState } from 'react'
import { App as AntdApp, Input, Modal } from 'antd'

/**
 * ElMessageBox.prompt 等价物（React 版）：`usePrompt()` 返回 [contextHolder, prompt]。
 * prompt(title, placeholder) → 用户确认返回输入值；取消返回 null。
 * 消费方：AlertsView 认领备注 / SkillsView 驳回原因。
 */
export function usePrompt(): [
  React.ReactNode,
  (title: string, placeholder?: string) => Promise<string | null>,
] {
  const { modal } = AntdApp.useApp()
  const [contextHolder] = useState<React.ReactNode>(null)
  const valueRef = useRef('')

  function prompt(title: string, placeholder = ''): Promise<string | null> {
    valueRef.current = ''
    return new Promise((resolve) => {
      modal.confirm({
        title,
        icon: null,
        content: (
          <Input
            placeholder={placeholder}
            onChange={(e) => { valueRef.current = e.target.value }}
          />
        ),
        okText: '确定',
        cancelText: '取消',
        onOk: () => resolve(valueRef.current.trim()),
        onCancel: () => resolve(null),
      })
    })
  }

  return [contextHolder, prompt]
}
