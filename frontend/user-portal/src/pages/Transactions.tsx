import { useEffect, useState } from 'react'
import { Alert, Card, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { gatewayUserApi } from '../api'
import type { TransactionView } from '../api'
import { gwCredsReady, useGatewayCredsStore } from '../stores/gwCreds'

const TYPE_TEXT: Record<string, string> = {
  HOLD: '冻结', SETTLE: '结算', RELEASE: '释放', RECHARGE: '充值', ADJUST: '调整',
}
const TYPE_COLOR: Record<string, string> = {
  HOLD: 'warning', SETTLE: 'processing', RELEASE: 'default', RECHARGE: 'success', ADJUST: 'error',
}

/** 动账流水（gateway HMAC，后端分页）。 */
export default function TransactionsPage() {
  const ready = gwCredsReady(useGatewayCredsStore.getState())
  const [rows, setRows] = useState<TransactionView[]>([])
  const [page, setPage] = useState({ pageNo: 1, pageSize: 20, total: 0 })
  const [loading, setLoading] = useState(false)

  async function load(pageNo = 1, pageSize = 20) {
    setLoading(true)
    try {
      const result = await gatewayUserApi.transactions(pageNo, pageSize)
      setRows(result.items)
      setPage({ pageNo, pageSize, total: result.total })
    } catch { /* 凭证缺失等：由引导提示覆盖 */ } finally {
      setLoading(false)
    }
  }
  useEffect(() => { if (ready) load() /* eslint-disable-line react-hooks/exhaustive-deps */ }, [ready])

  const columns: ColumnsType<TransactionView> = [
    { title: 'txId', dataIndex: 'txId', className: 'mono' },
    {
      title: '类型', width: 110,
      render: (_, row) => (
        <Tag color={TYPE_COLOR[row.type] ?? 'default'} style={{ margin: 0 }}>
          {TYPE_TEXT[row.type] ?? row.type}
        </Tag>
      ),
    },
    { title: '关联任务', dataIndex: 'taskId', width: 230, className: 'mono' },
    {
      title: '金额', dataIndex: 'amount', width: 90,
      render: (v: number) => <span className={v >= 0 ? 'pos' : 'neg'}>
        {v >= 0 ? `+${v}` : v}</span>,
    },
    { title: '余额', dataIndex: 'balanceAfter', width: 80 },
    { title: '时间', dataIndex: 'createdAt', width: 170 },
  ]

  return (
    <div>
      <div className="crumb">账户 / 动账流水</div>
      <h1 className="title">动账流水</h1>
      <p className="subtitle">HOLD / SETTLE / RELEASE / RECHARGE / ADJUST 全部动账记录</p>

      {!ready && (
        <Alert type="info" showIcon style={{ marginBottom: 14 }}
               message="尚未配置执行平台凭证"
               description="先到「APIKey」页录入凭证后即可查看流水。" />
      )}

      <Card>
        <Table
          size="small"
          rowKey="txId"
          columns={columns}
          dataSource={rows}
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
