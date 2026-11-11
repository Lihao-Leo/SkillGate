import { useEffect, useState } from 'react'
import { Table } from 'antd'
import { gatewayApi, type ModelCallView } from '../api/gateway'

/**
 * 模型调用明细表（V2.1 执行抽屉内复用）：逐次 LLM 调用的 token 细分与费率折算点数。
 * 挂载即按 taskId 拉取；查询失败容忍（任务可能无 LLM 调用）。
 */
export default function ModelCallTable({ taskId }: { taskId: string }) {
  const [rows, setRows] = useState<ModelCallView[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!taskId) return
    let cancelled = false
    setLoading(true)
    gatewayApi.adminModelCalls(taskId)
      .then((data) => { if (!cancelled) setRows(data) })
      .catch(() => { /* 明细查询失败容忍 */ })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [taskId])

  return (
    <Table
      size="small"
      loading={loading}
      rowKey="id"
      dataSource={rows}
      pagination={false}
      data-testid="model-call-table"
    >
      <Table.Column title="#" width={46} render={(_v, _row, index) => index + 1} />
      <Table.Column title="模型 alias" dataIndex="alias" />
      <Table.Column title="模型" dataIndex="modelName" ellipsis />
      <Table.Column title="输入 tokens" dataIndex="promptTokens" align="right"
                    render={(v: number | null) => v ?? '-'} />
      <Table.Column title="输出 tokens" dataIndex="completionTokens" align="right"
                    render={(v: number | null) => v ?? '-'} />
      <Table.Column title="合计" dataIndex="totalTokens" align="right"
                    render={(v: number | null) => v ?? '-'} />
      <Table.Column title="点数" dataIndex="costPoints" align="right"
                    render={(v: number | null) => v ?? '-'} />
      <Table.Column title="耗时" dataIndex="durationMs" align="right"
                    render={(v: number | null) => v == null ? '-' : `${v}ms`} />
      <Table.Column title="状态" dataIndex="status"
                    render={(v: string) => (
                      <span style={{ color: v === 'OK' ? '#12b76a' : '#f04438', fontWeight: 600 }}>
                        {v}
                      </span>
                    )} />
    </Table>
  )
}
