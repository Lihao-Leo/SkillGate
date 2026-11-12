import { useEffect, useState } from 'react'
import { App as AntdApp, Badge, Button, Card, Form, Input, InputNumber, Modal, Select,
  Space, Switch, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { gatewayApi } from '../api/gateway'
import type { AlertRecordPage, AlertRecordView, AlertRuleView } from '../api/gateway'
import { hasPerm, useAuthStore } from '../stores/auth'
import { usePrompt } from '../components/PromptModal'
import { useCreateIssue } from '../components/CreateIssueModal'

const RULE_TYPES = [
  { value: 'RUNNING_TIMEOUT', label: '任务运行超时（秒）' },
  { value: 'TOKEN_EXCESS', label: 'Token 消耗超量（个数）' },
  { value: 'TASK_FAILED', label: '任务失败（阈值无意义，填 0）' },
  { value: 'PENDING_STUCK', label: '排队积压（秒）' },
  { value: 'CALLBACK_DEAD_LETTER', label: '回调死信（次数）' },
  { value: 'WATCHDOG_INTERVENTION', label: '看门狗干预（次数）' },
]

function ruleTypeText(type: string): string {
  return RULE_TYPES.find((item) => item.value === type)?.label ?? type
}

interface RuleForm {
  open: boolean
  id: number | null
  ruleCode: string
  ruleName: string
  ruleType: string
  skillCode: string
  thresholdValue: number
  severity: string
  channelSelection: string[]
  dedupWindowSeconds: number
  enabledBool: boolean
}

const EMPTY_RULE_FORM: RuleForm = {
  open: false, id: null, ruleCode: '', ruleName: '', ruleType: 'RUNNING_TIMEOUT',
  skillCode: '', thresholdValue: 600, severity: 'WARN',
  channelSelection: ['feishu', 'record'], dedupWindowSeconds: 86400, enabledBool: true,
}

export default function AlertsPage() {
  const { message } = AntdApp.useApp()
  const session = useAuthStore((state) => state.session)
  const canManage = hasPerm(session, 'alert:manage')
  const [promptContextHolder, prompt] = usePrompt()
  const [createIssueContextHolder, createIssue] = useCreateIssue()

  // ---- 规则 ----
  const [rules, setRules] = useState<AlertRuleView[]>([])
  const [rulesLoading, setRulesLoading] = useState(false)
  const [ruleForm, setRuleForm] = useState<RuleForm>(EMPTY_RULE_FORM)

  // ---- 告警记录 ----
  const [records, setRecords] = useState<AlertRecordView[]>([])
  const [recordsLoading, setRecordsLoading] = useState(false)
  const [recordQuery, setRecordQuery] = useState({
    status: 'PENDING', ruleType: '', taskId: '', pageNo: 1, pageSize: 20,
  })
  const [recordTotal, setRecordTotal] = useState(0)
  const [pendingCount, setPendingCount] = useState(0)

  async function loadRules() {
    setRulesLoading(true)
    try {
      setRules(await gatewayApi.adminListAlertRules())
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setRulesLoading(false)
    }
  }

  async function loadRecords() {
    setRecordsLoading(true)
    try {
      const page: AlertRecordPage = await gatewayApi.adminListAlertRecords({
        status: recordQuery.status || undefined,
        ruleType: recordQuery.ruleType || undefined,
        taskId: recordQuery.taskId.trim() || undefined,
        pageNo: recordQuery.pageNo, pageSize: recordQuery.pageSize,
      })
      setRecords(page.rows)
      setRecordTotal(page.total)
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setRecordsLoading(false)
    }
  }

  async function loadPendingCount() {
    try {
      setPendingCount((await gatewayApi.adminAlertPendingCount()).count)
    } catch { /* 徽标失败容忍 */ }
  }

  useEffect(() => {
    loadRules()
    loadRecords()
    loadPendingCount()
    // 30s 轮询待处理数（useEffect cleanup，StrictMode 安全）
    const timer = setInterval(loadPendingCount, 30000)
    return () => clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function openCreate() {
    setRuleForm({ ...EMPTY_RULE_FORM, open: true })
  }

  function openEdit(row: AlertRuleView) {
    setRuleForm({
      open: true, id: row.id, ruleCode: row.ruleCode, ruleName: row.ruleName,
      ruleType: row.ruleType, skillCode: row.skillCode ?? '',
      thresholdValue: row.thresholdValue ?? 0, severity: row.severity,
      channelSelection: (row.channels ?? '').split(',').map((s) => s.trim()).filter(Boolean),
      dedupWindowSeconds: row.dedupWindowSeconds, enabledBool: row.enabled === 1,
    })
  }

  async function saveRule() {
    if (!ruleForm.ruleCode.trim() || !ruleForm.ruleName.trim()) {
      message.warning('规则编码与名称必填')
      return
    }
    const payload = {
      ruleCode: ruleForm.ruleCode.trim(),
      ruleName: ruleForm.ruleName.trim(),
      ruleType: ruleForm.ruleType,
      skillCode: ruleForm.skillCode.trim() || undefined,
      thresholdValue: Number(ruleForm.thresholdValue),
      severity: ruleForm.severity,
      channels: ruleForm.channelSelection.length ? ruleForm.channelSelection.join(',') : 'record',
      dedupWindowSeconds: Number(ruleForm.dedupWindowSeconds),
      enabled: ruleForm.enabledBool ? 1 : 0,
    }
    try {
      if (ruleForm.id === null) {
        await gatewayApi.adminCreateAlertRule(payload)
        message.success('规则已创建（worker 60s 内热生效）')
      } else {
        await gatewayApi.adminUpdateAlertRule(ruleForm.id, payload)
        message.success('规则已更新')
      }
      setRuleForm(EMPTY_RULE_FORM)
      await loadRules()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function toggleRule(row: AlertRuleView) {
    try {
      await gatewayApi.adminUpdateAlertRuleStatus(row.id, row.enabled === 1 ? 0 : 1)
      await loadRules()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function removeRule(row: AlertRuleView) {
    const note = await prompt(`删除规则「${row.ruleName}」？`, '删除后 worker 下一次拉取即失效')
    if (note === null) return
    try {
      await gatewayApi.adminDeleteAlertRule(row.id)
      message.success('已删除')
      await loadRules()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function openAck(row: AlertRecordView) {
    const note = await prompt(`认领告警 #${row.id}`, '处理说明（可空）')
    if (note === null) return
    try {
      await gatewayApi.adminAckAlertRecord(row.id, note || undefined)
      message.success('已认领')
      await loadRecords()
      await loadPendingCount()
    } catch { /* 取消 */ }
  }

  const ruleColumns: ColumnsType<AlertRuleView> = [
    { title: '规则', dataIndex: 'ruleName', width: 150 },
    { title: '编码', dataIndex: 'ruleCode', width: 150, className: 'mono' },
    { title: '类型', width: 160, render: (_, row) => ruleTypeText(row.ruleType) },
    {
      title: '限定 Skill', width: 120,
      render: (_, row) => row.skillCode ?? '全部',
    },
    { title: '阈值', dataIndex: 'thresholdValue', width: 80, align: 'right' },
    {
      title: '级别', width: 100,
      render: (_, row) => (
        <Tag color={row.severity === 'CRITICAL' ? 'error'
          : row.severity === 'INFO' ? 'default' : 'warning'} style={{ margin: 0 }}>
          {row.severity}
        </Tag>
      ),
    },
    { title: '通道', dataIndex: 'channels', width: 130 },
    {
      title: '启用', width: 80,
      render: (_, row) => (
        <Switch checked={row.enabled === 1} disabled={!canManage} size="small"
                data-testid="rule-toggle" onChange={() => toggleRule(row)} />
      ),
    },
    ...(canManage ? [{
      title: '操作', width: 130,
      render: (_: unknown, row: AlertRuleView) => (
        <Space size={4}>
          <Button type="link" size="small" data-testid={`edit-${row.ruleCode}`}
                  onClick={() => openEdit(row)}>编辑</Button>
          <Button type="link" danger size="small" onClick={() => removeRule(row)}>删除</Button>
        </Space>
      ),
    }] : []),
  ]

  const recordColumns: ColumnsType<AlertRecordView> = [
    { title: '#', dataIndex: 'id', width: 60 },
    {
      title: '级别', width: 100,
      render: (_, row) => (
        <Tag color={row.severity === 'CRITICAL' ? 'error'
          : row.severity === 'INFO' ? 'default' : 'warning'} style={{ margin: 0 }}>
          {row.severity}
        </Tag>
      ),
    },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    { title: '规则', dataIndex: 'ruleCode', width: 150, className: 'mono' },
    { title: 'Skill', dataIndex: 'skillCode', width: 130 },
    { title: '任务', dataIndex: 'taskId', width: 220, ellipsis: true, className: 'mono' },
    { title: '最近触发', dataIndex: 'lastSeenAt', width: 160 },
    {
      title: '状态', width: 90,
      render: (_, row) => (
        <Tag color={row.status === 'PENDING' ? 'error' : 'success'} style={{ margin: 0 }}>
          {row.status === 'PENDING' ? '待处理' : row.status === 'ACKED' ? '已认领' : row.status}
        </Tag>
      ),
    },
    ...(canManage ? [{
      title: '操作', width: 160,
      render: (_: unknown, row: AlertRecordView) => (
        <Space size={4}>
          {row.status === 'PENDING' && (
            <Button type="link" size="small" data-testid={`ack-${row.id}`}
                    onClick={() => openAck(row)}>认领</Button>
          )}
          <Button type="link" size="small" data-testid={`issue-${row.id}`}
                  onClick={() => createIssue({
                    type: 'ALERT', sourceId: String(row.id),
                    relatedTaskId: row.taskId ?? undefined,
                    tenantId: row.tenantId ?? undefined,
                    skillCode: row.skillCode ?? undefined,
                    title: row.title, description: row.content ?? '',
                    severity: row.severity,
                  })}>转工单</Button>
        </Space>
      ),
    }] : []),
  ]

  return (
    <div>
      {promptContextHolder}
      {createIssueContextHolder}
      <div className="page-header">
        <div>
          <h2>告警中心</h2>
          <p className="sub">告警规则（worker 60s 热生效）+ 告警记录认领</p>
        </div>
        <Space style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          {pendingCount > 0
            ? (
              <Badge count={pendingCount} data-testid="pending-badge">
                <Tag color="error" style={{ margin: 0 }}>待处理告警</Tag>
              </Badge>
            )
            : <Tag color="success" style={{ margin: 0 }}>无待处理告警</Tag>}
          {canManage && (
            <Button type="primary" data-testid="create-rule" onClick={openCreate}>新建规则</Button>
          )}
        </Space>
      </div>

      <Card title="告警规则">
        <Table size="small" rowKey="id" columns={ruleColumns} dataSource={rules}
               loading={rulesLoading} pagination={false} data-testid="alert-rules-table" />
      </Card>

      <Card title="告警记录" style={{ marginTop: 16 }}>
        <Space style={{ marginBottom: 12 }} wrap>
          <Select value={recordQuery.status || undefined} size="small" style={{ width: 120 }}
                  data-testid="record-status"
                  onChange={(v) => setRecordQuery((prev) => ({ ...prev, status: v ?? '' }))}
                  options={[
                    { value: 'PENDING', label: '待处理' },
                    { value: 'ACKED', label: '已认领' },
                    { value: '', label: '全部' },
                  ]} />
          <Input size="small" value={recordQuery.taskId} placeholder="按 taskId 过滤" allowClear
                 style={{ width: 220 }}
                 onChange={(e) => setRecordQuery((prev) => ({ ...prev, taskId: e.target.value }))}
                 onPressEnter={loadRecords} />
          <Button size="small" data-testid="record-refresh" onClick={loadRecords}>刷新</Button>
        </Space>
        <Table size="small" rowKey="id" columns={recordColumns} dataSource={records}
               loading={recordsLoading}
               pagination={{
                 current: recordQuery.pageNo, pageSize: recordQuery.pageSize,
                 total: recordTotal, showSizeChanger: false,
                 onChange: (pageNo, pageSize) => {
                   setRecordQuery((prev) => ({ ...prev, pageNo, pageSize }))
                   loadRecords()
                 },
               }}
               data-testid="alert-records-table" />
      </Card>

      <Modal
        open={ruleForm.open}
        title={ruleForm.id === null ? '新建告警规则' : '编辑告警规则'}
        width={560}
        onCancel={() => setRuleForm(EMPTY_RULE_FORM)}
        footer={null}
      >
        <Form labelCol={{ style: { width: 110 } }}>
          <Form.Item label="规则编码" required>
            <Input data-testid="rule-code" value={ruleForm.ruleCode} disabled={ruleForm.id !== null}
                   placeholder="如 trace-demo-timeout"
                   onChange={(e) => setRuleForm({ ...ruleForm, ruleCode: e.target.value })} />
          </Form.Item>
          <Form.Item label="规则名称" required>
            <Input data-testid="rule-name" value={ruleForm.ruleName}
                   onChange={(e) => setRuleForm({ ...ruleForm, ruleName: e.target.value })} />
          </Form.Item>
          <Form.Item label="规则类型" required>
            <Select data-testid="rule-type" style={{ width: '100%' }} value={ruleForm.ruleType}
                    onChange={(v) => setRuleForm({ ...ruleForm, ruleType: v })}
                    options={RULE_TYPES} />
          </Form.Item>
          <Form.Item label="限定 Skill">
            <Input value={ruleForm.skillCode} placeholder="留空 = 全部 Skill"
                   onChange={(e) => setRuleForm({ ...ruleForm, skillCode: e.target.value })} />
          </Form.Item>
          <Form.Item label="阈值" required>
            <InputNumber data-testid="rule-threshold" min={0} value={ruleForm.thresholdValue}
                         onChange={(v) => setRuleForm({ ...ruleForm, thresholdValue: v ?? 0 })} />
            <span className="upload-hint" style={{ marginLeft: 8 }}>
              单位随类型：秒 / token 数 / 次数
            </span>
          </Form.Item>
          <Form.Item label="级别">
            <Select data-testid="rule-severity" style={{ width: 140 }} value={ruleForm.severity}
                    onChange={(v) => setRuleForm({ ...ruleForm, severity: v })}
                    options={[
                      { value: 'INFO', label: 'INFO' },
                      { value: 'WARN', label: 'WARN' },
                      { value: 'CRITICAL', label: 'CRITICAL' },
                    ]} />
          </Form.Item>
          <Form.Item label="通知通道">
            <Select
              mode="multiple"
              style={{ width: 240 }}
              value={ruleForm.channelSelection}
              onChange={(v) => setRuleForm({ ...ruleForm, channelSelection: v })}
              options={[
                { value: 'feishu', label: '飞书 webhook' },
                { value: 'record', label: '仅记录（告警中心）' },
              ]}
            />
          </Form.Item>
          <Form.Item label="去重窗口">
            <InputNumber min={60} step={60} value={ruleForm.dedupWindowSeconds}
                         onChange={(v) => setRuleForm({ ...ruleForm, dedupWindowSeconds: v ?? 86400 })} />
            <span className="upload-hint" style={{ marginLeft: 8 }}>
              秒；同任务同规则窗口内只告警一次
            </span>
          </Form.Item>
          <Form.Item label="启用">
            <Switch checked={ruleForm.enabledBool} data-testid="rule-enabled"
                    onChange={(v) => setRuleForm({ ...ruleForm, enabledBool: v })} />
          </Form.Item>
          <Button type="primary" data-testid="rule-save" onClick={saveRule}>保存</Button>
        </Form>
      </Modal>
    </div>
  )
}
