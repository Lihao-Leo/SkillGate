import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { App as AntdApp, Button, Card, Form, Input, InputNumber, Modal, Space, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { gatewayApi } from '../api/gateway'
import type { AdminKeyPage } from '../api/gateway'
import { clearCredentials, saveCredentials, useCredentialsStore } from '../stores/credentials'

interface OnceDialog {
  open: boolean
  appKeyId: string
  appSecret: string
}

interface RechargeDialog {
  open: boolean
  appKeyId: string
  points: number
  orderNo: string
  submitting: boolean
}

export default function AppKeysPage() {
  const navigate = useNavigate()
  const { message } = AntdApp.useApp()
  const creds = useCredentialsStore.getState()
  const [page, setPage] = useState<AdminKeyPage>({ total: 0, pageNo: 1, pageSize: 20, items: [] })
  const [query, setQuery] = useState({ tenantId: '', pageNo: 1, pageSize: 20 })
  const [loading, setLoading] = useState(false)
  const [once, setOnce] = useState<OnceDialog>({ open: false, appKeyId: '', appSecret: '' })
  const [recharge, setRecharge] = useState<RechargeDialog>(
    { open: false, appKeyId: '', points: 100, orderNo: '', submitting: false })

  async function load(pageNo = query.pageNo, pageSize = query.pageSize, tenantId = query.tenantId) {
    setLoading(true)
    try {
      setPage(await gatewayApi.adminListKeys(tenantId || undefined, pageNo, pageSize))
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setLoading(false)
    }
  }
  useEffect(() => { load(1) /* eslint-disable-line react-hooks/exhaustive-deps */ }, [])

  async function toggleStatus(appKeyId: string, current: number) {
    const next = current === 1 ? 0 : 1
    try {
      await gatewayApi.adminUpdateKeyStatus(appKeyId, next as 0 | 1)
      message.success(next === 1 ? '已启用' : '已禁用')
      // 禁用当前签名所用 key：业务面凭证即刻失效，清空并引导重配
      if (next === 0 && appKeyId === creds.appKey) {
        clearCredentials()
        message.warning('已禁用当前使用的 AppKey，业务面凭证已失效，请重新配置')
        navigate('/connect')
        return
      }
      await load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function resetSecret(appKeyId: string) {
    try {
      const result = await gatewayApi.adminResetSecret(appKeyId)
      setOnce({ open: true, appKeyId: result.appKeyId, appSecret: result.appSecret })
      // 轮换的若是当前签名所用 key：用一次性新 Secret 同步本地凭证，业务面无缝续用
      if (appKeyId === creds.appKey) {
        saveCredentials({
          appKey: result.appKeyId, secret: result.appSecret,
          adminToken: useCredentialsStore.getState().adminToken,
        })
        message.success('当前凭证已同步轮换，业务面无需重新配置')
      }
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  /** 一次性 Secret 弹窗内一键切换为业务面签名凭证 */
  function useAsCredentials() {
    saveCredentials({
      appKey: once.appKeyId,
      secret: once.appSecret,
      adminToken: useCredentialsStore.getState().adminToken,
    })
    message.success('已设为当前凭证，业务面立即生效')
    setOnce((prev) => ({ ...prev, open: false }))
  }

  async function submitRecharge() {
    setRecharge((prev) => ({ ...prev, submitting: true }))
    try {
      const result = await gatewayApi.adminRecharge(recharge.appKeyId,
        recharge.points, recharge.orderNo)
      message.success(result.alreadyCredited
        ? `该 orderNo 已入账（幂等命中），余额 ${result.balance}`
        : `入账成功，余额 ${result.balance}`)
      setRecharge((prev) => ({ ...prev, open: false }))
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setRecharge((prev) => ({ ...prev, submitting: false }))
    }
  }

  const columns: ColumnsType<any> = [
    { title: 'AppKey', dataIndex: 'appKeyId', width: 280, className: 'mono' },
    { title: '租户', dataIndex: 'tenantId', width: 160 },
    {
      title: '状态', width: 80,
      render: (_, row) => (
        <Tag color={row.status === 1 ? 'success' : 'error'} style={{ margin: 0 }}>
          {row.status === 1 ? '启用' : '禁用'}
        </Tag>
      ),
    },
    { title: '创建时间', dataIndex: 'createdAt', width: 170 },
    {
      title: '操作', width: 220, fixed: 'right',
      render: (_, row) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={() => toggleStatus(row.appKeyId, row.status)}
                  data-testid={`toggle-${row.appKeyId}`}>
            {row.status === 1 ? '禁用' : '启用'}
          </Button>
          <Button type="link" size="small" onClick={() => resetSecret(row.appKeyId)}
                  data-testid={`reset-${row.appKeyId}`}>重置 Secret</Button>
          <Button type="link" size="small"
                  onClick={() => setRecharge((prev) => ({
                    ...prev, open: true, appKeyId: row.appKeyId, points: 100, orderNo: '',
                  }))}>入账</Button>
        </Space>
      ),
    },
  ]

  return (
    <div>
      {once.open && (
        <div style={{ display: 'none' }} data-testid="once-appkey">{once.appKeyId}</div>
      )}
      <div className="page-header">
        <div>
          <h2>AppKey 与账户</h2>
          <p className="sub">租户接入凭证签发与轮换（Secret 仅签发/重置时一次性展示）</p>
        </div>
        <Space>
          <Input placeholder="按租户过滤" allowClear style={{ width: 160 }}
                 value={query.tenantId}
                 onChange={(e) => setQuery((prev) => ({ ...prev, tenantId: e.target.value }))}
                 onPressEnter={() => load(1)} />
          <Button onClick={() => load(1)}>刷新</Button>
          <Button type="primary" onClick={() => setRecharge((prev) => ({
            ...prev, open: true, appKeyId: '', points: 100, orderNo: '',
          }))}>人工入账</Button>
        </Space>
      </div>

      <Card>
        <Table
          size="small"
          rowKey="appKeyId"
          columns={columns}
          dataSource={page.items}
          loading={loading}
          pagination={{
            current: page.pageNo, pageSize: page.pageSize, total: page.total,
            showSizeChanger: false, showTotal: (total) => `共 ${total} 条`,
            onChange: (pageNo, pageSize) => load(pageNo, pageSize),
          }}
          data-testid="keys-table"
        />
      </Card>

      {/* 一次性 Secret 展示（关闭后不可再见；可一键设为当前业务面凭证） */}
      <Modal
        open={once.open}
        title="新 Secret（仅此一次展示）"
        onCancel={() => setOnce((prev) => ({ ...prev, open: false }))}
        footer={[
          <Button key="use" type="primary"
                  onClick={useAsCredentials}>设为当前凭证</Button>,
          <Button key="close" onClick={() => setOnce((prev) => ({ ...prev, open: false }))}>
            我已保存
          </Button>,
        ]}
      >
        <p>AppKey：</p>
        <div className="secret-line" data-testid="once-appkey">{once.appKeyId}</div>
        <p>AppSecret（完整值仅此一次展示，关闭后不可恢复）：</p>
        <span className="secret-line" data-testid="once-appsecret">{once.appSecret}</span>
      </Modal>

      {/* 人工入账（orderNo 幂等） */}
      <Modal
        open={recharge.open}
        title="点数入账（orderNo 幂等，重复调用返回已入账流水）"
        onCancel={() => setRecharge((prev) => ({ ...prev, open: false }))}
        footer={null}
      >
        <Form layout="vertical" onFinish={submitRecharge}>
          <Form.Item label="AppKey" required>
            <Input value={recharge.appKeyId} placeholder="sk-..."
                   onChange={(e) => setRecharge((prev) => ({ ...prev, appKeyId: e.target.value }))} />
          </Form.Item>
          <Form.Item label="点数" required>
            <InputNumber min={1} value={recharge.points} style={{ width: '100%' }}
                         onChange={(v) => setRecharge((prev) => ({ ...prev, points: v ?? 1 }))} />
          </Form.Item>
          <Form.Item label="orderNo" required tooltip="支付平台流水号，重复入账会幂等命中">
            <Input value={recharge.orderNo}
                   onChange={(e) => setRecharge((prev) => ({ ...prev, orderNo: e.target.value }))} />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={recharge.submitting}
                  data-testid="recharge-submit">入账</Button>
        </Form>
      </Modal>
    </div>
  )
}
