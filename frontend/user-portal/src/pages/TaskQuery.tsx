import { useState } from 'react'
import { Alert, App as AntdApp, Button, Card, Descriptions, Input, Progress, Tag } from 'antd'
import { gatewayUserApi } from '../api'
import type { ExecutionView } from '../api'

const STATUS_COLOR: Record<string, string> = {
  PENDING: 'default', RUNNING: 'processing', CANCELLING: 'warning',
  SUCCEEDED: 'success', FAILED: 'error', CANCELLED: 'default',
}

/** 按 taskId 查执行状态与产物（gateway HMAC）。 */
export default function TaskQueryPage() {
  const { message } = AntdApp.useApp()
  const [taskId, setTaskId] = useState('')
  const [view, setView] = useState<ExecutionView | null>(null)
  const [loading, setLoading] = useState(false)

  async function query() {
    if (!taskId.trim()) {
      message.warning('请输入 taskId')
      return
    }
    setLoading(true)
    try {
      setView(await gatewayUserApi.execution(taskId.trim()))
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div>
      <div className="crumb">执行 / 任务查询</div>
      <h1 className="title">任务查询</h1>
      <p className="subtitle">按 taskId 查询执行状态、扣点与产物（产物下载链接 24h 有效）</p>

      <Card>
        <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
          <Input data-testid="taskid-input" placeholder="task_..." className="mono"
                 value={taskId} onChange={(e) => setTaskId(e.target.value)}
                 onPressEnter={query} />
          <Button type="primary" data-testid="query-btn" loading={loading} onClick={query}>
            查询
          </Button>
        </div>

        {view && (
          <div data-testid="execution-detail">
            <Descriptions bordered size="small" column={3}>
              <Descriptions.Item label="taskId" span={3}>
                <span className="mono">{view.taskId}</span>
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={STATUS_COLOR[view.status] ?? 'default'} style={{ margin: 0 }}>
                  {view.status}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="进度">
                {view.progress != null
                  ? <Progress percent={view.progress} size="small" strokeWidth={7} />
                  : '—'}
              </Descriptions.Item>
              <Descriptions.Item label="Skill">{view.skillCode ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="版本">{view.resolvedVersion ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="扣点">
                {view.billing?.pointsCharged ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label="错误" span={3}>
                {view.error ? `${view.error.code}: ${view.error.message}` : '—'}
              </Descriptions.Item>
            </Descriptions>

            {view.artifacts && view.artifacts.length > 0 && (
              <div style={{ marginTop: 14 }} data-testid="artifacts">
                <b>产物</b>
                <ul>
                  {view.artifacts.map((a) => (
                    <li key={a.artifactId}>
                      <a href={a.url} target="_blank" rel="noopener noreferrer"
                         className="mono">{a.artifactId}</a>
                      <span style={{ color: 'var(--ant-color-text-tertiary)', marginLeft: 8 }}>
                        {a.type} · {a.sizeBytes ?? '-'} B
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}
      </Card>
    </div>
  )
}
