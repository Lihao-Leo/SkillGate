import { useEffect, useState } from 'react'
import { App as AntdApp, Alert, Button, Card, Input, Modal, Space, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { rechargeApi } from '../api'
import type { UserKeyItem } from '../api'
import { clearGwCreds, gwCredsReady, saveGwCreds, useGatewayCredsStore } from '../stores/gwCreds'

export default function KeysPage() {
  const { message } = AntdApp.useApp()
  const [keys, setKeys] = useState<UserKeyItem[]>([])
  const [loading, setLoading] = useState(false)
  const [once, setOnce] = useState({ open: false, appKeyId: '', appSecret: '' })
  const [credsForm, setCredsForm] = useState({ appKey: '', secret: '' })
  const gwState = useGatewayCredsStore()
  const gwReady = gwCredsReady(gwState)

  async function load() {
    setLoading(true)
    try {
      setKeys(await rechargeApi.listKeys())
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setLoading(false)
    }
  }
  useEffect(() => { load() /* eslint-disable-line react-hooks/exhaustive-deps */ }, [])

  /** 签发/重置得到的一次性 Secret：展示 + 预填执行平台凭证表单 */
  function showOnce(appKeyId: string, appSecret: string) {
    setOnce({ open: true, appKeyId, appSecret })
    setCredsForm({ appKey: appKeyId, secret: appSecret })
  }

  async function issue() {
    try {
      const result = await rechargeApi.issueKey()
      showOnce(result.appKeyId, result.appSecret)
      await load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function resetSecret(appKeyId: string) {
    try {
      const result = await rechargeApi.resetSecret(appKeyId)
      showOnce(result.appKeyId, result.appSecret)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function disable(appKeyId: string) {
    try {
      await rechargeApi.disableKey(appKeyId)
      message.success('已禁用')
      await load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  /** 把签发/重置得到（或用户粘贴）的 key+secret 配置为执行平台调用凭证 */
  function useAsGatewayCreds() {
    if (!credsForm.appKey || !credsForm.secret) {
      message.warning('请先在上面复制 AppKey 与 AppSecret')
      return
    }
    saveGwCreds(credsForm.appKey.trim(), credsForm.secret.trim())
    message.success('执行平台凭证已保存（本地浏览器）')
  }

  const columns: ColumnsType<UserKeyItem> = [
    { title: 'AppKey', dataIndex: 'appKeyId', className: 'mono' },
    {
      title: '主 Key', width: 90,
      render: (_, row) => row.isPrimary ? <Tag color="processing">主</Tag> : null,
    },
    { title: '创建时间', dataIndex: 'createdAt', width: 170 },
    {
      title: '操作', width: 180,
      render: (_, row) => (
        <Space size={4}>
          <Button type="link" size="small" data-testid={`reset-${row.appKeyId}`}
                  onClick={() => resetSecret(row.appKeyId)}>重置 Secret</Button>
          <Button type="link" danger size="small" data-testid={`disable-${row.appKeyId}`}
                  onClick={() => disable(row.appKeyId)}>禁用</Button>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <div className="crumb">账户 / APIKey 管理</div>
      <h1 className="title">APIKey 管理</h1>
      <p className="subtitle">调用执行平台的凭证（HMAC 签名）；Secret 仅签发/重置时一次性展示</p>

      <Card title="我的 APIKey" style={{ marginBottom: 16 }}>
        <Space style={{ marginBottom: 12 }}>
          <Button type="primary" data-testid="issue-btn" onClick={issue}>签发新 Key</Button>
        </Space>
        <Table size="small" rowKey="appKeyId" columns={columns} dataSource={keys}
               loading={loading} pagination={false} data-testid="keys-table" />
      </Card>

      <Card title="执行平台凭证">
        <p style={{ color: 'var(--ant-color-text-tertiary)', fontSize: 12.5, marginTop: 0 }}>
          用于任务查询 / 余额 / 流水（gateway HMAC 签名）。签发或重置后，把完整 AppKey + Secret
          录入到下方即可（本地浏览器存储）。
        </p>
        <div className="gw-state" data-testid="gw-creds-state">
          {gwReady
            ? <>已配置：<span className="mono">{gwState.appKey}</span>
                <Button type="link" danger size="small" onClick={() => {
                  clearGwCreds()
                  message.success('已清除执行平台凭证')
                }}>清除</Button>
              </>
            : '未配置（任务查询/余额/流水不可用）'}
        </div>
        <Space style={{ marginTop: 12, display: 'flex' }} size={8}>
          <Input data-testid="creds-appkey" placeholder="AppKey" style={{ width: 260 }}
                 className="mono" value={credsForm.appKey}
                 onChange={(e) => setCredsForm((prev) => ({ ...prev, appKey: e.target.value }))} />
          <Input.Password data-testid="creds-secret" placeholder="AppSecret" style={{ width: 260 }}
                          className="mono" value={credsForm.secret}
                          onChange={(e) => setCredsForm((prev) => ({ ...prev, secret: e.target.value }))} />
          <Button type="primary" data-testid="creds-save" onClick={useAsGatewayCreds}>
            保存凭证
          </Button>
        </Space>
      </Card>

      <Modal
        open={once.open}
        title="Secret（仅此一次展示）"
        onCancel={() => setOnce((prev) => ({ ...prev, open: false }))}
        footer={<Button type="primary"
                        onClick={() => setOnce((prev) => ({ ...prev, open: false }))}>我已保存</Button>}
      >
        <p>AppKey：</p>
        <div className="secret-line" data-testid="once-appkey">{once.appKeyId}</div>
        <p>AppSecret（关闭后不可恢复）：</p>
        <span className="secret-line" data-testid="once-appsecret">{once.appSecret}</span>
      </Modal>
    </div>
  )
}
