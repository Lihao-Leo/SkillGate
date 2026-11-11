import { useEffect, useState } from 'react'
import { App as AntdApp, Button, Card, Form, Input, Modal, Space, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { gatewayApi } from '../api/gateway'

interface KbItem { kbId: string; tenantId: string; name: string; embeddingAlias: string; status: number }
interface KbDoc {
  docId: string
  status: number
  chunkCount: number
  createdAt?: string | null
  errorMessage?: string | null
}

export default function KbPage() {
  const { message, modal } = AntdApp.useApp()
  const [kbs, setKbs] = useState<KbItem[]>([])
  const [selected, setSelected] = useState<string>('')
  const [docs, setDocs] = useState<KbDoc[]>([])
  const [loading, setLoading] = useState(false)
  const [createOpen, setCreateOpen] = useState(false)
  const [createForm, setCreateForm] = useState({ tenantId: '', name: '', embeddingAlias: '' })

  async function loadKbs() {
    setLoading(true)
    try {
      const list = await gatewayApi.adminListKbs()
      setKbs(list as unknown as KbItem[])
      if (list.length > 0 && !selected) {
        setSelected((list[0] as unknown as KbItem).kbId)
        await loadDocs((list[0] as unknown as KbItem).kbId)
      }
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setLoading(false)
    }
  }

  async function loadDocs(kbId: string) {
    if (!kbId) return
    try {
      setDocs(await gatewayApi.adminListKbDocuments(kbId))
    } catch (e) {
      message.error((e as Error).message)
    }
  }
  useEffect(() => { loadKbs() /* eslint-disable-line react-hooks/exhaustive-deps */ }, [])

  async function selectKb(kbId: string) {
    setSelected(kbId)
    await loadDocs(kbId)
  }

  async function createKb() {
    try {
      await gatewayApi.adminCreateKb(
        createForm.tenantId.trim(),
        createForm.name.trim(),
        createForm.embeddingAlias.trim() || undefined,
      )
      message.success('知识库已创建')
      setCreateOpen(false)
      setCreateForm({ tenantId: '', name: '', embeddingAlias: '' })
      await loadKbs()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function deleteKb(kbId: string) {
    const ok = await new Promise<boolean>((resolve) => {
      modal.confirm({
        title: '删除知识库',
        content: `确定删除 ${kbId}？其文档与分块将一并删除。`,
        okButtonProps: { danger: true },
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      })
    })
    if (!ok) return
    try {
      await gatewayApi.adminDeleteKb(kbId)
      message.success('已删除')
      setSelected('')
      setDocs([])
      await loadKbs()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function onUpload(event: React.ChangeEvent<HTMLInputElement>) {
    const files = event.target.files
    if (!files || files.length === 0 || !selected) return
    try {
      await gatewayApi.adminAddKbDocument(selected, files[0])
      message.success('文档已登记（待入库）')
      await loadDocs(selected)
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      event.target.value = ''
    }
  }

  async function ingest(docId: string) {
    try {
      const result = await gatewayApi.adminIngestKbDocument(selected, docId)
      message.success(result.indexed
        ? `入库完成，分块 ${result.chunkCount}`
        : `已分块 ${result.chunkCount}（Milvus 未启用，暂不入库）`)
      await loadDocs(selected)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function deleteDoc(docId: string) {
    try {
      await gatewayApi.adminDeleteKbDocument(selected, docId)
      await loadDocs(selected)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const docColumns: ColumnsType<KbDoc> = [
    { title: 'docId', dataIndex: 'docId', width: 200, className: 'mono' },
    {
      title: '状态', width: 100,
      render: (_, row) => (
        <Tag color={row.status === 1 ? 'success' : row.status === 2 ? 'error' : 'default'}>
          {row.status === 1 ? '已入库' : row.status === 2 ? '失败' : row.status === 3 ? '处理中' : '待入库'}
        </Tag>
      ),
    },
    { title: '分块数', dataIndex: 'chunkCount', width: 90 },
    { title: '上传时间', dataIndex: 'createdAt', width: 170 },
    {
      title: '操作', width: 150,
      render: (_, row) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={() => ingest(row.docId)}>入库</Button>
          <Button type="link" danger size="small" onClick={() => deleteDoc(row.docId)}>删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <div className="page-header">
        <div>
          <h2>知识库</h2>
          <p className="sub">租户 KB 与文档入库（分块 + 向量化）</p>
        </div>
        <Button type="primary" onClick={() => setCreateOpen(true)}>新建知识库</Button>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '320px 1fr', gap: 16 }}>
        <Card title="知识库列表" size="small">
          <Table
            size="small"
            rowKey="kbId"
            dataSource={kbs}
            loading={loading}
            pagination={false}
            onRow={(row) => ({ onClick: () => selectKb(row.kbId), style: { cursor: 'pointer' } })}
            data-testid={`kb-${selected}`}
          >
            <Table.Column title="名称" dataIndex="name" />
            <Table.Column title="租户" dataIndex="tenantId" width={110} ellipsis />
            <Table.Column
              title="操作" width={70}
              render={(_, row: KbItem) => (
                <Button type="link" danger size="small"
                        onClick={(e) => { e.stopPropagation(); deleteKb(row.kbId) }}>删</Button>
              )}
            />
          </Table>
        </Card>

        <Card title={`文档（${selected || '未选择知识库'}）`} size="small">
          <Space style={{ marginBottom: 12 }}>
            <input type="file" accept=".txt,.md" disabled={!selected} onChange={onUpload} />
          </Space>
          <Table size="small" rowKey="docId" columns={docColumns} dataSource={docs}
                 pagination={false} />
        </Card>
      </div>

      <Modal open={createOpen} title="新建知识库" width={440}
             onCancel={() => setCreateOpen(false)} footer={null}>
        <Form layout="vertical" onFinish={createKb}>
          <Form.Item label="租户" required>
            <Input value={createForm.tenantId}
                   onChange={(e) => setCreateForm({ ...createForm, tenantId: e.target.value })} />
          </Form.Item>
          <Form.Item label="名称" required>
            <Input value={createForm.name}
                   onChange={(e) => setCreateForm({ ...createForm, name: e.target.value })} />
          </Form.Item>
          <Form.Item label="embedding alias">
            <Input value={createForm.embeddingAlias} placeholder="默认 embedding-default"
                   onChange={(e) => setCreateForm({ ...createForm, embeddingAlias: e.target.value })} />
          </Form.Item>
          <Button type="primary" htmlType="submit" block>创建</Button>
        </Form>
      </Modal>
    </div>
  )
}
