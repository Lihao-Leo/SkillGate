import { useEffect, useState } from 'react'
import { App as AntdApp, Button, Card, Input, Select, Space, Table, Tabs, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { gatewayApi } from '../api/gateway'
import type { ExecutionEventSearchView, SkillIoLogView } from '../api/gateway'

const STAGES = ['ACCEPT', 'AUTH', 'RATE_LIMIT', 'FREEZE', 'ENQUEUE', 'CLAIM', 'STAGE',
  'SANDBOX', 'COLLECT', 'ARTIFACT_PUT', 'SETTLE', 'FINALIZE', 'CALLBACK', 'WATCHDOG',
  'KB_RETRIEVE']

/** 日志查询（V2.3）双源：FREE 分发埋点 + 任务事件链（Tab 切换）。 */
export default function LogsPage() {
  const { message } = AntdApp.useApp()

  // ---- 分发埋点 ----
  const [ioRows, setIoRows] = useState<SkillIoLogView[]>([])
  const [ioLoading, setIoLoading] = useState(false)
  const [ioPage, setIoPage] = useState({ pageNo: 1, pageSize: 20, total: 0 })
  const [ioFilter, setIoFilter] = useState({ skillCode: '', status: '' })

  async function loadIo(pageNo = 1, pageSize = 20) {
    setIoLoading(true)
    try {
      const page = await gatewayApi.adminListIoLogs({
        skillCode: ioFilter.skillCode.trim() || undefined,
        status: ioFilter.status || undefined,
        pageNo, pageSize,
      })
      setIoRows(page.records)
      setIoPage({ pageNo, pageSize, total: page.total })
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setIoLoading(false)
    }
  }

  // ---- 任务事件 ----
  const [eventRows, setEventRows] = useState<ExecutionEventSearchView[]>([])
  const [eventLoading, setEventLoading] = useState(false)
  const [eventFilter, setEventFilter] = useState({ taskId: '', stage: '', status: '' })
  const [eventSearched, setEventSearched] = useState(false)

  async function loadEvents() {
    setEventLoading(true)
    try {
      const page = await gatewayApi.adminSearchExecutionEvents({
        taskId: eventFilter.taskId.trim() || undefined,
        stage: eventFilter.stage || undefined,
        status: eventFilter.status || undefined,
      })
      setEventRows(page.records)
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setEventLoading(false)
    }
  }

  const ioColumns: ColumnsType<SkillIoLogView> = [
    { title: 'Skill', dataIndex: 'skillCode', width: 160, className: 'mono' },
    { title: '版本', dataIndex: 'version', width: 80 },
    {
      title: '状态', width: 110,
      render: (_, row) => (
        <Tag color={row.status === 'SUCCEEDED' ? 'success' : 'error'}>{row.status}</Tag>
      ),
    },
    { title: '耗时', dataIndex: 'durationMs', width: 100, align: 'right',
      render: (v: number | null) => v == null ? '—' : `${v}ms` },
    { title: '调用方', dataIndex: 'callerHint', width: 140 },
    { title: '时间', dataIndex: 'createdAt', width: 170 },
  ]

  const eventColumns: ColumnsType<ExecutionEventSearchView> = [
    { title: 'taskId', dataIndex: 'taskId', width: 220, className: 'mono' },
    { title: 'stage', dataIndex: 'stage', width: 110, className: 'mono' },
    { title: 'service', dataIndex: 'service', width: 90 },
    {
      title: '状态', width: 90,
      render: (_, row) => (
        <Tag color={row.status === 'OK' ? 'success' : row.status === 'FAILED' ? 'error' : 'default'}>
          {row.status}
        </Tag>
      ),
    },
    { title: '耗时', dataIndex: 'durationMs', width: 90, align: 'right',
      render: (v: number | null) => v == null ? '—' : `${v}ms` },
    { title: '时间', dataIndex: 'occurredAt', width: 180 },
    { title: 'traceId', dataIndex: 'traceId', width: 160, className: 'mono' },
  ]

  return (
    <div>
      <div className="page-header">
        <div>
          <h2>日志查询</h2>
          <p className="sub">跨运行检索：FREE 分发埋点 + 任务事件链（事件检索需 taskId 或 ≤7 天时间窗）</p>
        </div>
      </div>

      <Card>
        <Tabs
          items={[
            {
              key: 'io',
              label: '分发埋点日志',
              children: (
                <>
                  <Space style={{ marginBottom: 12 }} wrap>
                    <Input placeholder="Skill 编码" allowClear style={{ width: 180 }}
                           value={ioFilter.skillCode}
                           onChange={(e) => setIoFilter({ ...ioFilter, skillCode: e.target.value })}
                           onPressEnter={() => loadIo()} />
                    <Select value={ioFilter.status || undefined} placeholder="状态" allowClear
                            style={{ width: 140 }}
                            onChange={(v) => { setIoFilter({ ...ioFilter, status: v ?? '' }); loadIo(1) }}
                            options={[
                              { value: 'SUCCEEDED', label: 'SUCCEEDED' },
                              { value: 'FAILED', label: 'FAILED' },
                            ]} />
                    <Button onClick={() => loadIo()}>查询</Button>
                  </Space>
                  <Table
                    size="small"
                    rowKey="id"
                    columns={ioColumns}
                    dataSource={ioRows}
                    loading={ioLoading}
                    pagination={{
                      current: ioPage.pageNo, pageSize: ioPage.pageSize, total: ioPage.total,
                      showSizeChanger: false,
                      onChange: (pageNo, pageSize) => loadIo(pageNo, pageSize),
                    }}
                  />
                </>
              ),
            },
            {
              key: 'events',
              label: '任务事件链',
              children: (
                <>
                  <Space style={{ marginBottom: 12 }} wrap>
                    <Input placeholder="taskId（精确检索）" allowClear style={{ width: 240 }}
                           className="mono" value={eventFilter.taskId}
                           onChange={(e) => setEventFilter({ ...eventFilter, taskId: e.target.value })}
                           onPressEnter={loadEvents} />
                    <Select value={eventFilter.stage || undefined} placeholder="stage" allowClear
                            style={{ width: 140 }}
                            onChange={(v) => setEventFilter({ ...eventFilter, stage: v ?? '' })}
                            options={STAGES.map((s) => ({ value: s, label: s }))} />
                    <Select value={eventFilter.status || undefined} placeholder="状态" allowClear
                            style={{ width: 120 }}
                            onChange={(v) => setEventFilter({ ...eventFilter, status: v ?? '' })}
                            options={[
                              { value: 'OK', label: 'OK' },
                              { value: 'FAILED', label: 'FAILED' },
                              { value: 'SKIPPED', label: 'SKIPPED' },
                            ]} />
                    <Button type="primary" onClick={loadEvents}>检索</Button>
                  </Space>
                  {!eventSearched && (
                    <p style={{ color: 'var(--ant-color-text-tertiary)', fontSize: 12.5 }}>
                      输入 taskId 检索精确；不填 taskId 时仅允许 ≤7 天时间窗（防全表扫描）。
                    </p>
                  )}
                  <Table
                    size="small"
                    rowKey="id"
                    columns={eventColumns}
                    dataSource={eventRows}
                    loading={eventLoading}
                    pagination={false}
                  />
                </>
              ),
            },
          ]}
        />
      </Card>
    </div>
  )
}
