import { useEffect, useState } from 'react'
import { App as AntdApp, Button, Card, Form, Input, Modal, Select, Space, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { gatewayApi } from '../api/gateway'
import type { RoleView, UserPage, UserView } from '../api/gateway'

export default function UsersPage() {
  const { message } = AntdApp.useApp()
  const [rows, setRows] = useState<UserView[]>([])
  const [roles, setRoles] = useState<RoleView[]>([])
  const [page, setPage] = useState({ pageNo: 1, pageSize: 20, total: 0 })
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(false)

  const [createOpen, setCreateOpen] = useState(false)
  const [createForm, setCreateForm] = useState({
    username: '', password: '', displayName: '', roleCode: 'VIEWER',
  })
  const [resetResult, setResetResult] = useState<{ open: boolean; username: string; password: string }>({
    open: false, username: '', password: '',
  })

  async function load(pageNo = 1, pageSize = 20, kw = keyword) {
    setLoading(true)
    try {
      const result = await gatewayApi.adminListUsers(kw.trim() || undefined, pageNo, pageSize)
      setRows(result.rows)
      setPage({ pageNo: result.pageNo, pageSize: result.pageSize, total: result.total })
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setLoading(false)
    }
  }

  async function loadRoles() {
    try {
      setRoles(await gatewayApi.adminListRoles())
    } catch (e) {
      message.error((e as Error).message)
    }
  }
  useEffect(() => { load(); loadRoles() /* eslint-disable-line react-hooks/exhaustive-deps */ }, [])

  async function create() {
    try {
      await gatewayApi.adminCreateUser({
        username: createForm.username.trim(),
        password: createForm.password,
        displayName: createForm.displayName.trim() || undefined,
        roleCode: createForm.roleCode,
      })
      message.success('用户已创建')
      setCreateOpen(false)
      setCreateForm({ username: '', password: '', displayName: '', roleCode: 'VIEWER' })
      await load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function changeRole(row: UserView, roleCode: string) {
    try {
      await gatewayApi.adminChangeUserRole(row.username, roleCode)
      message.success(`已切换 ${row.username} 的角色`)
      await load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function toggleStatus(row: UserView) {
    try {
      await gatewayApi.adminChangeUserStatus(row.username, row.status === 1 ? 0 : 1)
      await load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function resetPassword(row: UserView) {
    try {
      const result = await gatewayApi.adminResetUserPassword(row.username)
      setResetResult({ open: true, username: result.username, password: result.password })
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const columns: ColumnsType<UserView> = [
    { title: '用户名', dataIndex: 'username', width: 140 },
    { title: '显示名', dataIndex: 'displayName', width: 120 },
    {
      title: '角色', width: 190,
      render: (_, row) => (
        <Select
          value={row.role}
          size="small"
          style={{ width: 160 }}
          onChange={(v) => changeRole(row, v)}
          options={roles.map((role) => ({
            value: role.roleCode,
            label: `${role.roleName}（${role.roleCode}）`,
          }))}
        />
      ),
    },
    {
      title: '状态', width: 90,
      render: (_, row) => (
        <Tag color={row.status === 1 ? 'success' : 'default'} style={{ margin: 0 }}>
          {row.status === 1 ? '启用' : '禁用'}
        </Tag>
      ),
    },
    { title: '最近登录', dataIndex: 'lastLoginAt', width: 170 },
    {
      title: '操作', width: 190, fixed: 'right',
      render: (_, row) => (
        <Button danger type="link" size="small" data-testid={`reset-${row.username}`}
                onClick={() => resetPassword(row)}>
          重置密码
        </Button>
      ),
    },
  ]

  return (
    <div>
      <div className="page-header">
        <div>
          <h2>用户与角色</h2>
          <p className="sub">后台账号管理：角色分配 / 启停 / 密码轮换</p>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <Input value={keyword} placeholder="按用户名搜索" allowClear style={{ width: 220 }}
                 data-testid="user-keyword"
                 onChange={(e) => setKeyword(e.target.value)}
                 onPressEnter={() => load()} />
          <Button data-testid="user-search" onClick={() => load()}>搜索</Button>
          <Button type="primary" data-testid="user-create"
                  onClick={() => setCreateOpen(true)}>新建用户</Button>
        </div>
      </div>

      <Card>
        <Table
          size="small"
          rowKey="username"
          columns={columns}
          dataSource={rows}
          loading={loading}
          pagination={{
            current: page.pageNo, pageSize: page.pageSize, total: page.total,
            showSizeChanger: false,
            onChange: (pageNo, pageSize) => load(pageNo, pageSize),
          }}
          data-testid="user-table"
        />
      </Card>

      <Modal open={createOpen} title="新建用户" width={440}
             onCancel={() => setCreateOpen(false)} footer={null}>
        <Form layout="vertical" onFinish={create}>
          <Form.Item label="用户名" required>
            <Input data-testid="create-username" value={createForm.username}
                   onChange={(e) => setCreateForm({ ...createForm, username: e.target.value })} />
          </Form.Item>
          <Form.Item label="初始密码" required tooltip="不少于 8 位">
            <Input.Password data-testid="create-password" value={createForm.password}
                            onChange={(e) => setCreateForm({ ...createForm, password: e.target.value })} />
          </Form.Item>
          <Form.Item label="显示名">
            <Input value={createForm.displayName}
                   onChange={(e) => setCreateForm({ ...createForm, displayName: e.target.value })} />
          </Form.Item>
          <Form.Item label="角色" required>
            <Select data-testid="create-role" style={{ width: '100%' }} value={createForm.roleCode}
                    onChange={(v) => setCreateForm({ ...createForm, roleCode: v })}
                    options={roles.map((role) => ({
                      value: role.roleCode,
                      label: `${role.roleName}（${role.roleCode}）`,
                    }))} />
          </Form.Item>
          <Button type="primary" htmlType="submit" block data-testid="create-submit">创建</Button>
        </Form>
      </Modal>

      <Modal open={resetResult.open} title="密码已重置" width={420}
             onCancel={() => setResetResult((prev) => ({ ...prev, open: false }))}
             footer={<Button onClick={() => setResetResult((prev) => ({ ...prev, open: false }))}>
               关闭
             </Button>}>
        <p>请将新密码告知 <b>{resetResult.username}</b>（仅本次展示）：</p>
        <code className="secret-line" data-testid="reset-password-value">{resetResult.password}</code>
      </Modal>
    </div>
  )
}
