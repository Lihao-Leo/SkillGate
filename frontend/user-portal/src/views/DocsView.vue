<script setup lang="ts">
const errorCodes = [
  { code: '0', meaning: '成功' },
  { code: '40001', meaning: '参数错误' },
  { code: '40101', meaning: '鉴权失败（AppKey / 签名 / 时间戳超窗 ±5min）' },
  { code: '40201', meaning: '点数不足（余额/冻结失败）' },
  { code: '42901', meaning: '限流（QPS / 并发 / 日调用量）' },
  { code: '40401', meaning: '资源不存在' },
]
</script>

<template>
  <div class="hero">
    <h1>文档中心</h1>
    <p>接入指南 · 请求签名 · 轮询用法 · 错误码</p>
  </div>

  <div class="panel-card">
    <h3 class="panel-title">接入指南</h3>
    <ol class="steps">
      <li>充值获得 AppKey + AppSecret（首次购买自动签发，Secret 仅一次展示）</li>
      <li>调用 <code>POST /api/v1/execute</code> 提交任务（HMAC 请求签名，见下）</li>
      <li>轮询 <code>GET /api/v1/executions/&#123;taskId&#125;</code> 或配置 callbackUrl 接收回调</li>
    </ol>

    <h3 class="panel-title" style="margin-top: 24px">请求签名（五段 \n 分隔）</h3>
    <pre class="code">stringToSign = appKey + "\n" + timestamp + "\n" + METHOD + "\n" + path[?query] + "\n" + sha256Hex(body)
signature    = HMAC-SHA256(appSecret, stringToSign)  // hex

// Header
X-Skill-AppKey / X-Skill-Timestamp（毫秒，±5min 容差）/ X-Skill-Signature</pre>

    <h3 class="panel-title" style="margin-top: 24px">轮询客户端用法</h3>
    <pre class="code">// 终态：SUCCEEDED / FAILED / CANCELLED；非终态带 progress
while (!['SUCCEEDED','FAILED','CANCELLED'].includes(view.status)) {
  await sleep(2000)
  view = await get(`/api/v1/executions/${taskId}`)
}</pre>

    <h3 class="panel-title" style="margin-top: 24px">错误码表</h3>
    <el-table :data="errorCodes" size="small" data-testid="error-code-table">
      <el-table-column prop="code" label="code" width="120" class-name="mono" />
      <el-table-column prop="meaning" label="含义" />
    </el-table>
  </div>
</template>

<style scoped>
.steps { line-height: 2; color: #374151; }
.steps code { background: #f1f1ff; color: var(--el-color-primary); padding: 2px 6px; border-radius: 6px; font-size: 13px; }
.code {
  background: #171a23; color: #e5e7eb;
  padding: 16px; border-radius: 12px; overflow-x: auto;
  font-size: 12.5px; line-height: 1.7; margin: 0;
}
</style>
