import { useEffect, useState } from 'react'
import { App as AntdApp, Button, Card, Form, Input, InputNumber, Modal, Select, Space, Switch, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { gatewayApi } from '../api/gateway'
import type { ModelCatalogEntry, ModelProviderView } from '../api/gateway'

interface TestResult {
  open: boolean
  loading: boolean
  alias: string
  ok: boolean | null
  message: string
  latencyMs: number
  httpStatus: number
  sample: string
}

const EMPTY_TEST: TestResult = {
  open: false, loading: false, alias: '', ok: null, message: '', latencyMs: 0, httpStatus: 0,
  sample: '',
}

export default function ModelProvidersPage() {
  const { message } = AntdApp.useApp()
  const [items, setItems] = useState<ModelProviderView[]>([])
  const [catalog, setCatalog] = useState<ModelCatalogEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [dialog, setDialog] = useState({
    open: false, editing: false,
    alias: '', provider: '', modelName: '', endpoint: '', apiKey: '', fallbackAlias: '',
    maxQps: 10, costPer1kInput: '', costPer1kOutput: '', costPerCall: '', status: 1,
  })
  const catalogEntry = catalog.find((entry) => entry.provider === dialog.provider) ?? null
  const [testing, setTesting] = useState<TestResult>(EMPTY_TEST)

  async function load() {
    setLoading(true)
    try {
      setItems(await gatewayApi.adminListModelProviders())
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setLoading(false)
    }
  }

  async function loadCatalog() {
    try {
      setCatalog(await gatewayApi.adminModelCatalog())
    } catch { /* 目录拉取失败退化为手填 */ }
  }
  useEffect(() => { load(); loadCatalog() /* eslint-disable-line react-hooks/exhaustive-deps */ }, [])

  function onProviderChange(provider: string) {
    const entry = catalog.find((item) => item.provider === provider) ?? null
    setDialog((prev) => ({
      ...prev,
      provider,
      // 新建时供应商选择联动：endpoint 模板 + 默认模型
      endpoint: entry && !prev.editing ? entry.endpointHint : prev.endpoint,
      modelName: entry && !prev.editing ? entry.models[0] ?? '' : prev.modelName,
    }))
  }

  function openCreate() {
    setDialog({
      open: true, editing: false, alias: '', provider: '', modelName: '', endpoint: '',
      apiKey: '', fallbackAlias: '', maxQps: 10, costPer1kInput: '', costPer1kOutput: '',
      costPerCall: '', status: 1,
    })
  }

  function openEdit(row: ModelProviderView) {
    setDialog({
      open: true, editing: true, alias: row.alias, provider: row.provider,
      modelName: row.modelName, endpoint: row.endpoint, apiKey: '',
      fallbackAlias: row.fallbackAlias ?? '', maxQps: row.maxQps,
      costPer1kInput: row.costPer1kInputTokens ?? '', costPer1kOutput: row.costPer1kOutputTokens ?? '',
      costPerCall: row.costPerCall ?? '', status: row.status,
    })
  }

  async function submit() {
    try {
      await gatewayApi.adminUpsertModelProvider({
        alias: dialog.alias, provider: dialog.provider, modelName: dialog.modelName,
        endpoint: dialog.endpoint || undefined,
        apiKey: dialog.apiKey || undefined,
        fallbackAlias: dialog.fallbackAlias || undefined,
        maxQps: dialog.maxQps,
        costPer1kInput: dialog.costPer1kInput || undefined,
        costPer1kOutput: dialog.costPer1kOutput || undefined,
        costPerCall: dialog.costPerCall || undefined,
        status: dialog.status,
      })
      message.success('已保存（Skill 按 alias 引用）')
      setDialog((prev) => ({ ...prev, open: false }))
      await load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  /** 草稿连通性测试（弹窗内，不必先保存） */
  async function testDraft() {
    setTesting({ ...EMPTY_TEST, open: true, loading: true })
    try {
      const result = await gatewayApi.adminTestModelProvider({
        endpoint: dialog.endpoint, modelName: dialog.modelName, apiKey: dialog.apiKey || undefined,
      })
      setTesting({ ...EMPTY_TEST, ...result, open: true, loading: false })
    } catch (e) {
      setTesting({ ...EMPTY_TEST, open: true, loading: false, ok: false, message: (e as Error).message })
    }
  }

  /** 已存配置连通性测试（表格行操作） */
  async function testSaved(alias: string) {
    setTesting({ ...EMPTY_TEST, open: true, loading: true, alias })
    try {
      const result = await gatewayApi.adminTestModelProvider({ alias })
      setTesting({ ...EMPTY_TEST, ...result, open: true, loading: false, alias })
    } catch (e) {
      setTesting({ ...EMPTY_TEST, open: true, loading: false, ok: false, message: (e as Error).message })
    }
  }

  async function remove(alias: string) {
    try {
      await gatewayApi.adminDeleteModelProvider(alias)
      message.success('已删除')
      await load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function toggleStatus(row: ModelProviderView) {
    try {
      await gatewayApi.adminModelProviderStatus(row.alias, row.status === 1 ? 0 : 1)
      await load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const columns: ColumnsType<ModelProviderView> = [
    { title: 'alias', dataIndex: 'alias', width: 180, className: 'mono' },
    { title: '供应商', dataIndex: 'provider', width: 110 },
    { title: '模型', dataIndex: 'modelName', width: 180 },
    { title: '回退', dataIndex: 'fallbackAlias', width: 150 },
    { title: 'QPS', dataIndex: 'maxQps', width: 70 },
    {
      title: '成本(入/出 /1k)', width: 160,
      render: (_, row) => `${row.costPer1kInputTokens ?? '-'} / ${row.costPer1kOutputTokens ?? '-'}`,
    },
    {
      title: '状态', width: 80,
      render: (_, row) => (
        <Tag color={row.status === 1 ? 'success' : 'error'} style={{ margin: 0 }}>
          {row.status === 1 ? '启用' : '禁用'}
        </Tag>
      ),
    },
    {
      title: '操作', width: 210, fixed: 'right',
      render: (_, row) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={() => openEdit(row)}>编辑</Button>
          <Button type="link" size="small" onClick={() => testSaved(row.alias)}
                  data-testid={`test-${row.alias}`}>测试</Button>
          <Button type="link" size="small"
                  onClick={() => toggleStatus(row)}>
            {row.status === 1 ? '禁用' : '启用'}
          </Button>
          <Button type="link" danger size="small" onClick={() => remove(row.alias)}
                  data-testid={`delete-${row.alias}`}>删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <div className="page-header">
        <div>
          <h2>模型与能力</h2>
          <p className="sub">
            模型别名路由：Skill 只认 alias；API Key AES 加密存储、永不回显；费率用于执行明细费用折算
          </p>
        </div>
        <Button type="primary" onClick={openCreate} data-testid="create-provider">新增模型</Button>
      </div>

      <Card>
        <Table
          size="small"
          rowKey="alias"
          columns={columns}
          dataSource={items}
          loading={loading}
          data-testid="providers-table"
        />
      </Card>

      <Modal
        open={dialog.open}
        title={dialog.editing ? '编辑模型' : '新增模型'}
        width={560}
        onCancel={() => setDialog((prev) => ({ ...prev, open: false }))}
        footer={null}
      >
        <Form layout="vertical">
          <Form.Item label="alias" required tooltip="Skill 按 alias 引用模型">
            <Input data-testid="provider-alias" value={dialog.alias} disabled={dialog.editing}
                   placeholder="如 llm-text"
                   onChange={(e) => setDialog((prev) => ({ ...prev, alias: e.target.value }))} />
          </Form.Item>
          <Form.Item label="供应商" required>
            <Select data-testid="provider-select" filterOption={false}
                    showSearch
                    value={dialog.provider || undefined}
                    placeholder="选择供应商（自动带出模型列表）"
                    style={{ width: '100%' }}
                    onChange={onProviderChange}
                    options={catalog.map((entry) => ({
                      value: entry.provider,
                      label: `${entry.label}（${entry.provider}）`,
                    }))} />
          </Form.Item>
          <Form.Item label="模型名" required>
            {catalogEntry && catalogEntry.models.length > 0 ? (
              <Select data-testid="model-select" showSearch
                      value={dialog.modelName || undefined}
                      placeholder="从目录选择或输入自定义模型名"
                      style={{ width: '100%' }}
                      onChange={(v) => setDialog((prev) => ({ ...prev, modelName: v }))}
                      options={catalogEntry.models.map((m) => ({ value: m, label: m }))} />
            ) : (
              <Input value={dialog.modelName} placeholder="供应商侧模型名"
                     onChange={(e) => setDialog((prev) => ({ ...prev, modelName: e.target.value }))} />
            )}
          </Form.Item>
          <Form.Item label="Endpoint" required>
            <Input data-testid="provider-endpoint" value={dialog.endpoint}
                   placeholder={catalogEntry?.endpointHint ?? 'API 端点地址（OpenAI 兼容）'}
                   onChange={(e) => setDialog((prev) => ({ ...prev, endpoint: e.target.value }))} />
          </Form.Item>
          <Form.Item label="API Key">
            <Input.Password data-testid="provider-apikey" value={dialog.apiKey}
                            placeholder={dialog.editing ? '留空 = 不修改' : 'AES 加密存储，永不回显'}
                            onChange={(e) => setDialog((prev) => ({ ...prev, apiKey: e.target.value }))} />
          </Form.Item>
          <Form.Item label="回退 alias">
            <Input value={dialog.fallbackAlias} placeholder="失败时的回退模型（可空）"
                   onChange={(e) => setDialog((prev) => ({ ...prev, fallbackAlias: e.target.value }))} />
          </Form.Item>
          <Form.Item label="最大 QPS">
            <InputNumber min={1} value={dialog.maxQps}
                         onChange={(v) => setDialog((prev) => ({ ...prev, maxQps: v ?? 10 }))} />
          </Form.Item>
          <Form.Item label="成本/1k 输入">
            <Input data-testid="provider-cost-input" value={dialog.costPer1kInput}
                   placeholder="点数，如 0.01（用于执行明细费用折算）"
                   onChange={(e) => setDialog((prev) => ({ ...prev, costPer1kInput: e.target.value }))} />
          </Form.Item>
          <Form.Item label="成本/1k 输出">
            <Input data-testid="provider-cost-output" value={dialog.costPer1kOutput}
                   placeholder="点数，如 0.02"
                   onChange={(e) => setDialog((prev) => ({ ...prev, costPer1kOutput: e.target.value }))} />
          </Form.Item>
          <Form.Item label="状态">
            <Switch checked={dialog.status === 1} checkedChildren="启用" unCheckedChildren="禁用"
                    onChange={(v) => setDialog((prev) => ({ ...prev, status: v ? 1 : 0 }))} />
          </Form.Item>
          <Space>
            <Button data-testid="provider-test-draft" onClick={testDraft}>连通性测试</Button>
            <Button type="primary" data-testid="provider-submit" onClick={submit}>保存</Button>
          </Space>
        </Form>
      </Modal>

      <Modal
        open={testing.open}
        title={testing.alias ? `连通性测试 · ${testing.alias}` : '连通性测试（草稿）'}
        width={520}
        onCancel={() => setTesting(EMPTY_TEST)}
        footer={<Button onClick={() => setTesting(EMPTY_TEST)}>关闭</Button>}
      >
        <div style={{ minHeight: 90 }} data-testid="provider-test-dialog">
          {testing.loading ? (
            <span>测试中…</span>
          ) : (
            <>
              <Tag color={testing.ok ? 'success' : 'error'} style={{ fontSize: 14, padding: '4px 12px' }}>
                {testing.ok ? '连通正常' : '连通失败'}
              </Tag>
              <p style={{ margin: '10px 0 6px' }}>{testing.message}</p>
              <p className="mono" style={{ color: 'var(--ant-color-text-tertiary)', fontSize: 12.5 }}>
                延迟 {testing.latencyMs}ms · HTTP {testing.httpStatus || '-'}
              </p>
              {testing.sample && (
                <pre className="secret-line" style={{ maxHeight: 120, overflow: 'auto', fontSize: 12 }}>
                  {testing.sample}
                </pre>
              )}
            </>
          )}
        </div>
      </Modal>
    </div>
  )
}
