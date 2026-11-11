import { useEffect, useState } from 'react'
import { App as AntdApp, Button, Card, Input, Space, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { LockOutlined, WalletOutlined } from '@ant-design/icons'
import { gatewayApi } from '../api/gateway'
import type { BalanceView, TransactionPage, TransactionView } from '../api/gateway'

const TYPE_TEXT: Record<string, string> = {
  HOLD: '冻结', SETTLE: '结算', RELEASE: '释放', RECHARGE: '充值', ADJUST: '调整',
}
const TYPE_COLOR: Record<string, string> = {
  HOLD: 'warning', SETTLE: 'processing', RELEASE: 'default', RECHARGE: 'success', ADJUST: 'error',
}

function fmtDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

export default function AccountPage() {
  const { message } = AntdApp.useApp()
  const [balance, setBalance] = useState<BalanceView | null>(null)
  const [page, setPage] = useState<TransactionPage>({ total: 0, pageNo: 1, pageSize: 20, items: [] })
  const [query, setQuery] = useState({ taskId: '', pageNo: 1, pageSize: 20 })
  const [loading, setLoading] = useState(false)

  async function load(pageNo = query.pageNo, pageSize = query.pageSize, taskId = query.taskId) {
    setLoading(true)
    try {
      const [balanceView, txPage] = await Promise.all([
        gatewayApi.balance(),
        gatewayApi.transactions(taskId.trim() || undefined, pageNo, pageSize),
      ])
      setBalance(balanceView)
      setPage(txPage)
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setLoading(false)
    }
  }
  useEffect(() => { load(1) /* eslint-disable-line react-hooks/exhaustive-deps */ }, [])

  const columns: ColumnsType<TransactionView> = [
    { title: 'txId', dataIndex: 'txId', width: 220, className: 'mono' },
    {
      title: '类型', width: 100,
      render: (_, row) => (
        <Tag color={TYPE_COLOR[row.type] ?? 'default'} style={{ margin: 0 }}>
          {TYPE_TEXT[row.type] ?? row.type}
        </Tag>
      ),
    },
    { title: '关联任务', dataIndex: 'taskId', width: 220, className: 'mono' },
    {
      title: '金额', dataIndex: 'amount', width: 90,
      render: (v: number) => (
        <span className={v >= 0 ? 'pos' : 'neg'}>{v >= 0 ? `+${v}` : v}</span>
      ),
    },
    { title: '余额', dataIndex: 'balanceAfter', width: 80 },
    { title: '备注', dataIndex: 'remark', ellipsis: true },
    { title: '时间', dataIndex: 'createdAt', width: 170, render: fmtDateTime },
  ]

  return (
    <div>
      <div className="page-header">
        <div>
          <h2>账户流水</h2>
          <p className="sub">当前 AppKey 的点数账户：可用 / 冻结与全部动账记录</p>
        </div>
        <Button onClick={() => load()}>刷新</Button>
      </div>

      <div className="stat-grid">
        <div className="stat-card">
          <span className="icon-chip" style={{ background: 'linear-gradient(135deg,#3b5bf5,#7b5bf5)' }}>
            <WalletOutlined />
          </span>
          <div>
            <div className="label">可用点数</div>
            <div className="value" data-testid="balance">{balance?.balance ?? '-'}</div>
          </div>
        </div>
        <div className="stat-card">
          <span className="icon-chip" style={{ background: 'linear-gradient(135deg,#f79009,#f04438)' }}>
            <LockOutlined />
          </span>
          <div>
            <div className="label">冻结点数</div>
            <div className="value" data-testid="frozen">{balance?.frozen ?? '-'}</div>
          </div>
        </div>
      </div>

      <Card title="动账记录">
        <Space style={{ marginBottom: 12 }}>
          <Input placeholder="按 taskId 过滤" allowClear style={{ width: 240 }}
                 value={query.taskId}
                 onChange={(e) => setQuery((prev) => ({ ...prev, taskId: e.target.value }))}
                 onPressEnter={() => load(1)} />
          <Button onClick={() => load(1)}>筛选</Button>
        </Space>
        <Table
          size="small"
          rowKey="txId"
          columns={columns}
          dataSource={page.items}
          loading={loading}
          pagination={{
            current: page.pageNo, pageSize: page.pageSize, total: page.total,
            showSizeChanger: false,
            onChange: (pageNo, pageSize) => load(pageNo, pageSize),
          }}
          data-testid="tx-table"
        />
      </Card>
    </div>
  )
}
