import { useRef, useState } from 'react'
import { App as AntdApp, Form, Input, Modal, Select } from 'antd'

/**
 * 转工单弹窗（Alerts/Executions 共用）：预填来源字段，severity/title/description 可编辑。
 * resolve(ticketNo) 成功返回工单号；取消 resolve(null)。
 */
export interface CreateIssueTarget {
  type: string
  sourceId?: string
  relatedTaskId?: string
  tenantId?: string
  skillCode?: string
  title: string
  description?: string
  severity?: string
}

export function useCreateIssue(): [
  React.ReactNode,
  (target: CreateIssueTarget) => Promise<string | null>,
] {
  const { message } = AntdApp.useApp()
  const [contextHolder] = useState<React.ReactNode>(null)
  const valuesRef = useRef<Record<string, string>>({})
  const targetRef = useRef<CreateIssueTarget | null>(null)

  function createIssue(target: CreateIssueTarget): Promise<string | null> {
    targetRef.current = target
    valuesRef.current = {
      title: target.title,
      description: target.description ?? '',
      severity: target.severity ?? 'WARN',
      assignee: '',
    }
    return new Promise((resolve) => {
      Modal.confirm({
        title: '转为问题工单',
        width: 520,
        content: (
          <Form layout="vertical" style={{ marginTop: 12 }}>
            <Form.Item label="标题">
              <Input onChange={(e) => { valuesRef.current.title = e.target.value }}
                     defaultValue={target.title} />
            </Form.Item>
            <Form.Item label="级别">
              <Select defaultValue={target.severity ?? 'WARN'}
                      onChange={(v) => { valuesRef.current.severity = v }}
                      options={[
                        { value: 'INFO', label: 'INFO' },
                        { value: 'WARN', label: 'WARN' },
                        { value: 'CRITICAL', label: 'CRITICAL' },
                      ]} />
            </Form.Item>
            <Form.Item label="描述">
              <Input.TextArea rows={3} defaultValue={target.description ?? ''}
                              onChange={(e) => { valuesRef.current.description = e.target.value }} />
            </Form.Item>
            <Form.Item label="负责人（可空）">
              <Input onChange={(e) => { valuesRef.current.assignee = e.target.value }} />
            </Form.Item>
          </Form>
        ),
        okText: '创建工单',
        cancelText: '取消',
        onOk: async () => {
          try {
            const { gatewayApi } = await import('../api/gateway')
            const result = await gatewayApi.adminCreateIssue({
              title: valuesRef.current.title || target.title,
              type: target.type,
              severity: valuesRef.current.severity,
              sourceId: target.sourceId,
              relatedTaskId: target.relatedTaskId,
              tenantId: target.tenantId,
              skillCode: target.skillCode,
              description: valuesRef.current.description,
              assignee: valuesRef.current.assignee || undefined,
            })
            const issue = result.issue as { ticketNo: string }
            if (result.alreadyExists) {
              message.info(`该来源已有工单：${issue.ticketNo}`)
            } else {
              message.success(`工单已创建：${issue.ticketNo}`)
            }
            resolve(issue.ticketNo)
          } catch (e) {
            message.error((e as Error).message)
            resolve(null)
          }
        },
        onCancel: () => resolve(null),
      })
    })
  }

  return [contextHolder, createIssue]
}
