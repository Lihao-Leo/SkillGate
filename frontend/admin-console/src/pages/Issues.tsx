import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { App as AntdApp, Button, Card, Input, Modal, Select, Space, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { gatewayApi } from '../api/gateway'
import type { IssuePage, IssueView } from '../api/gateway'
import { hasPerm, useAuthStore } from '../stores/auth'

const TYPE_TEXT: Record<string, string> = {
  ALERT: '告警', EXECUTION_FAILED: '执行失败', MANUAL: '手工',
}
const SEVERITY_COLOR: Record<string, string> = {
  INFO: 'default', WARN: 'warning', CRITICAL: 'error',
}
const STATUS_COLOR: Record<string, string> = {
  OPEN: 'error', FIXING: 'processing', RESOLVED: 'success', CLOSED: 'default',
}

/** 问题工单：告警/失败任务/手工三类来源的归因→修复→验证→关单闭环。 */
export default function IssuesPage() {
  const navigate = useNavigate()
  const { message, modal } = AntdApp.useApp()
  const canManage = hasPerm(useAuthStore.getState().session, 'issue:manage')

  const [rows, setRows] = useState<IssueView[]>([])
  const [page, setPage] = useState({ pageNo: 1, pageSize: 20, total: 0 })
  const [filters, setFilters] = useState({ status: '', type: '', severity: '', keyword: '' })
  const [loading, setLoading] = useState(false)
  const [detail, setDetail] = useState<IssueView | null>(null)

  async function load(pageNo = 1, pageSize = 20, nextFilters = filters) {
    setLoading(true)
    try {
      const result: IssuePage = await gatewayApi.adminListIssues({
        status: nextFilters.status || undefined,
        type: nextFilters.type || undefined,
        severity: nextFilters.severity || undefined,
        keyword: nextFilters.keyword.trim() || undefined,
        pageNo, pageSize,
      })
      setRows(result.records)
      setPage({ pageNo: result.pageNo, pageSize: result.pageSize, total: result.total })
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setLoading(false)
    }
  }
  useEffect(() => { load() /* eslint-disable-line react-hooks/exhaustive-deps */ }, [])

  async function openDetail(row: IssueView) {
    try {
      const view = await gatewayApi.adminGetIssue(row.id)
      setDetail((view as any).issue as IssueView)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function transition(row: IssueView, target: string) {
    const resolution = target === "RESOLVED" ? "解决方案（待补充详情）" : undefined;
    try {
      await gatewayApi.adminTransitionIssue(row.id, target, undefined, resolution)
      message.success(`已流转至 ${target}`)
      await load()
      if (detail && detail.id === row.id) await openDetail(row)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const columns: ColumnsType<IssueView> = [
    { title: '工单号', dataIndex: 'ticketNo', width: 190, className: 'mono' },
    {
      title: '级别', width: 100,
      render: (_, row) => (
        <Tag color={SEVERITY_COLOR[row.severity] ?? 'default'} style={{ margin: 0 }}>
          {row.severity}
        </Tag>
      ),
    },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    { title: '来源', dataIndex: 'type', width: 110,
      render: (v: string) => TYPE_TEXT[v] ?? v },
    { title: 'Skill', dataIndex: 'skillCode', width: 130 },
    {
      title: '关联任务', dataIndex: 'relatedTaskId', width: 200, ellipsis: true,
      className: 'mono',
      render: (v: string) => v ?? '—',
    },
    { title: '负责人', dataIndex: 'assignee', width: 100 },
    {
      title: '状态', width: 100,
      render: (_, row) => (
        <Tag color={STATUS_COLOR[row.status] ?? 'default'} style={{ margin: 0 }}>
          {row.status}
        </Tag>
      ),
    },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170 },
    {
      title: '操作', width: 130, fixed: 'right',
      render: (_, row) => (
        <Button type="link" size="small" onClick={() => openDetail(row)}>详情</Button>
      ),
    },
  ]

  const detailActions = (row: IssueView) => {
    if (!canManage) return null
    const actions: React.ReactNode[] = []
    if (row.status === 'OPEN') {
      actions.push(<Button key="fix" type="primary" size="small"
        onClick={() => transition(row, 'FIXING')}>开始处理</Button>)
      actions.push(<Button key="res" size="small"
        onClick={() => transition(row, 'RESOLVED')}>直接解决</Button>)
    }
    if (row.status === 'FIXING') {
      actions.push(<Button key="res" type="primary" size="small"
        onClick={() => transition(row, 'RESOLVED')}>提交解决</Button>)
    }
    if (row.status === 'RESOLVED') {
      actions.push(<Button key="close" type="primary" size="small"
        onClick={() => transition(row, 'CLOSED')}>验证通过，关单</Button>)
      actions.push(<Button key="back" size="small"
        onClick={() => transition(row, 'FIXING')}>回退处理中</Button>)
    }
    return actions
  }

  return (
    <div>
      <div className="page-header">
        <div>
          <h2>问题工单</h2>
          <p className="sub">告警 / 失败任务 / 手工来源的归因 → 修复 → 验证 → 关单闭环</p>
        </div>
        <Space style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <Select value={filters.status || undefined} placeholder="状态" allowClear
                  style={{ width: 120 }}
                  onChange={(v) => { setFilters({ ...filters, status: v ?? '' }); load(1, 20, { ...filters, status: v ?? '' }) }}
                  options={[
                    { value: 'OPEN', label: 'OPEN' },
                    { value: 'FIXING', label: 'FIXING' },
                    { value: 'RESOLVED', label: 'RESOLVED' },
                    { value: 'CLOSED', label: 'CLOSED' },
                  ]} />
          <Select value={filters.type || undefined} placeholder="来源" allowClear
                  style={{ width: 130 }}
                  onChange={(v) => { setFilters({ ...filters, type: v ?? '' }); load(1, 20, { ...filters, type: v ?? '' }) }}
                  options={Object.entries(TYPE_TEXT).map(([value, label]) => ({ value, label }))} />
          <Input value={filters.keyword} placeholder="工单号/标题/taskId" allowClear
                 style={{ width: 200 }} data-testid="issue-keyword"
                 onChange={(e) => setFilters({ ...filters, keyword: e.target.value })}
                 onPressEnter={() => load()} />
          <Button data-testid="issue-search" onClick={() => load()}>搜索</Button>
        </Space>
      </div>

      <Card>
        <Table
          size="small"
          rowKey="id"
          columns={columns}
          dataSource={rows}
          loading={loading}
          pagination={{
            current: page.pageNo, pageSize: page.pageSize, total: page.total,
            showSizeChanger: false,
            onChange: (pageNo, pageSize) => load(pageNo, pageSize),
          }}
          data-testid="issues-table"
        />
      </Card>

      {/* 详情抽屉 */}
      <Modal
        open={!!detail}
        title={detail ? `${detail.ticketNo} · ${detail.title}` : ''}
        width={720}
        onCancel={() => setDetail(null)}
        footer={detail && canManage ? (
          <Space>
            {detailActions(detail)}
            <Button onClick={() => setDetail(null)}>关闭</Button>
          </Space>
        ) : <Button onClick={() => setDetail(null)}>关闭</Button>}
      >
        {detail && (
          <Space direction="vertical" style={{ width: '100%' }} size={12}>
            <Space size={8}>
              <Tag color={SEVERITY_COLOR[detail.severity] ?? 'default'}>{detail.severity}</Tag>
              <Tag color={STATUS_COLOR[detail.status] ?? 'default'}>{detail.status}</Tag>
              <Tag>{TYPE_TEXT[detail.type] ?? detail.type}</Tag>
              {detail.assignee && <Tag>负责人：{detail.assignee}</Tag>}
            </Space>
            {detail.relatedTaskId && (
              <p style={{ margin: 0 }}>
                关联任务：
                <a onClick={() => { setDetail(null); navigate('/executions') }}
                   className="mono">{detail.relatedTaskId}</a>
                {' '}（到执行监控按 taskId 查询详情）
              </p>
            )}
            {detail.description && (
              <div><b>描述</b><p style={{ margin: '4px 0' }}>{detail.description}</p></div>
            )}
            {detail.rootCause && <div><b>根因</b><p style={{ margin: '4px 0' }}>{detail.rootCause}</p></div>}
            {detail.resolution && (
              <div><b>解决方案</b><p style={{ margin: '4px 0' }}>{detail.resolution}</p></div>
            )}
            <p style={{ color: 'var(--ant-color-text-tertiary)', fontSize: 12, margin: 0 }}>
              创建人 {detail.createdBy} · 创建 {detail.createdAt?.replace('T', ' ')}
              {detail.closedAt && <> · 关闭 {detail.closedAt?.replace('T', ' ')}</>}
            </p>
          </Space>
        )}
      </Modal>
    </div>
  )
}
