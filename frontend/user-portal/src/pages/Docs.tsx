import { Card, Table } from 'antd'

/** 接入指南（公开页，无需登录）。 */
export default function DocsPage() {
  const errorCodes = [
    { code: 0, meaning: '成功' },
    { code: 40001, meaning: '参数校验失败' },
    { code: 40101, meaning: '鉴权失败（AppKey 无效 / 请求签名错误）' },
    { code: 40201, meaning: '点数余额不足（含 rechargeUrl 引导充值）' },
    { code: 42901, meaning: '限流 / 超配额' },
    { code: 40401, meaning: '资源不存在（taskId / Skill / 版本不可用）' },
  ]

  return (
    <div>
      <div className="crumb">接入 / 文档</div>
      <h1 className="title">接入指南</h1>
      <p className="subtitle">三步接入：签发 Key → 提交执行 → 获取结果（轮询或回调）</p>

      <Card title="1. 提交执行" style={{ marginBottom: 14 }}>
        <pre className="code-block">{`POST /api/v1/execute
X-Skill-AppKey: sk-xxx
X-Skill-Timestamp: 1700000000000
X-Skill-Signature: <hex>
Content-Type: application/json

{"skillCode":"trace-demo","instructions":"..."}`}</pre>
      </Card>

      <Card title="2. 获取结果" style={{ marginBottom: 14 }}>
        <pre className="code-block">{`GET /api/v1/executions/{taskId}
（终态响应 = 完整结果体：status / artifacts / billing / error）`}</pre>
      </Card>

      <Card title="3. 请求签名算法" style={{ marginBottom: 14 }}>
        <pre className="code-block">{`stringToSign = [
  appKey,
  timestamp,            // 毫秒，容差 ±5min
  METHOD,               // 大写
  path[?query],
  sha256Hex(body)       // 空 body = 空字节串
].join('\\n')

signature = hex( HMAC-SHA256( appSecret, stringToSign ) )`}</pre>
      </Card>

      <Card title="错误码">
        <Table
          size="small"
          rowKey="code"
          pagination={false}
          data-testid="error-code-table"
          columns={[
            { title: 'code', dataIndex: 'code', width: 100 },
            { title: '含义', dataIndex: 'meaning' },
          ]}
          dataSource={errorCodes}
        />
      </Card>
    </div>
  )
}
