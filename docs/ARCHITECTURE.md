> **SkillGate 平台技术方案**（内部立项名「Skill 执行平台」；v7.0 与代码实现逐节对齐）

# Skill 执行平台\-技术方案

|项|内容|
|---|---|
|版本|v7\.0（与代码实现对齐：模型网关自研替代 LiteLLM · FREE 0 点可执行 · AGENT 包类型 · 管理面接口补全 · FREE 埋点与 package 分发保留）|
|日期|2026\-09\-17|
|核心模型|**一次请求 = 一个 Skill = 一个 taskId**；统一 async，结果获取双通道（HMAC 回调 / 主动轮询）；Skill 纯黑盒 \+ 沙箱隔离；点数计费前置（预校验\-冻结\-结算）；输入只有 materials \+ instructions|

## 1\. 项目背景

平台定位：**开放的多租户 Skill 执行平台**。Skill 提供方（内部团队或第三方）将 Skill 包上传上架——Skill 前后端拆分：后端是本平台的执行 API，前端是从元数据自动生成的「调用说明」（导出 Markdown / OpenAPI / tool schema，用于 agent 市场展示）；调用方在充值平台购买点数获得 AppKey（apikey），凭 key 调用执行接口。

**两类对接形态**：① 服务端接口对接——调用方后端直接调 API，async 回调 / 轮询为主；② agent 平台内调用——如上架腾讯 WorkBuddy SkillHub：agent 读调用说明，统一异步执行——agent 用前端脚本轮询（4s 间隔）或等回调拿结果，凭 context 续跑会话。

**平台职责**：Skill 生命周期与市场 · 点数计费（预校验\-冻结\-结算闭环）· 沙箱隔离执行 · 模型能力供给（平台模型网关回退链 / RAG / 视频生成）· 产物交付（OSS \+ 预签名 URL \+ HMAC 签名回调）。

## 2\. 设计原则

1. **一次请求 = 一个 Skill**

2. **Skill 纯黑盒**：平台不解析内部逻辑，不校验参数

3. **两层输入**：materials（素材）\+ instructions（指令），就这两个字段

4. **版本化不覆盖**：复现锚点 = 包 sha256 \+ 沙箱镜像 digest

5. **凭据平台管**：模型 key 由平台下发，永不落 Skill 包；OSS 凭据不出平台，对外一律预签名 URL

6. **计费前置**：先预校验 \+ 冻结、后执行、按实际结算，失败即退（预冻结\-结算\-对账闭环）

7. **不可信代码沙箱执行**：每任务独立 K8s Job，网络出口受控

8. **结果获取双通道**：统一 async（taskId），回调（HMAC 签名）与主动轮询任选——无回调能力的 agent 平台（如 WorkBuddy）走轮询

## 3\. 系统分层架构

> **服务定名（v6\.0）**：平台整体称 **Skill 执行平台（skill\-platform）**；四个后端服务为 **skill\-gateway**（Java，接入与管理）、**billing\-service**（Java，计费，独立部署）、**skill\-worker**（Python，沙箱执行编排）、**recharge\-service**（Java，充值平台：订单 / 支付渠道 / C 端用户，独立部署独立库，见 §10\.4）；两个前端应用为 **skill\-admin\-web**（管理后台：Vue 3 \+ Element Plus，PC，经 gateway 管理面 API）与 **recharge\-web**（充值用户端：Vue 3，PC Web 响应式，经 recharge\-service BFF）——前端均为静态部署（Nginx / CDN），与后端独立发布。K8s namespace：`skill-platform`；MQ Topic：`skill-execute` / `skill-settle` / `skill-callback-retry` / `skill-callback-dlq`；数据库：`skill_platform` 与 `skill_billing` 两库。
> 
> **选型 RocketMQ**：延迟消息原生支持回调退避重试（1/5/15min 定时等级）、内置 DLQ（%DLQ%消费组）、事务消息可作受理侧 Outbox 兜底（V1 用 PENDING 看门狗替代，事务消息留作演进）；消费语义 at\-least\-once，全链路靠幂等键消化重复（接手条件更新 / hold 状态 / taskId）。
> 
> （v7\.0 注）代码仓库对应目录：skill\-admin\-web = frontend/admin\-console，recharge\-web = frontend/user\-portal。
> 
> 

### 3\.1 接入与管理服务（skill\-gateway，Java）

> 📊 整体架构图（可编辑画板，已按 v6\.1 重绘：结果双通道，无 sync）
> 
> 

**接入面**（每次 /execute 过五关）：① AppKey 鉴权（HMAC 请求签名）→ ② Redis 集中限流与配额（AppKey QPS / 租户并发 RUNNING 上限 / 日调用量；并发计数漂移由每 1min 修复任务按 DB 真值重置）→ ③ 计费预校验 \+ 冻结（同步调 billing\-service，余额不足快速失败 40201）→ ④ 调度（入 MQ）→ ⑤ 返回 taskId（结果经回调或轮询获取）。

**管理面**：Skill 上传校验与审核（含 CODE/AGENT 包类型识别、版本审核流转）、市场与可见性（visibility 上下架）、版本与定价（output\_config / pricing\_config 运营配置即时生效）、invocation\_spec 导出、素材上传与 presign 签发、执行查询 / 取消 / 列表 / 失败重试、死信回调重发与放弃处理台、监控看板指标、模型路由与 KB 管理、系统配置（DB 覆盖配额默认值）、AppKey 与账户管理（充值平台对接）。

### 3\.2 计费服务（billing\-service，Java，独立部署）

钱的事独立部署、独立审计：点数账户（key 即账户）、预校验、冻结 / 结算 / 退款、不可变流水、对账兜底（FROZEN 超 24h 未结算自动释放）、成本 × 收入归因报表。与 gateway 同步交互（预校验 / 冻结），与 worker 的结算为同步 RPC 主路径（`skill-settle` 仅作兜底重试通道，按 taskId 幂等）。**边界**：billing 只碰账户与流水，永不对接支付渠道——充值经独立 recharge\-service（§10\.4）以 orderNo 幂等入账。

### 3\.3 MQ（RocketMQ）

- `skill-execute`：执行消息（gateway → worker scheduler）

- `skill-settle`：结算兜底重试（settle RPC 失败时 worker → billing，at\-least\-once，按 taskId 幂等）

- `skill-callback-retry` / `skill-callback-dlq`：回调重试（退避 1 / 5 / 15min）与死信

### 3\.4 执行服务（skill\-worker，Python）

**scheduler**（Deployment 常驻，沙箱编排器）：消费 `skill-execute` → 创建 K8s Job（沙箱）→ activeDeadlineSeconds = timeoutSeconds 超时控制 → watch 终态 → 采集 `artifacts/` 上传 OSS → 回调（HMAC）→ 发结算事件。

**executor**（每任务一个 K8s Job）：scheduler 从 OSS 拉包 → sha256 校验 → 解压到共享 PVC 工作区 → 运行 Skill 入口（黑盒；CODE=python main\.py / AGENT=平台 agent runner，§4\.7）。**v7\.0 对齐**：按 package\_sha256 的解压缓存与 pip wheelhouse 缓存未实施（每任务独立拉包解压），为冷启动演进项。产物按 §4\.7 契约写 artifacts/ 目录。

**toolset**（Job 内注入）：LLM 调用（经平台模型网关，env 重定向零改动；toolset 封装含 usage 自动上报与单任务调用上限硬闸）、`video_gen`（特殊协议提交 / 轮询 / 取结果，toolset 自实现回退）、`kb_query`（Milvus RAG 检索）、调用计数与 usage 上报（METERED 结算依据 \+ 单任务调用上限硬闸）。

**scheduler 编排主循环伪代码**（顺序不变量：先落产物 → 再结算 → 最后置终态）：

```python
while msg := mq.consume("skill-execute"):              # scheduler 常驻 Deployment
    exec = load_execution(msg.task_id)
    if exec is None: continue                          # 受理侧已回滚，忽略残留消息
    if exec.status != "PENDING": continue              # 幂等：已接手/已取消跳过
    n = update_execution(task_id, status=RUNNING,
                         where=status=PENDING)          # 条件更新防双消费
    if n != 1: continue                                 #   另一副本已接手

    job = k8s.create_job(sandbox_spec(exec))           # activeDeadlineSeconds=timeoutSeconds
    result = watch_until_done(job)                     # 期间轮询 CANCELLING 标记
    if exec.status == "CANCELLING":                    #   用户取消 → 杀 Job 走失败路径
        k8s.delete_job(job); result = Cancelled()

    files = collect_artifacts(job.workdir)             # artifacts/，manifest 优先
    if result.ok and not files:
        result = Failed("EMPTY_OUTPUT")                #   空产物 → FAILED 全额退款
    if over_limit(files):                              #   单文件/总量/数量上限
        result = Failed("OUTPUT_LIMIT")                #   超限 → FAILED（退款语义不变）

    upload_oss(files)                                   # 1) 先落产物（{tenantId}/artifacts/{taskId}/）
    insert_artifacts(exec.task_id, files)
    charged = billing.settle_rpc(exec.task_id,         # 2) 同步结算；失败立即重试 1 次
                  actual=min(len(files), exec.count))    #    超产按 min 计费
    settled = charged is not None
    if not settled:                                    #    双次失败 → MQ 兜底重试
        mq.send("skill-settle", exec.task_id)
        charged = estimate(exec)                       #    预估值随回调（settled=false）
    update_execution(exec.task_id, result)             # 3) 最后置终态【无完成无结果竞态】
    if exec.callback_url:
        mq.send("skill-callback-retry",
                callback(exec, charged, settled))       #    billing.settled 见 §5.5
```

### 3\.5 存储

MySQL（`skill_platform` / `skill_billing` 两库）· OSS（Skill 包 \+ 素材 \+ 产物，全私有桶，对外一律预签名）· Redis（限流 / 配额 / 并发计数 / usage / progress / 轮询状态缓存：终态查询 TTL 2s，miss 回源 DB）· **平台模型网关**（skill\-gateway 内置 OpenAI 兼容转发，替代原 LiteLLM 组件，路由即 model\_provider 表配置，§4\.5）· Milvus（RAG 向量）· Langfuse（自建，LLM 调用链观测——**v7\.0 对齐：未实施**，原经 LiteLLM callback 接入的方案随 LiteLLM 一并移除，列为演进项）。

### 3\.6 多租户隔离矩阵

**两级主体模型**：租户 = 授权与对账主体；AppKey = 账户与配额主体（一租户可 N key，key 即点数账户）。

|层|隔离机制|粒度|演进口径|
|---|---|---|---|
|身份|AppKey→租户推导；回调域名白名单按 key|key|—|
|限流配额|QPS / 日配额按 key（账户属性）；**并发 RUNNING 按租户聚合**（Redis 租户级计数器，防多 key 拆分绕过；漂移由每 1min 修复任务按 DB 真值重置）|key / 租户|—|
|OSS|**逻辑隔离**：同桶 \{tenantId\}/ 前缀 \+ 全私有 \+ 预签名；IAM 可按前缀授权|前缀|大租户物理分桶|
|MySQL|表内 tenant\_id 列 \+ 全查询经 AppKey 推导租户条件；唯一键均含租户维度|行级|大租户分库|
|Milvus|collection 按租户前缀——**已是物理隔离**|collection|—|
|Redis|key 按 appKey / taskId 天然隔离，无租户共享数据|key|—|
|K8s 沙箱|节点池不按租户分（V1 信任模型见 §4\.6）；租户间靠容器边界|Job|强需求时按租户节点池|
|billing 对账|账户=key（扣款逻辑只看 key）；租户级对账=聚合其下全部 key 流水（报表维度）|key / 租户|—|

**结论**：V1 全部逻辑隔离（Milvus 除外）；物理分桶 / 分库 / 按租户节点池为大租户或强合规需求的演进项，不进本期。

---

## 4\. 核心模块设计

### 4\.1 Skill 生命周期

> 📊 **Skill 上传与版本管理时序**（可编辑画板，已按 v6\.0 重绘）
> 
> 

**上传**：skillCode、version、zip 包、描述、required\_abilities、**output\_config**、**pricing\_config**、**invocation\_spec**、publish（是否直接发布）→ 校验（路径穿越 / 包大小 ≤200MB / 包内文件数 ≤20000 / 结构与入口）→ **包类型识别（v7\.0）**：根入口 main\.py → CODE；SKILL\.md → AGENT（同 skillCode 禁止混型上传）；单顶层目录自动剥壳（兼容右键压缩形态，顺带过滤 \_\_MACOSX / \.DS\_Store）→ 平台审核（V1 提供方=运营代传：Skill 为内部业务资产经管理后台上传，无第三方自助；上传可直接 publish，版本状态机 0 待审核 → 1 已发布 / → 2 已废弃（终态）经版本审核接口流转）→ OSS `skill/{tenantId}/{skillCode}/{version}/skill.zip`（永不覆盖）→ MySQL 元数据。

**output\_config（产出能力声明，运营性配置）**：

```json
{ "countable": true, "defaultCount": 1, "maxCount": 20, "timeoutSeconds": 600 }
```

- `countable / defaultCount / maxCount`：数量控制语义见 §4\.4

- `timeoutSeconds`：本 Skill 单次执行超时，不传默认 600s，**全局硬顶 3600s**（Job activeDeadlineSeconds 同源）

**pricing\_config（定价，运营性配置）**，详见 §4\.8：

```json
{ "mode": "PER_EXECUTION", "points": 10 }    // 按次：单价/个（默认）
{ "mode": "METERED", "capPoints": 500 }        // 按量：实际用量折算，封顶
{ "mode": "FREE" }                             // 免费（v7.0）：0 点平台执行（不冻结不结算）；亦可经 package 端点公开分发（埋点采集 IO，§4.8）
```

**visibility（市场可见性）**：`PRIVATE` = 仅本租户；`PUBLIC` = 全平台市场可见（需审核通过）。

**invocation\_spec（调用说明元数据）**：素材要求、instructions 写法建议、典型时长、计费说明、回调契约——管理后台一键导出 Markdown / OpenAPI / function\-calling tool schema，作为「前后端拆分」的前端部分用于 agent 市场上架（如 WorkBuddy），说明与真实 API 同源不漂移。

**更新**：新版本新路径；不传 version 用默认版；改标记即回滚。**运营配置修改**：output\_config / pricing\_config 经 `PUT /api/v1/skills/{skillCode}/config` 即时生效、无需重传包（Skill 本体改动仍走新版本上传）。**执行时将 output\_config 与 pricing\_config 快照记入执行记录**——历史执行可解释、计费可复算（改价不影响进行中任务）。

**灰度口径**：V1 **不做版本级流量灰度**——新版本 = 改 default\_version 全量切换，出问题靠「失败自动退款 \+ Job 成功率监控（\<90% 日告警）\+ 秒级回滚（改标记）」兜底；任务型流量（日万级）爆炸半径可控，灰度收益低。**路由钩子已预留**：execute 显式传 version 可自选试跑；演进加 skill\.canary\_config（\{version, percent \| appKeys\[\]\}），gateway 版本解析处集中分流，执行记录 resolvedVersion 逐任务可追溯——不动 worker / billing。触发条件：开放第三方上传或单 Skill 日量 \>5 万。镜像灰度同理（sandbox\_digest 已逐任务记录，演进加按 tag 节点池 canary）。

### 4\.2 执行模型

> 📊 执行时序（可编辑画板，已按 v6\.1 重绘：终态双通道 ①回调/②轮询）
> 
> 

```
调用方 ──POST /execute──→ 网关：鉴权 → 限流/配额 → 计费预校验+冻结（billing）
        ←── {taskId, status:PENDING}
              → MQ(skill-execute) → scheduler → K8s Job 沙箱执行
                                        → 先落产物（artifacts/ → OSS）与结算，再置终态
                                        → 结算（同步 RPC 按实际；RPC 失败走 MQ skill-settle 兜底）

终态结果获取双通道（任选其一）：
  ① 回调：scheduler ──HMAC POST──→ callbackUrl（支持回调的服务端调用方）
  ② 轮询：GET /executions/{taskId}（建议 4s 间隔；WorkBuddy 等无回调能力的 agent 平台）
```

**幂等**：请求可带 `clientRequestId`（同 AppKey 下唯一），重复请求返回原 taskId 不重复执行；**回调可能重复投递**（重试导致），调用方须按 taskId 幂等处理。

**context 透传**：请求可带 ≤4KB 任意 JSON `context`，回调与终态轮询响应原样带回——agent 场景传会话标识，拿到结果后续跑会话。

**gateway 受理伪代码**（五关顺序，不变量已标注）：

```python
def execute(req):                                       # 受理五关（§4.2；另有第零关：版本包对象必须在 OSS 存在，防存储清理后任务进 MQ 才失败）
    app_key = verify_hmac(req)                          # ① 鉴权 → 40101
    check_quota(app_key)                                # ② 限流/配额 → 42901
    skill = load_skill(req.skillCode)                   #    默认版本 + output/pricing 快照 + 包存在校验
    count = resolve_count(req.count, skill)             #    → 40006
    check_len(req.instructions, 32 * 1024)              #    instructions ≤32KB（context ≤4KB 同理）→ 40001
    if skill.pricing.mode == "FREE":                    #    FREE=0 点受理（v7.0）：不校验定价、不冻结，
        pass                                            #    amount=0 → hold_id=None，正常进 MQ（不再返回 40007）
    for m in req.materials:
        if m.is_oss_url: check_owner(m, app_key.tenant)  #    素材归属校验 → 40301
                                                        #    （防跨租户引用）

    hit = find_execution(app_key, req.clientRequestId)  #    幂等命中【先于冻结】
    if hit: return hit.taskId

    amount = pricing_amount(skill, count)               # ③ FREE 恒为 0
    hold_id = billing.freeze(app_key, amount) if amount > 0 else None   # → 40201
    try:
        task_id = gen_task_id()
        insert_execution(task_id, PENDING, snapshots, hold_id, ...)    # ④
    except UniqueConflict:                              #    并发同幂等键：
        billing.release(hold_id)                        #    【败者立即释放，不等对账】
        return find_execution(app_key, req.clientRequestId).task_id
    try:
        mq.send("skill-execute", task_id)
    except MQFailure:                                   #    可感知失败：当场回滚
        delete_execution(task_id); billing.release(hold_id)
        raise ApiError(50001, "请重试")
    return task_id                                      # ⑤ 终态经回调/轮询获取
```

### 4\.3 输入设计（两层）

```json
{
  "materials": [
    { "type": "video", "url": "oss://tenant-a/materials/mat_001.mp4" },
    { "type": "text", "content": "品牌要求：突出防晒霜轻薄不闷痘" }
  ],
  "instructions": "重点关注开场钩子，生成 vlog 风格短视频，控制在60秒内"
}
```

|层|平台处理|
|---|---|
|`materials`|黑盒透传|
|`instructions`|黑盒透传|

**没有 params、没有 Schema 校验**——Skill 是 AI 驱动的，instructions 里的自然语言它自己理解。长度上限（v7\.0 补录）：instructions ≤32KB、context ≤4KB，超限 40001。

**素材类型**（语义类型）：`video / image / audio / document / text / link / data`——其中 `text` 直接内联、`link` 为外部 URL 由 Skill 沙箱自行下载（均不落平台存储）；上传通道的 `other` 为存储兜底类型（格式无法识别时）。

> 其中 `text` 和 `link` 不需要走素材上传接口——`text` 直接内联 `content` 字段，`link` 是外部 URL 由 Skill 自行下载；只有需要上传二进制文件的类型（video/image/audio/document/data/other）才走 §5\.1 的上传接口。
> 
> 

**素材来源**：

|来源|写法|
|---|---|
|OSS（经 §5\.1 上传：multipart ≤100MB，或 presign 直传大文件）|`{"type": "video", "url": "oss://..."}`|
|内联文本|`{"type": "text", "content": "..."}`|
|外部链接|`{"type": "link", "url": "https://..."}`|

**素材归宿与 agent 场景**：素材统一落**平台 OSS 私有桶**，变的只是触发上传的角色——服务端调用方走 §5\.1 双通道；**agent 场景由前端脚本代上传**：用户文件在 agent 运行环境有本地路径，前端脚本用**用户的 AppKey** 完成 presign → 直传 OSS → confirm → execute（skill\-sdk / 前端模板内置 `upload_and_execute()` 封装，agent 平台无需替平台存任何素材）；用户丢链接则不落盘，`{"type":"link"}` 透传由 Skill 沙箱自行下载（沙箱出公网 80/443 即为此设计）。

### 4\.4 产出数量控制（count \+ override）

执行接口支持 `count` 字段控制本次产出数量（如生成类 Skill 默认出 1 个视频，传 `count: 10` 则一次执行产出 10 个）。**数量语义归 Skill 声明（output\_config），平台只做边界校验与透传，不承担产出兑现责任（黑盒不破）**：

|规则|行为|
|---|---|
|不传 count|用 Skill 的 defaultCount|
|传 count ≤ maxCount|直接生效|
|传 count \> maxCount|400 拒绝（提示上限）；显式传 `"override": true` 则放行（调用方自担成本/时长），执行记录标记 overridden|
|Skill 声明 countable=false 却传了 count|400 拒绝「该 Skill 不支持数量参数」（防静默忽略）|
|Skill 实际产出 \< count|回调如实报 `expectedCount` 与实际 artifacts 数量，短缺原因进说明——**平台不撒谎**|

兜底：即使 override 放行，toolset 的单任务模型调用上限仍然生效——真跑飞了有硬闸。

**计费联动**（PER\_EXECUTION）：冻结 = 单价 × count；结算 = 单价 × **实际产物数**（短缺不收；超产照常交付、按 min\(实际, count\) 计费；空产物 FAILED 全退）。METERED 不受 count 影响，按实际用量结算、封顶 capPoints。

### 4\.5 模型与能力供给（模型网关 · RAG · video\_gen）

任务启动时注入环境变量：

```bash
OPENAI_BASE_URL=http://skill-gateway.platform.svc/v1   # 平台模型网关（v7.0，原 LiteLLM 地址）
OPENAI_API_KEY={internal-token}-task-{taskId}          # 仅沙箱流量可用（网关按此鉴权）
SKILL_MODELS=llm-text,video-gen
```

**平台模型网关（v7\.0，替代 LiteLLM）**：skill\-gateway 内置 OpenAI 兼容转发 `POST /v1/chat/completions`（§5\.8）——按请求体 model=alias 实时查 model\_provider 表路由（API Key AES 解密后透传），上游 5xx / 连接失败按 fallback\_alias 逐级回退（代码内循环，防环），支持流式透传；鉴权仅限沙箱执行流量（Bearer \{internal\-token\}\-task\-\{taskId\}，worker 注入）。无外部网关组件：路由即 DB 配置、实时生效；代价为 spend 日志与 Langfuse 采集随之移除，usage 全靠 toolset 上报（见下）。

已用 OpenAI SDK 的 Skill **零改动**（SDK 自动读 `OPENAI_BASE_URL` 环境变量重定向到平台模型网关）。

**可选 ****`skill-sdk`**** 薄包**（`pip install skill-sdk`）：

- 不装：Skill 直接用 OpenAI SDK / requests 读环境变量调平台模型网关，完全够用

- 装了：额外获得 `video_gen(prompt, options)`（特殊协议提交 / 轮询 / 取结果）、`kb_query(kb, query, top_k)`（RAG 检索）、`report_progress(percent)`（进度上报）、`save_artifact(path, type)`（产物登记，即 §4\.7 目录约定的封装）；体验更好但非必需

**回退链**：`model_provider.fallback_alias` 声明备选——LLM 类由模型网关在转发层实现（上游 5xx / 不可达时切 fallback\_alias，最多 4 跳且防环）；`video_gen` 走特殊协议不经模型网关，由 toolset 读同一配置自行实现回退。Provider 故障 / 限流在适配层内部消化，Skill 只认 alias。

**观测与成本（v7\.0 改写）**：usage 以 toolset 上报为**唯一来源**（execution\.model\_calls / tokens\_used 数据源）；原「LiteLLM spend 日志归并、Langfuse 调用链采集」随 LiteLLM 移除而不再成立，Langfuse 列为演进项。model\_provider 仍维护成本单价字段（成本归因口径预留）——**METERED 折算当前使用 worker 全局默认单价**（env 可配），按 provider 成本 × 加成率折算为演进项。

**usage 采集链路（METERED 结算数据源）**：toolset **每次调用后增量上报**——Redis `INCR task:usage:{taskId}`（分类计数：llm\_calls / llm\_input\_tokens / llm\_output\_tokens / video\_calls），Job 终态由 scheduler 刷回 execution\.model\_calls / tokens\_used 后对 key `EXPIRE 24h`；Skill 被杀（TIMEOUT / BUDGET\_EXCEEDED）时按**最后已知值**结算。调用前硬闸：llm\_calls \+ video\_calls ≥ 上限（默认 500，env 可调）直接抛 BudgetExceeded。**降级口径**：Redis 故障/驱逐时上报降级为进程内（**接受少收**），不阻断 Skill。**progress 同通道**：toolset report\_progress → Redis `task:progress:{taskId}`（TTL 24h），scheduler watch 期间轮询刷回 DB，查询接口读 Redis、miss 回源 DB。

**video\_gen 与 execution 状态机联动**（两层轮询 / 两层超时 / 取消联动）：

```
外层（平台）  ：调用方 ──3-5s 轮询──→ GET /executions/{taskId}（读 progress / status）
内层（toolset↔供应商）：video_gen 提交 → 5s 间隔轮询 provider（指数退避至 30s）→ 取结果
                      ↑ 内层对调用方完全不可见，仅经 progress 字段外透
```

- **进度映射**：toolset 自动映射——提交=10%、provider 轮询中=10→90% 线性、取回=95%，Skill 零代码获得进度；count\>1 时 Skill 可 report\_progress\(完成数/总数\) 覆盖

- **超时分层**：单次 provider 调用超时（默认 300s，options\.timeout 可调）**\< execution\.timeoutSeconds**（外层总闸）。内层先到 → video\_gen 抛 TimeoutError 由 Skill 决定重试/降级/失败；外层到 → SIGTERM 杀 Job

- **取消联动**：Job 收 SIGTERM 时 toolset handler 尽力取消 provider 侧任务（best\-effort，止损供应商成本）

- **进度可见性契约**：调用方可见粒度 = 外层轮询间隔（3\-5s），不可能更细——写入 invocation\_spec 预期管理

### 4\.6 沙箱执行（不可信代码隔离）

- **每任务一个 K8s Job**：非 root、readOnlyRootFilesystem、CPU / 内存 limit、独立沙箱节点池（**v7\.0 对齐：节点池绑定未实施**，当前集群默认调度，租户间靠容器边界）

- **网络出口受控**：设计口径为放行公网 80/443（Skill 下载 link 素材）\+ 平台内部端点（模型网关 / OSS / toolset）、禁内网横向与云元数据——**v7\.0 对齐：独立节点池 / 独立子网 egress 未实施**（Job spec 无 nodeSelector 与专用网段），为开放第三方上传前的落地前置项

- **冷启动抵消（v7\.0 对齐：未实施）**：包解压缓存与 pip wheelhouse 走共享 PVC（key = package\_sha256 \+ 镜像 digest）为设计目标；当前每任务由 scheduler 从 OSS 拉包解压到 PVC 工作区，无缓存命中

- **超时联动**：activeDeadlineSeconds = output\_config\.timeoutSeconds，到点杀 Job 记 TIMEOUT

- **镜像矩阵**：按 required\_abilities 选执行镜像——base\-python（纯 LLM 类）/ \+ffmpeg（音视频处理）（\+ml 重依赖科学计算为演进）；镜像 tag 版本管理，执行记录 sandbox\_digest 可追溯。依赖安装由镜像内置环境与入口脚本承接（包内 requirements）

**实现技术栈**：

|维度|V1 定稿|演进|
|---|---|---|
|容器运行时|**runc \+ 加固**：非 root、readOnlyRootFilesystem、capabilities drop ALL、allowPrivilegeEscalation=false、seccomp RuntimeDefault|开放第三方上传后按风险等级切 gVisor RuntimeClass（syscall 慢 2\-10×，视频类先测）|
|egress 实现|非 NetworkPolicy（做不了「放公网禁内网」）——设计为**沙箱节点池独立子网**：不挂内网路由、只挂公网 NAT \+ 公共 DNS；平台端点（模型网关 / OSS）经专用 internal LB 暴露到该子网。**v7\.0 对齐：未实施**，当前依赖集群默认网络策略|按 skill 的域名白名单 egress proxy|
|超时 kill|activeDeadlineSeconds=timeoutSeconds → kubelet SIGTERM（**grace 10s**，Skill 可捕获清理/取消 provider 任务）→ SIGKILL；terminationGracePeriodSeconds: 10|—|
|资源档位|固定两档：标准 2C4G / 视频类 4C8G（required\_abilities 含 video 选大档）|skill 声明 resource\_class|

**信任模型**：V1 Skill 全部经运营审核上传（内部业务资产），威胁模型是**缺陷隔离**（失控/崩溃/OOM）而非恶意防御——runc \+ 加固即够；恶意防御（gVisor / 按租户节点池）为开放第三方上传后的要求。

### 4\.7 Skill 包契约（黑盒的入口与出口）

Skill 是**自包含程序**：代码 \+ 提示词模板都在包内，平台提供进程环境与模型供给，**不提供也不需要 agent 环境**——「agentic 与否」是 Skill 作者在黑盒内部的选择（脚本\+LLM / 自写 agent loop / 纯计算均合法，平台不预设）。提示词是包内静态资产（作者写死的模板与 few\-shot），调用方传的 instructions 是运行时注入模板槽位的变量——改提示词=改 Skill 本体=走新版本（§4\.1 分界线）。

```
skill.zip（CODE 型）
├── main.py                # 入口：沙箱 Job 固定执行 python main.py
├── prompts/
│   └── deconstruct.md     # 提示词模板（Skill 作者维护，含 few-shot）
├── requirements.txt       # 依赖
└── assets/                # 其他静态资源

skill.zip（AGENT 型，v7.0）
├── SKILL.md               # 指令入口：平台 agent runner 执行（包零改造）
├── references/  scripts/  # 知识与确定性脚本（可选）
└── main.py（可选）         # 经 templates/agent-skill-bridge 桥接入时由桥承接
```

```python
def main():
    inp = load_input()                          # ① 读 input.json（env SKILL_INPUT 指向）
    video = fetch(inp.materials[0])             #    素材：OSS 预签名 URL 下载 / link 自行抓取
    prompt = render("prompts/deconstruct.md",   # ② 提示词模板 + instructions 注入槽位
                    instructions=inp.instructions)
    result = llm.chat(prompt, images=frames)    # ③ 经平台模型网关（key 平台管）
    save_artifact("拆解报告.md", result)         # ④ 写 artifacts/（见下）
```

**入口与输入注入约定**：

- **入口**：`main.py`（CODE），Job 命令行固定执行，无参数；或 `SKILL.md`（AGENT，v7\.0），由平台 agent runner 执行（agent\-skill\-bridge 桥提供零改造接入）

- **输入注入**：平台把完整入参写入工作目录 `input.json`（env `SKILL_INPUT` 指向路径），含 materials（OSS 项已换预签名 URL）、instructions、context、count——**不走 stdin / 命令行参数**（长 JSON 踩长度限制与转义坑）

- **退出码**：0 = 正常结束（产物有无按下文空产物规则判定）；非 0 = 失败（进入失败结算路径）

- **skill\-sdk 封装**：以上约定封装为 `load_input() / save_artifact() / report_progress()`；不装 SDK 按裸约定读写文件同样成立

**AGENT 包类型（v7\.0 新增）**：`skill.kind = AGENT`（SKILL\.md 指令 \+ references/ 知识 \+ scripts/ 确定性脚本的 Claude/Cursor 生态技能包形态）。沙箱按包类型分流入口：CODE → `python main.py`；AGENT → 平台 agent runner（镜像内置）。平台提供标准桥 `templates/agent-skill-bridge`：拷入桥的 main\.py 即可让 SKILL\.md 技能零改造接入（load\_input → SKILL\.md\+references 组装提示词 → 模型网关调用（usage 自动上报）→ 生成 JSON（失败自动修复轮）→ skill\.json 声明的管线脚本渲染产物 → save\_artifact 登记）。

**产物（出口）契约**：

Skill 在工作目录 `artifacts/` 下写产物文件，可选 `artifacts/manifest.json` 声明类型与元数据；scheduler 于 Job 终态后扫描该目录，逐文件上传 OSS（`{tenantId}/artifacts/{taskId}/{filename}`）并落 artifact 表；manifest 缺失按后缀推断 type。`save_artifact()` SDK 方法即此约定的封装。

- **空产物 = FAILED**（`error_code=EMPTY_OUTPUT`）：进程 exit 0 但 artifacts/ 为空视为失败、全额退款——「成功但 0 产物」对调用方是误导

- **产物 URL**：回调 / 查询一律返回 24h 时效预签名 https URL，调用方零 OSS 凭据

- **产物上限**：单文件 ≤500MB、单任务总量 ≤2GB、文件数 ≤ maxCount×2（countable=false 的 Skill 无 maxCount，默认 ≤4）——防 OSS 滥用；超限置 FAILED（error\_code=OUTPUT\_LIMIT），退款语义同失败

### 4\.8 计费机制（点数：预冻结\-结算\-对账）

> 📊 **计费时序**（可编辑画板：受理冻结 → 终态结算/退款 → 对账兜底三条路径）
> 
> 

**账户与密钥**：充值平台（独立，对接支付渠道）支付成功 → 调平台签发接口入账并颁发 **AppKey（apikey，sk\- 前缀）\+ AppSecret**；AppSecret 同时用于请求 HMAC 签名与回调 HMAC 签名。**key 即账户**（V1）：一个 key 一个点数账户，扣点只看 key；多 key 共账后续加账户主体层。

|mode|定价|预校验 / 冻结|结算|
|---|---|---|---|
|`FREE`（0 点执行 \+ 公开分发，v7\.0）|0 点：可直接 execute（0 点受理，不冻结不结算）；亦可经 package 端点下载分发包在调用方环境执行|不冻结（amount=0，hold\_id=NULL）|恒 0 点（worker 防御口径）；旁路：FREE Skill 自动签发埋点令牌，调用方环境执行可上报 IO → skill\_io\_log，供价值分析择优转收费版|
|`PER_EXECUTION`（默认）|单价 points / 个|冻结 单价 × count|单价 × min\(实际产物数, count\)，超产不多收；失败全退|
|`METERED`|实际用量 × 折算单价（当前 worker 全局默认单价；按 provider 成本 × 加成率为演进）|冻结 capPoints|按 model\_calls / tokens 折算，封顶 capPoints；被杀按已发生用量|

**流水线**：预校验（不足快速失败 40201，不进队列）→ 冻结 hold（单行原子：balance → frozen）→ 执行 → 结算 settle（实际费用从 frozen 划扣、差额解冻）。**结算主路径为同步 RPC**：scheduler 落完产物后同步调 billing\.settle\(\)（单行事务毫秒级），拿到 pointsCharged 再置终态、再回调——**回调里的 billing 永远是确定值**；RPC 失败/超时降级发 MQ skill\-settle 兜底重试（按 taskId 幂等），对账兜底见下。

- FAILED / TIMEOUT / CANCELLED / EMPTY\_OUTPUT：PER\_EXECUTION 全额退；METERED 按已发生用量收（超调用上限被杀同理）

- 结算消息丢失兜底：**对账任务**扫 FROZEN 超 24h 未结算自动释放；billing\_transaction 不可变流水（HOLD / SETTLE / RELEASE / RECHARGE / ADJUST）供审计

- 回调携带 `billing: {mode, pointsCharged, holdId, settled}`：`settled=true` 为最终扣点；`false` 为预估（settle RPC 双次失败时），终值以 `GET /account/transactions` 对账为准；计费按执行时 pricing 快照复算，改价不影响进行中与历史任务

- **FREE 任务（v7\.0）**：受理 0 点不冻结（hold\_id=NULL），结算跳过（恒 0 点）；回调 / 查询 billing\.settled=true、pointsCharged=0。对账天然不会命中 FREE 任务（无 hold 记录）。

**billing 核心操作**（真实 SQL，非伪代码）：

```sql
-- 冻结 freeze(appKey, amount)：单行原子，天然防并发花超（无需分布式锁）；FREE 任务 amount=0 不调用
UPDATE credit_account SET balance = balance - :amt, frozen = frozen + :amt, version = version + 1
 WHERE app_key_id = :key AND balance >= :amt;             -- 影响行数=0 → 40201 余额不足
-- 同事务：insert billing_hold(FROZEN) + billing_transaction(HOLD)

-- 结算 settle(taskId, actual)：同步 RPC 主路径（失败立即重试 1 次）/ MQ 兜底，按 hold 状态幂等
SELECT * FROM billing_hold WHERE task_id = :task FOR UPDATE; -- status != FROZEN → 重复消息，直接 return
UPDATE credit_account SET frozen = frozen - :hold_amt, balance = balance + (:hold_amt - :actual)
 WHERE app_key_id = :key;                          -- actual = min(实际产物数, count)；失败场景 actual=0 全退
-- 同事务：hold → SETTLED（失败 → RELEASED）+ 流水(SETTLE / RELEASE)

-- 对账兜底（每 5min）：先查任务为何未结算，再动钱（v7.0 对齐：三种成因三条路径）
-- 前提：skill_platform 与 skill_billing 为同 MySQL 实例分库；分实例演进时改为 billing 调 gateway 查询 API
SELECT h.hold_id, e.status FROM billing_hold h
  JOIN skill_platform.execution e ON e.task_id = h.task_id
 WHERE h.status = 'FROZEN' AND h.created_at < NOW() - INTERVAL 24 HOUR;
-- ① e 已终态 → 结算消息丢失：按规则补 settle / release + INFO 告警
--    （PER_EXECUTION 按 pricing 快照复算 = 单价 × min(实际产物数, expectedCount)；
--      METERED 对账无 usage 数据源 → 全额释放 + WARN 告警，宁可少收人工复核）
-- ② e 非终态（PENDING/RUNNING/CANCELLING）→ 任务从未执行：
--    先置 execution CANCELLED（消息再到达会被 status!=PENDING 挡掉，止损）再 release + HIGH 告警
-- ③ e 记录不存在（v7.0 补录：受理侧已回滚但 release 失败的残留）→ release + HIGH 告警
```

### 4\.9 执行记录

`taskId、skillCode、version、sha256、沙箱镜像 digest、``client_request_id、context、状态、进度、错误（含 EMPTY_OUTPUT）、耗时、model_calls、tokens、expectedCount、output_config 快照、pricing 快照、hold_id`（产物经 artifact 表以 task\_id 关联，不在 execution 表内直接存储）

### 4\.10 故障恢复与看门狗（异常路径不变量）

正常链路不变量见 §3\.4 伪代码；本节锁死故障路径。看门狗为 scheduler 内嵌定时任务，核心思想：**期望态（execution 终态）与实际态（MQ / K8s Job）持续对账收敛**。

- **受理侧回滚**：mq\.send 同步失败（可感知）→ 当场删 execution \+ release hold \+ 返回 50001，不留悬空

- **PENDING 看门狗**（每 1min）：PENDING 且创建 \> 5min 未被接手 → 重投 MQ；\> 30min → 置 FAILED\(INTERNAL\) \+ 全额退款

- **防双消费**：接手即条件更新——`UPDATE execution SET status='RUNNING' WHERE task_id=:t AND status='PENDING'`，影响行数=1 才继续，多副本抢同一消息只有一个赢

- **RUNNING reconciler**（每 1min）：RUNNING 超过 timeoutSeconds\+5min 宽限 → 查 Job 实际终态收殓；Job missing 需**连续 2 周期**确认（防 API 抖动误判）→ 先 delete\_job（幂等）再**条件回滚 RUNNING→PENDING**（重置 started\_at）后重投——既有「条件更新防双跑」不变量接管，杜绝重投死循环；各步幂等重入（采集按 artifact 表已存在跳过、结算按 hold 状态幂等、置终态条件更新）

**取消（cancel）设计**（status 枚举增 `CANCELLING`）：

- **PENDING 阶段**：条件更新置 CANCELLED → 直接退款；后续 MQ 消息被「status \!= PENDING 跳过」不变量天然挡掉

- **RUNNING 阶段**：置 CANCELLING → reconciler / watch 循环发现 → delete Job → 按失败路径结算（PER\_EXECUTION 全退；METERED 按已发生用量）→ 置 CANCELLED

- 已终态任务 cancel 幂等：直接返回当前终态

```python
def reconcile():                                        # 每 1min 两段看门狗
    for e in where(status=PENDING, created_at < now-5min):
        mq.resend("skill-execute", e.task_id)           # 重投（条件更新防双跑）
    for e in where(status=PENDING, created_at < now-30min):
        finalize(e, FAILED, INTERNAL); refund(e)         # 置失败 + 全额退款

    for e in where(status=RUNNING, started_at < now-(e.timeout+5min)):
        job = k8s.get_job(e)                           # 查实际态
        if job.succeeded:  collect(e); settle(e); finalize(e)   # 幂等收殓
        elif job.alive:    continue                   # 仍在跑（宽限后下次再看）
        else:                                         # missing / FAILED
            if not missing_twice(e): continue           # 防抖：连续 2 周期才动（防 API 抖动误判）
            k8s.delete_job(e)                           # 幂等，防误判时产生双 Job
            n = update(e, status=PENDING, started_at=NULL,
                       where=status=RUNNING)            # 条件回滚，不变量接管
            if n == 1: mq.resend("skill-execute", e.task_id)  # 消费端正常接手
            else:      finalize(e, FAILED, INTERNAL); refund(e)  # 回滚竞争失败

    for e in where(status=CANCELLING):                 # 用户取消的执行中任务
        k8s.delete_job(e); settle_as_failed(e); finalize(e, CANCELLED)
```

---

## 5\. 平台对外接口

### 5\.1 素材上传

**双通道**（v6\.0）：小文件（≤100MB）multipart 经网关；大文件（video 等）**presign 预签名直传 OSS**——网关不做大文件中转。

```
POST /api/v1/materials/presign     { "materialType": "video", "filename": "x.mp4", "sizeBytes": 52428800 }
  → { "materialId": "mat_..._001", "uploadUrl": "https://oss/...(签名PUT)", "expiresAt": "..." }
PUT {uploadUrl}                    调用方直传 OSS（单链接，≤5GB）
POST /api/v1/materials/{materialId}/confirm    校验已上传，回写 size/sha256，素材转为可用
```

```
POST /api/v1/materials/upload
Content-Type: multipart/form-data
```

|参数|必填|说明|
|---|---|---|
|`file`|是|文件|
|`materialType`|否|类型枚举，不传按后缀识别|
|`tenantId`|—|已移除：租户由 AppKey 推导|

|类型|格式|大小上限|
|---|---|---|
|video|mp4/mov/avi/webm|500MB|
|image|jpg/png/gif/webp/bmp|20MB|
|audio|mp3/wav/aac/m4a/flac|100MB|
|document|pdf/doc/docx/xls/xlsx/ppt/pptx/txt/csv/md|50MB|
|data|json/xml/yaml|10MB|
|other|\*|100MB|

**响应**：

```json
{
  "code": 0,
  "data": {
    "materialId": "mat_20260906_001",
    "materialType": "video",
    "materialUrl": "oss://tenant-a/materials/mat_20260906_001.mp4",
    "filename": "原始视频.mp4",
    "sizeBytes": 52428800
  }
}
```

**presign 安全属性**：脚本/前端**全程不持有平台 OSS 凭据**——预签名 URL 由平台密钥在服务端生成，三重限定：唯一 object key（写偏一位即 403）、唯一方法（PUT，不能读/删/列举）、短时效（15min）。签发口子本身过 AppKey\+HMAC 五关；confirm 时校验大小/类型/sha256，未 confirm 的素材不可被 execute 引用。与产物下载（预签名 GET）同一套机制，凭据不出平台。

### 5\.2 Skill 管理

|接口|Method|路径|
|---|---|---|
|上传 Skill 包|POST|`/api/v1/skills/upload`（multipart；publish 可选直发）|
|查 Skill 列表|GET|`/api/v1/skills`（租户由 AppKey 推导）|
|查 Skill 详情|GET|`/api/v1/skills/{skillCode}`|
|设默认版本|PUT|`/api/v1/skills/{skillCode}/default-version`|
|改运营配置（output\_config / pricing\_config）|PUT|`/api/v1/skills/{skillCode}/config`|
|版本审核流转（v7\.0）|PUT|`/api/v1/skills/{skillCode}/versions/{version}/status`（0 待审核 → 1 发布；1 → 2 废弃，废弃为终态）|
|市场上下架（v7\.0）|PUT|`/api/v1/skills/{skillCode}/visibility`（上架 PUBLIC 需已有已发布版本）|
|删除 Skill（v7\.0）|DELETE|`/api/v1/skills/{skillCode}`（硬删元数据、版本与包对象；execution / artifact 保留审计）|
|下载分发包（FREE 公开分发）|GET|`/api/v1/skills/{skillCode}/package`（已发布 \+ PUBLIC 才放行，预签名 GET，任意有效 AppKey \+ 独立限流、下载留痕统计分发量；FREE 亦可 0 点 execute，§4\.8）|

改运营配置：

```
PUT /api/v1/skills/{skillCode}/config
{ "defaultCount": 2, "maxCount": 30 }
```

改 skill 表 output\_config 字段，**即时生效，无需重传包**（Skill 本体改动仍走新版本上传）；下次执行立即读到新值。

**调用说明导出与市场**（v6\.0 新增）：

```
GET /api/v1/skills/{skillCode}/invocation-spec?format=markdown|openapi|tool-schema
GET /api/v1/marketplace/skills          # 公开市场列表（visibility=PUBLIC 且已发布，含定价/说明）
```

### 5\.3 执行接口（核心）

```
POST /api/v1/execute
```

**请求**：

```json
{
  "skillCode": "video-generation",
  "version": null,
  "count": 10,
  "override": false,
  "clientRequestId": "req-20260912-0001",
  "materials": [
    { "type": "video", "url": "oss://tenant-a/materials/mat_20260912_001.mp4" },
    { "type": "text", "content": "品牌要求：突出防晒霜轻薄不闷痘" }
  ],
  "instructions": "生成 vlog 风格短视频，每条侧重不同场景切入",
  "context": { "wb_session_id": "xxx" },
  "callbackUrl": "https://caller-backend.example.com/api/skill-callback"
}
```

|字段|必填|说明|
|---|---|---|
|`skillCode`|是|Skill 编码|
|`version`|否|null = 默认版本|
|`count`|否|产出数量；不传用 defaultCount；规则见 §4\.4|
|`override`|否|count 超 maxCount 时显式放行（自担成本），默认 false|
|`clientRequestId`|否|幂等键，同 AppKey 下唯一；重复请求返回原 taskId 不重复执行|
|`materials` / `instructions`|否|见 §4\.3|
|`context`|否|≤4KB 任意 JSON，回调与终态轮询响应原样带回（agent 续会话用）|
|`callbackUrl`|否|回调通道地址（域名白名单校验）；不传则只能轮询获取结果|
|`tenantId`|—|不再显式传：由 AppKey 推导|

**响应**：

```json
{ "code": 0, "data": { "taskId": "task_20260912_001", "resolvedVersion": "1.2.0", "count": 10, "status": "PENDING", "idempotent": false } }

// 统一异步：结果经「回调（§5.5）」或「轮询（§5.4）」获取，终态两通道响应体同构
// idempotent（v7.0 补录）：true=命中同 clientRequestId 的既有任务（未重复受理，返回原 taskId）
```

### 5\.4 查询与取消

|接口|Method|路径|
|---|---|---|
|查执行状态|GET|`/api/v1/executions/{taskId}`|
|任务列表（v7\.0）|GET|`/api/v1/executions`（本租户分页，可按 status 筛选；轻量行不含产物 URL）|
|取消执行|POST|`/api/v1/executions/{taskId}/cancel`|
|失败重试（v7\.0）|POST|`/api/v1/executions/{taskId}/retry`（按原任务入参重放完整受理：新 taskId \+ 新冻结，原失败记录保留审计；仅 FAILED 且有入参存档可重试）|

**轮询契约（一等公民）**：终态查询响应 = **完整结果体，与回调 body 同构**（status / artifacts\[预签名 URL\] / billing / context / error）——轮询方不需要任何回调能力。状态置 SUCCEEDED 前先落产物与结算，不存在「完成但无结果」竞态。响应含 `progress`（0\-100，Skill 经 report\_progress 上报；null = 不支持）与 `callbackStatus`。

**轮询建议**：间隔 3\-5s；网络错误指数退避、连续 4 次失败才终止；429 可重试、404 视为终态；整体等待上限 600s，超时凭 taskId 稍后恢复轮询。查询接口限流独立放宽，不与 execute 共享 QPS 桶。

### 5\.5 回调（HTTP POST 到 callbackUrl）

```json
{
  "taskId": "task_20260912_001",
  "skillCode": "video-generation",
  "resolvedVersion": "1.2.0",
  "status": "SUCCEEDED",
  "expectedCount": 10,
  "artifacts": [
    { "type": "video", "url": "https://oss.../artifacts/art_001.mp4?Expires=...&Signature=...(24h预签名)", "sizeBytes": 15200000 }
  ],
  "billing": { "mode": "PER_EXECUTION", "pointsCharged": 70, "holdId": "hold_...", "settled": true },
  "context": { "wb_session_id": "xxx" },
  "modelCalls": 15,
  "tokensUsed": 45000,
  "durationMs": 320000,
  "error": null,
  "timestamp": "2026-09-14T10:30:20Z"
}
```

> `expectedCount` = 平台按 count/默认值解析出的期望产出数；artifacts 为实际产出。两者不等时（短缺或超产）status 统一为 SUCCEEDED，实际数量如实转达、短缺说明在 Skill 输出中带回——平台如实转达，不隐瞒不虚构；超产照常交付、计费按 min\(实际, count\)（§4\.4）。
> 
> 

**失败**：

```json
{
  "taskId": "task_20260912_003",
  "status": "FAILED",
  "error": { "code": "TIMEOUT", "message": "Skill execution exceeded 600s" },
  "billing": { "mode": "PER_EXECUTION", "pointsCharged": 0, "holdId": "hold_...", "settled": true },
  "context": { "wb_session_id": "xxx" },
  "timestamp": "2026-09-14T10:35:00Z"
}
// 失败/取消全额退款 pointsCharged=0；METERED 被杀按已发生用量（usage 最后已知值，§4.5）
// settled=false 仅出现在结算 RPC 双次失败的降级时刻：pointsCharged 为预估，终值以 transactions 对账
```

**签名与安全**：回调带 `X-Skill-Signature: HMAC-SHA256(timestamp + body, AppSecret)` 与 `X-Skill-Timestamp`（毫秒数值串，容差 ±5min 防重放），调用方须验签；callbackUrl 需过该 AppKey 域名白名单与保留地址段校验（含 DNS 解析后拒绝回环 / 内网 / 链路本地 / 组播地址，SSRF 防护）。**请求签名（v7\.0 补录规范）**：同一 AppSecret，五段串 `appKey + "\n" + timestamp + "\n" + METHOD + "\n" + path[?query] + "\n" + sha256hex(body)` 做 HMAC\-SHA256，头 X\-Skill\-AppKey / X\-Skill\-Timestamp / X\-Skill\-Signature，常量时间比较。**重试**：指数退避 3 次（1 / 5 / 15min，RocketMQ 延迟等级 5/9/14），超限进死信；重试可能导致重复投递，调用方按 taskId 幂等。

### 5\.6 错误码

|code|含义|
|---|---|
|0|成功|
|40001|参数校验失败（含 instructions ≤32KB / context ≤4KB 超限）|
|40002|Skill 不存在或版本不可用|
|40006|count 参数不合法（不支持数量控制 / 超上限未 override）|
|40004 / 40005|素材类型不支持 / 大小超限|
|40101|鉴权失败（AppKey 无效 / 请求签名错误）|
|40201|点数余额不足（快速失败，任务未入队，响应带 rechargeUrl）|
|40202|计费校验失败（如 METERED 未配置 capPoints）|
|40301|无权限（含 callbackUrl 不在白名单、素材跨租户引用）|
|42901|限流 / 超配额（QPS / 并发 / 日调用量）|
|50001|内部错误|

注：`EMPTY_OUTPUT`（进程成功退出但无产物）是执行 error\_code，随回调 / 查询返回，不是 HTTP 错误码。

### 5\.7 计费与账户接口

```bash
GET  /api/v1/account/balance                      # 当前 AppKey 点数余额（可用 / 冻结中）
GET  /api/v1/account/transactions?taskId=         # 计费流水（HOLD/SETTLE/RELEASE/RECHARGE）
# 管理侧（充值平台 / 运营后台调用，X-Admin-Token 静态凭据 + 内网）
POST /api/v1/admin/app-keys                       # 签发 AppKey + AppSecret（充值成功后）
GET  /api/v1/admin/app-keys                       # key 列表（v7.0 补录）
POST /api/v1/admin/app-keys/{appKeyId}/reset-secret   # AppSecret 重置=轮换（旧值立即失效，v7.0 补录）
PUT  /api/v1/admin/app-keys/{appKeyId}/status     # 禁用 / 启用（v7.0 补录）
POST /api/v1/admin/credits/recharge               # 点数入账（带 orderNo 幂等，重复调用返回已入账流水）
# 免费公开分发 Skill 的埋点上报（Header: X-Skill-Token: skt-...，skill 级令牌）
POST /api/v1/telemetry/skill-io                   # 上报输入摘要/输出元数据/状态/耗时 → skill_io_log
```

**管理面其余接口（v7\.0 补录，均 /api/v1/admin 前缀 \+ X\-Admin\-Token）**：model\-providers 增删改查与启停（模型路由维护，§6\.6）、kbs 与文档入库（§6\.11）、sys\-config 读写（全局配额 DB 覆盖 yml 默认值）、metrics/dashboard 与 metrics/overview（§8\.2 指标可视化数据源）、executions 列表与 callback\-resend / callback\-giveup（死信处理台：重发=按回调契约重建 body 重投 skill\-callback\-retry；放弃=callback\_status 置 GIVE\_UP）。

**分工**：充值平台管商品 / 订单 / 支付渠道；billing\-service 管账户 / 冻结 / 结算 / 流水 / 对账。支付成功 → 签发（或充值）接口 → key 可用。

**admin 接口服务间认证**：`/admin/*` 仅内网可达 \+ 独立服务凭据（mTLS 或静态 token \+ IP 白名单），与调用方 AppKey 体系隔离；充值平台使用专属凭据，最小权限仅签发与入账两个接口。**提供方身份 V1 口径**：Skill 为内部业务资产，运营经管理后台代上传（无第三方自助）；对外开放自助上传时再扩提供方身份模型。

### 5\.8 模型网关接口（v7\.0 新增）

```bash
POST /v1/chat/completions     # OpenAI 兼容转发（平台模型网关，§4.5）
```

鉴权：Bearer \{internal\-token\}\-task\-\{taskId\}（仅沙箱执行流量，worker 注入 env；不对外）。请求体 model=平台能力别名（alias），其余参数原样透传；上游 5xx / 不可达按 fallback\_alias 逐级回退；支持 stream 流式透传。不经 AppKey HMAC 体系（非 /api/v1 路径）。

---

## 6\. 数据库表设计

### 6\.1 skill（Skill 元数据表）

```sql
CREATE TABLE `skill` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `tenant_id`          VARCHAR(64)  NOT NULL                COMMENT '租户标识（由 AppKey 推导）',
    `skill_code`         VARCHAR(128) NOT NULL                COMMENT 'Skill 唯一编码，同租户下唯一',
    `name`               VARCHAR(256) NOT NULL                COMMENT 'Skill 显示名称',
    `description`        TEXT                                 COMMENT 'Skill 功能描述，供市场与管理页展示',
    `visibility`         VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE' COMMENT '可见性：PRIVATE=仅本租户 PUBLIC=市场公开',
    `kind`               VARCHAR(16)  NOT NULL DEFAULT 'CODE' COMMENT '包类型（v7.0）：CODE=main.py 代码技能；AGENT=SKILL.md 智能体技能（平台 agent runner 执行）；同 skillCode 禁止混型',
    `required_abilities` JSON                                 COMMENT '所需平台能力列表，如 ["llm-text","video-gen","kb"]',
    `output_config`      JSON                                 COMMENT '{"countable":true,"defaultCount":1,"maxCount":20,"timeoutSeconds":600}；运营性配置；timeoutSeconds 不传默认 600，全局硬顶 3600',
    `pricing_config`     JSON                                 COMMENT '定价：{"mode":"PER_EXECUTION","points":10} / {"mode":"METERED","capPoints":500} / {"mode":"FREE"}=0 点执行（不冻结不结算，可经 package 端点公开分发，v7.0）；运营性配置',
    `invocation_spec`    JSON                                 COMMENT '调用说明元数据（含前后端拆分：后端 API 契约 + 前端轮询客户端接入说明），一键导出 markdown/openapi/tool-schema',
    `telemetry_token`    VARCHAR(64)  NULL                    COMMENT '埋点令牌（skt- 前缀）：FREE 公开分发模式的 Skill 以 X-Skill-Token 上报 IO；NULL=非公开分发',
    `default_version`    VARCHAR(32)  NULL                    COMMENT '当前默认执行版本号',
    `status`             TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1=启用 0=禁用',
    `created_by`         VARCHAR(64)  NOT NULL                COMMENT '上传人',
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_skill` (`tenant_id`, `skill_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Skill 元数据表';
```

### 6\.2 skill\_version（Skill 版本表）

```sql
CREATE TABLE `skill_version` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `skill_id`        BIGINT       NOT NULL                COMMENT '关联 skill 表 id',
    `version`         VARCHAR(32)  NOT NULL                COMMENT '语义化版本号，如 1.2.0；同一 skill 下唯一',
    `oss_key`         VARCHAR(512) NOT NULL                COMMENT 'Skill 包在 OSS 的路径，格式 skill/{tenantId}/{skillCode}/{version}/skill.zip',
    `oss_url`         VARCHAR(1024) NULL                   COMMENT '对象绝对访问地址（bucket endpoint + key 稳定指针，v7.0）；读写仍走预签名',
    `package_sha256`  CHAR(64)     NOT NULL                COMMENT '包完整性校验值（SHA-256），下载后校验 + 执行记录复现锚点',
    `package_size`    BIGINT       NOT NULL                COMMENT '包大小（字节）',
    `changelog`       TEXT                                 COMMENT '本版变更说明',
    `status`          TINYINT      NOT NULL DEFAULT 0      COMMENT '版本状态：0=已上传待审核 1=已发布可执行 2=已废弃不可执行（废弃为终态）',
    `uploaded_by`     VARCHAR(64)  NOT NULL                COMMENT '上传人',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_skill_version` (`skill_id`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Skill 版本表：每次上传新版本一条记录，包永不覆盖，保障历史任务可复现';
```

### 6\.3 execution（执行记录表）

```sql
CREATE TABLE `execution` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `task_id`           VARCHAR(64)   NOT NULL                COMMENT '全局唯一任务ID，返回给调用方用于轮询/回调关联',
    `client_request_id` VARCHAR(128)  NULL                    COMMENT '调用方幂等键；同 AppKey 下唯一，重复请求返回原任务',
    `app_key_id`        VARCHAR(64)   NOT NULL                COMMENT '发起调用的 AppKey（v7.0 更正类型：存 app_key_id 字符串，非自增 id）',
    `tenant_id`         VARCHAR(64)   NOT NULL                COMMENT '租户标识',
    `skill_code`        VARCHAR(128)  NOT NULL                COMMENT '执行的 Skill 编码',
    `skill_version`     VARCHAR(32)   NOT NULL                COMMENT '实际解析使用的版本号',
    `package_sha256`    CHAR(64)      NOT NULL                COMMENT 'Skill 包 SHA-256，复现锚点之一',
    `sandbox_digest`    VARCHAR(64)   NULL                    COMMENT '沙箱镜像 digest，复现锚点之二',
    `input_ref`         VARCHAR(1024) NULL                    COMMENT '完整入参 JSON 的 OSS key',
    `context`           JSON          NULL                    COMMENT '调用方透传上下文（≤4KB），回调与终态轮询响应原样带回',
    `expected_count`    INT           NULL                    COMMENT '期望产出数',
    `count_overridden`  TINYINT       NOT NULL DEFAULT 0      COMMENT 'count 超限经 override 放行：1=是',
    `output_config_snapshot` JSON     NULL                    COMMENT '执行时 output_config 快照',
    `pricing_snapshot`  JSON          NULL                    COMMENT '执行时 pricing_config 快照（计费按此复算）',
    `hold_id`           VARCHAR(64)   NULL                    COMMENT '计费冻结单（FREE / 0 点任务为 NULL）',
    `status`            VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/CANCELLING=取消中/SUCCEEDED/FAILED/CANCELLED；置终态前先落产物与结算',
    `progress`          TINYINT       NULL                    COMMENT '进度 0-100（report_progress 上报；NULL=不支持）',
    `error_code`        VARCHAR(32)   NULL                    COMMENT 'TIMEOUT/BUDGET_EXCEEDED/EMPTY_OUTPUT/OUTPUT_LIMIT/NEED_MEDIA/INTERNAL/CANCELLED',
    `error_message`     TEXT          NULL                    COMMENT '失败详细描述',
    `callback_url`      VARCHAR(512)  NULL                    COMMENT '回调地址（可选通道；不传则仅轮询）',
    `callback_status`   VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/RETRYING/DEAD_LETTER/GIVE_UP（v7.0：后管放弃）/NO_CALLBACK',
    `model_calls`       INT           NOT NULL DEFAULT 0      COMMENT '模型调用次数（toolset 增量上报汇总，§4.5）',
    `tokens_used`       INT           NOT NULL DEFAULT 0      COMMENT 'token 消耗（usage 上报汇总）',
    `duration_ms`       BIGINT        NULL                    COMMENT '执行时长（毫秒）',
    `created_by`        VARCHAR(64)   NOT NULL                COMMENT '发起人（AppKey 标识）',
    `created_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '任务创建时间',
    `started_at`        DATETIME      NULL                    COMMENT '开始执行时间（接手条件更新时写入）',
    `finished_at`       DATETIME      NULL                    COMMENT '完成/终止时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_id` (`task_id`),
    UNIQUE KEY `uk_key_client_req` (`app_key_id`, `client_request_id`),
    KEY `idx_tenant_status` (`tenant_id`, `status`),
    KEY `idx_app_key` (`app_key_id`, `created_at`),
    KEY `idx_status_started` (`status`, `started_at`),
    KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='执行记录表：统一异步，结果经回调或轮询获取；idx_status_started 供看门狗扫描';
```

### 6\.4 artifact（执行产物表）

```sql
CREATE TABLE `artifact` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `artifact_id`   VARCHAR(64)  NOT NULL                COMMENT '全局唯一产物ID，如 art_20260912_001',
    `task_id`       VARCHAR(64)  NOT NULL                COMMENT '关联的执行任务ID',
    `tenant_id`     VARCHAR(64)  NOT NULL                COMMENT '租户标识（冗余存储，便于按租户清理）',
    `type`          VARCHAR(32)  NOT NULL                COMMENT '产物类型：video/image/audio/document/json/text',
    `oss_key`       VARCHAR(512) NOT NULL                COMMENT '产物在 OSS 的路径：{tenantId}/artifacts/{taskId}/{filename}',
    `file_size`     BIGINT       NOT NULL DEFAULT 0      COMMENT '文件大小（字节）',
    `content_type`  VARCHAR(128) NULL                    COMMENT 'MIME 类型',
    `meta`          JSON         NULL                    COMMENT '扩展元数据（视频时长、分辨率等）',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '产出时间',
    `expires_at`    DATETIME     NULL                    COMMENT '过期清理时间（默认90天）；NULL=永久保留',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_artifact_id` (`artifact_id`),
    KEY `idx_task` (`task_id`),
    KEY `idx_tenant_expires` (`tenant_id`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='执行产物表：记录 Skill 产物文件，关联 OSS；单文件≤500MB、总量≤2GB、数量≤maxCount×2（执行时校验）';
```

### 6\.5 material（素材表）

```sql
CREATE TABLE `material` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `material_id`        VARCHAR(64)  NOT NULL                COMMENT '全局唯一素材ID',
    `tenant_id`          VARCHAR(64)  NOT NULL                COMMENT '租户标识（受理时校验 oss:// 归属）',
    `material_type`      VARCHAR(32)  NOT NULL                COMMENT 'video/image/audio/document/data/other（other=存储兜底）',
    `oss_key`            VARCHAR(512) NOT NULL                COMMENT '素材路径：{tenantId}/materials/{materialId}.{ext}',
    `upload_channel`     VARCHAR(16)  NOT NULL DEFAULT 'multipart' COMMENT 'multipart / presign',
    `status`             TINYINT      NOT NULL DEFAULT 0      COMMENT '0=待上传（presign 已签发未确认）1=可用 2=确认失败',
    `filename`           VARCHAR(256) NULL                    COMMENT '原始文件名',
    `content_type`       VARCHAR(128) NULL                    COMMENT 'MIME 类型',
    `file_size`          BIGINT       NOT NULL DEFAULT 0      COMMENT '文件大小（字节）',
    `sha256`             CHAR(64)     NULL                    COMMENT '内容 SHA-256（confirm 时回写校验）',
    `created_by`         VARCHAR(64)  NULL                    COMMENT '上传人（AppKey 标识）',
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    `last_referenced_at` DATETIME     NULL                    COMMENT '最近被执行请求引用时间；7 天未引用清理以此为准',
    `expires_at`         DATETIME     NULL                    COMMENT '过期时间；NULL=永久',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_material_id` (`material_id`),
    KEY `idx_tenant` (`tenant_id`),
    KEY `idx_cleanup` (`last_referenced_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='素材表：双通道上传的输入文件；text/link 语义类型不落库（内联/透传）';
```

### 6\.6 model\_provider（模型供应商配置表）

```sql
CREATE TABLE `model_provider` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `alias`           VARCHAR(64)   NOT NULL                COMMENT '模型能力别名，任务启动时按此注入环境变量，如 llm-text / vlm-video / video-gen',
    `provider`        VARCHAR(64)   NOT NULL                COMMENT '供应商名称，如 zhipu / volcengine / qwen / openai / custom',
    `model_name`      VARCHAR(128)  NOT NULL                COMMENT '供应商侧的模型名称',
    `endpoint`        VARCHAR(512)  NOT NULL                COMMENT 'API 端点地址',
    `api_key_cipher`  VARCHAR(512)  NOT NULL                COMMENT 'API Key（AES 加密存储，解密后写入 LiteLLM 配置）',
    `fallback_alias`  VARCHAR(64)   NULL                    COMMENT '回退目标 alias（LLM 类→LiteLLM fallbacks；video_gen→toolset 读此配置自行回退）；NULL=无回退',
    `max_qps`         INT           NOT NULL DEFAULT 10     COMMENT '该模型最大 QPS，用于 LiteLLM 限流配置',
    `cost_per_1k_input_tokens`  DECIMAL(10,4) NULL           COMMENT '每 1k 输入 token 成本（点数）——METERED 折算与平台成本归因',
    `cost_per_1k_output_tokens` DECIMAL(10,4) NULL           COMMENT '每 1k 输出 token 成本（点数）',
    `cost_per_call`   DECIMAL(10,4) NULL                     COMMENT '按次成本（视频生成等非 token 计费能力）',
    `status`          TINYINT       NOT NULL DEFAULT 1       COMMENT '1=启用 0=禁用（禁用后路由不生效，流量走 fallback_alias）',
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间（Key 轮换时更新）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_alias` (`alias`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='模型供应商配置表：alias 路由 + 回退链 + 成本单价，对应 LiteLLM 路由表；Skill 只认 alias';
```

### 6\.7 app\_key（AppKey 表，skill\_platform 库）

```sql
CREATE TABLE `app_key` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `app_key_id`       VARCHAR(64)  NOT NULL                COMMENT '对外 AppKey（sk- 前缀），即调用方 apikey',
    `tenant_id`        VARCHAR(64)  NOT NULL                COMMENT '租户标识',
    `secret_cipher`    VARCHAR(512) NOT NULL                COMMENT 'AppSecret（AES 加密存储；请求与回调 HMAC 签名共用）',
    `quota`            JSON         NULL                    COMMENT '限流配额：{"qps":10,"maxRunning":50,"dailyLimit":10000}；未配置用全局默认',
    `callback_domains` JSON         NULL                    COMMENT '回调域名白名单 JSON 数组；空=仅做保留地址段校验',
    `status`           TINYINT      NOT NULL DEFAULT 1      COMMENT '1=启用 0=禁用',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '签发时间（充值平台支付成功触发）',
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_key` (`app_key_id`),
    KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AppKey 表：充值平台支付成功后签发；V1 key 即账户主体；quota 三级限流配置';
```

### 6\.8 credit\_account（点数账户表，skill\_billing 库）

```sql
CREATE TABLE `credit_account` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `app_key_id`  VARCHAR(64) NOT NULL                COMMENT '关联 AppKey（V1：key 即账户，一 key 一账户）',
    `tenant_id`   VARCHAR(64) NOT NULL                COMMENT '租户标识（冗余，报表用）',
    `balance`     BIGINT      NOT NULL DEFAULT 0      COMMENT '可用点数',
    `frozen`      BIGINT      NOT NULL DEFAULT 0      COMMENT '冻结中点数（在途任务 hold 合计）',
    `version`     BIGINT      NOT NULL DEFAULT 0      COMMENT '乐观锁版本号（冻结/结算单行原子更新）',
    `updated_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_key` (`app_key_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='点数账户表：冻结=balance 减 frozen 加（WHERE balance 足够，单行原子）';
```

### 6\.9 billing\_hold（计费冻结单，skill\_billing 库）

```sql
CREATE TABLE `billing_hold` (
    `id`             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `hold_id`        VARCHAR(64) NOT NULL                COMMENT '冻结单ID',
    `task_id`        VARCHAR(64) NOT NULL                COMMENT '关联执行任务（一任务一冻结单）',
    `app_key_id`     VARCHAR(64) NOT NULL                COMMENT '扣点账户',
    `amount`         BIGINT      NOT NULL                COMMENT '冻结点数（预估：单价×count 或 capPoints）',
    `status`         VARCHAR(16) NOT NULL DEFAULT 'FROZEN' COMMENT 'FROZEN=冻结中 SETTLED=已结算 RELEASED=已释放（退款/对账兜底）',
    `settled_amount` BIGINT      NULL                    COMMENT '实际结算点数（≤amount，差额已解冻）',
    `created_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '冻结时间',
    `settled_at`     DATETIME    NULL                    COMMENT '结算/释放时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_hold` (`hold_id`),
    UNIQUE KEY `uk_task` (`task_id`),
    KEY `idx_status_created` (`status`, `created_at`),
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='计费冻结单：对账任务扫 FROZEN 超 24h 未结算自动释放';
```

### 6\.10 billing\_transaction（计费流水表，skill\_billing 库）

```sql
CREATE TABLE `billing_transaction` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `tx_id`         VARCHAR(64)  NOT NULL                COMMENT '流水ID',
    `app_key_id`    VARCHAR(64)  NOT NULL                COMMENT '账户',
    `task_id`       VARCHAR(64)  NULL                    COMMENT '关联任务（HOLD/SETTLE/RELEASE 带；充值/调整为 NULL）',
    `type`          VARCHAR(16)  NOT NULL                COMMENT 'HOLD=冻结 SETTLE=结算 RELEASE=释放 RECHARGE=充值 ADJUST=人工调整',
    `amount`        BIGINT       NOT NULL                COMMENT '点数变动（正/负）',
    `balance_after` BIGINT       NOT NULL                COMMENT '变动后可用余额快照（审计）',
    `remark`        VARCHAR(256) NULL                    COMMENT '备注（订单号 / 退款原因等）',
    `order_no`      VARCHAR(64)  NULL                    COMMENT '充值订单号（v7.0 补录：RECHARGE 幂等键，§10.4 入账幂等）',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tx` (`tx_id`),
    UNIQUE KEY `uk_type_order` (`type`, `order_no`),
    KEY `idx_key_task` (`app_key_id`, `task_id`),
    KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='计费流水表：不可变（只插入不更新），审计与对账依据；RECHARGE 按 orderNo 幂等';
```

### 6\.11 kb / kb\_document（知识库表，skill\_platform 库；向量在 Milvus）

```sql
CREATE TABLE `kb` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `kb_id`             VARCHAR(64)  NOT NULL                COMMENT '知识库ID',
    `tenant_id`         VARCHAR(64)  NOT NULL                COMMENT '租户标识（KB 隔离边界）',
    `name`              VARCHAR(128) NOT NULL                COMMENT '知识库名称',
    `embedding_alias`   VARCHAR(64)  NOT NULL                COMMENT '向量化模型 alias（经 LiteLLM）',
    `milvus_collection` VARCHAR(128) NOT NULL                COMMENT '对应 Milvus collection（含 tenant 前缀，物理隔离）',
    `status`            TINYINT      NOT NULL DEFAULT 1      COMMENT '1=启用 0=禁用',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`), UNIQUE KEY `uk_kb` (`kb_id`), KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识库表：toolset kb_query 检索入口，租户隔离';

CREATE TABLE `kb_document` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `doc_id`      VARCHAR(64)  NOT NULL                COMMENT '文档ID',
    `kb_id`       VARCHAR(64)  NOT NULL                COMMENT '所属知识库',
    `tenant_id`   VARCHAR(64)  NOT NULL                COMMENT '租户标识',
    `oss_key`     VARCHAR(512) NOT NULL                COMMENT '原文 OSS 路径',
    `chunk_count` INT          NOT NULL DEFAULT 0      COMMENT '已入库分块数',
    `status`      TINYINT      NOT NULL DEFAULT 0      COMMENT '0=待入库 1=已入库 2=失败',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (`id`), UNIQUE KEY `uk_doc` (`doc_id`), KEY `idx_kb` (`kb_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识库文档表：入库 pipeline 状态跟踪';
```

### 6\.12 skill\_io\_log（免费公开分发 Skill 埋点表，skill\_platform 库）

```sql
CREATE TABLE `skill_io_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `skill_code`  VARCHAR(128) NOT NULL                COMMENT 'Skill 编码',
    `version`     VARCHAR(32)  NULL                    COMMENT 'Skill 版本（上报方声明）',
    `input`       JSON         NULL                    COMMENT '输入摘要（materials 元信息 + instructions）',
    `output`      JSON         NULL                    COMMENT '输出产物元数据（类型/数量/大小，不含文件本体）',
    `status`      VARCHAR(16)  NOT NULL                COMMENT 'SUCCEEDED / FAILED（调用方环境执行结果）',
    `duration_ms` BIGINT       NULL                    COMMENT '执行耗时（毫秒）',
    `caller_hint` VARCHAR(128) NULL                    COMMENT '调用方环境标识（agent 平台/来源，尽力而为）',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上报时间',
    PRIMARY KEY (`id`),
    KEY `idx_skill_created` (`skill_code`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='免费公开分发 Skill 的埋点日志：采集 IO 供价值分析（代码公开跑在调用方环境，埋点可被摘除——样本尽力而为）';
```

### 6\.13 ER

```
skill (1) ──→ skill_version (N)
skill (1) ──→ execution (N)；execution (1) ──→ artifact (N)；execution (1) ──→ billing_hold (1)
app_key (1) ──→ credit_account (1) ──→ billing_transaction (N)；app_key (1) ──→ execution (N)
material 独立（last_referenced_at 回写）；model_provider 独立（fallback_alias 自引用）
kb (1) ──→ kb_document (N)；向量数据在 Milvus（collection 按 tenant 物理隔离）
skill_io_log 独立（FREE 公开分发 Skill 埋点，按 skill_code 关联）
sys_config 独立（v7.0：平台系统配置，DB 覆盖 yml 默认值，§6.14）
```

### 6\.14 sys\_config（平台系统配置表，v7\.0 补录，skill\_platform 库）

```sql
CREATE TABLE `sys_config` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `config_key`    VARCHAR(128) NOT NULL                COMMENT '配置键（如 quota.qps）',
    `config_value`  VARCHAR(512) NOT NULL                COMMENT '配置值（覆盖 yml 默认）',
    `remark`        VARCHAR(256) NULL                    COMMENT '备注',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='平台系统配置：DB 覆盖 yml 默认值';
```

用途：全局配额默认值（quota\.qps / quota\.max\-running / quota\.daily\-limit 等）DB 覆盖 yml 默认，后管 sys\-config 接口读写（§5\.7）；限流读取顺序 = app\_key\.quota 字段 → sys\_config → yml 默认。

---

## 7\. 关键约束

|约束|说明|
|---|---|
|超时|Skill 级 timeoutSeconds（默认 600s）\+ 全局硬顶 3600s（Job activeDeadlineSeconds 同源）|
|沙箱安全|每任务独立 K8s Job：非 root、资源 limit、独立节点池（常备 3 台自动伸缩）、egress 受控（公网 80/443 \+ 平台端点，禁内网 / 云元数据）；路径穿越校验 \+ sha256；上传经平台审核（V1 运营代传）|
|计费|预校验不足快速失败；冻结\-结算（同步 RPC 主路径）\-退款闭环；单任务调用上限硬闸；对账先查任务状态再动钱；流水不可变|
|存储|OSS 全私有桶，对外仅预签名（产物 24h / 上传 15min）；产物 90 天、单文件 ≤500MB、总量 ≤2GB、数量 ≤ maxCount×2；Skill 包永久；素材 7 天未引用清理|
|轮询|终态查询响应与回调 body 同构；建议 3\-5s 间隔 \+ 指数退避，429 可重试、404 终态；查询不经 execute 的 QPS 桶（无独立限流，v7\.0 对齐）|
|回调|HMAC\-SHA256 签名 \+ 时间戳防重放；域名白名单 \+ 禁保留地址段；退避 3 次重试 \+ 死信；重复投递按 taskId 幂等；callbackUrl 可选|
|限流配额|Redis 令牌桶三级：QPS（默认 10）/ 租户并发 RUNNING（默认 50）/ 日调用量（默认 1 万），配额存 app\_key\.quota；查询通道独立放宽|
|素材归属|受理时校验 oss:// 素材归属本 AppKey 租户（material 表 tenant\_id），跨租户引用 → 40301|

## 8\. 容量与运维

### 8\.1 容量规划（V1 保守目标）

|项|目标值|依据 / 余量|
|---|---|---|
|受理 QPS|100|gateway 单副本数千 QPS，Redis 限流 10 万\+ ops/s|
|轮询查询 QPS|500（峰值活跃轮询 \~25 QPS × 20 倍余量）|峰值并发 \~100 任务 × 4s 间隔 ≈ 25 QPS|
|日任务量|1 万|稳态并发 ≈ 35（平均 5min/任务）|
|并发 Job 峰值|100（日 1 万 × 峰值系数 3）|对应节点需求见下|
|沙箱节点|8C16G，视频类密度 2\-3 Job/节点|峰值 100 并发 → 节点池 5\-40 自动伸缩、常备 5（镜像预热）|
|MQ|RocketMQ 单主|TPS 万级富余|
|Redis / MySQL|单实例 / 主从（同实例分库）|瓶颈顺序：DB 查询 \> JVM \> Redis|

### 8\.2 监控与告警

|指标|阈值 → 动作|
|---|---|
|skill\-execute 队列深度|\> 1000 → 告警（消费堆积）|
|PENDING 年龄 P99|\> 5min → 告警（看门狗重投命中）|
|RUNNING 超 timeout 任务数|\> 0 → 告警（reconciler 收殓，逐条对账）|
|Job 成功率|\< 90%（日窗）→ 告警|
|hold 悬空数（FROZEN \> 1h）|\> 0 → 告警（对账前置预警）|
|回调死信 / 对账释放数突增|\> 0 / 环比突增 → 告警（v7\.0 注：DLQ 自动告警未实施，人工经后管死信处理台）|
|40201 / 42901 占比|突增 → 运营关注（余额/配额策略）|

### 8\.3 发布与回滚

- **服务**：gateway / billing / scheduler 无状态，滚动或蓝绿发布；billing 发布前先跑一轮对账任务清零悬空 hold

- **执行镜像**：executor 镜像按 tag 灰度（Job 路由按 tag），执行记录 sandbox\_digest 可追溯回滚范围

- **Skill**：回滚 = 改 default\_version 标记（包永不覆盖，秒级）；DDL 只加列不改列，向前兼容

## 9\. 验证用例清单

用例 ID 稳定不变，供任务拆解（ly\-task）与测试执行（ly\-test）关联；分组与章节对应：受理（§4\.2/§5\.3）、调度与看门狗（§4\.10）、计费与对账（§4\.8）、产物（§4\.7）、通道（§5\.4/§5\.5）、素材（§5\.1）、FREE（§4\.8/§5\.2）。

|用例 ID|分组|用例 / 关键预期|
|---|---|---|
|`TC-EXE-001`|受理|正常受理：五关通过，返回 taskId/PENDING，MQ 收到消息|
|`TC-EXE-002`|受理|AppKey 无效/签名错误 → 40101，不产生任何记录|
|`TC-EXE-003`|受理|余额不足 → 40201 带 rechargeUrl，任务不入队不建 hold|
|`TC-EXE-004`|受理|count 超 maxCount 未 override → 40006；override=true 放行且标记|
|`TC-EXE-005`|受理|countable=false 传 count → 40006|
|`TC-EXE-006`|受理|幂等命中：同 clientRequestId 重复请求返回原 taskId，不二次冻结|
|`TC-EXE-007`|受理|并发同 clientRequestId：uk 冲突败者立即释放 hold（余额可查证）|
|`TC-EXE-008`|受理|materials 引用他租户 oss:// → 40301，不执行|
|`TC-EXE-009`|受理|mq\.send 失败 → 50001，execution 已删、hold 已释放|
|`TC-EXE-010`|受理|AppKey QPS 超限（默认 10/s）→ 42901|
|`TC-EXE-011`|受理|租户并发 RUNNING 超 maxRunning（默认 50）→ 42901|
|`TC-EXE-012`|受理|日调用量超 dailyLimit → 42901，次日恢复|
|`TC-SCH-001`|调度|双副本抢同一消息：条件更新仅一个接手，另一个 continue|
|`TC-SCH-002`|看门狗|PENDING\>5min → 重投 MQ；消息重复到达不双跑|
|`TC-SCH-003`|看门狗|PENDING\>30min → FAILED\(INTERNAL\) \+ 全额退款|
|`TC-SCH-004`|看门狗|scheduler watch 中崩溃：RUNNING reconciler 按 Job 实际终态收殓（SUCCEEDED 补采集/结算/置终态）|
|`TC-SCH-005`|看门狗|Job 超时未消 → delete Job，置 FAILED\(TIMEOUT\)，退款|
|`TC-SCH-006`|取消|PENDING 阶段 cancel → CANCELLED \+ 退款；迟到消息被跳过|
|`TC-SCH-007`|取消|RUNNING 阶段 cancel → CANCELLING → 杀 Job → 按失败路径结算 → CANCELLED|
|`TC-SCH-008`|取消|cancel 已终态任务 → 幂等返回当前终态，不重复结算/退款|
|`TC-SCH-009`|看门狗|RUNNING 孤儿（Job 连续 2 周期 missing）→ delete \+ 条件回滚 PENDING \+ 重投接手，全程无双跑|
|`TC-BIL-001`|计费|PER\_EXECUTION 正常：结算=min\(实际,count\)×单价，差额解冻，流水 SETTLE|
|`TC-BIL-002`|计费|失败/超时/取消 → 全额释放，pointsCharged=0|
|`TC-BIL-003`|计费|空产物 → FAILED\(EMPTY\_OUTPUT\) \+ 全额退款|
|`TC-BIL-004`|计费|超产：产出\>count，交付全部、计费按 min，回调如实转达数量|
|`TC-BIL-005`|计费|settle 重复消息（MQ 兜底重投）→ hold 状态幂等，不二次扣/退|
|`TC-BIL-006`|计费|METERED 正常：usage 折算封顶 capPoints|
|`TC-BIL-007`|计费|METERED 被杀：按 usage 最后已知值结算|
|`TC-BIL-008`|对账|FROZEN\>24h 且任务已终态 → 补结算/释放 \+ 告警|
|`TC-BIL-009`|对账|FROZEN\>24h 且任务非终态 → 先置 CANCELLED 再释放 \+ 高级告警|
|`TC-BIL-010`|计费|settle RPC 双次失败 → 回调 billing\.settled=false 且 pointsCharged=预估值；MQ 兜底结算后 transactions 终值正确|
|`TC-ART-001`|产物|manifest\.json 解析 type/meta；无 manifest 按后缀推断|
|`TC-ART-002`|产物|单文件/总量/数量超限 → FAILED\(OUTPUT\_LIMIT\)，退款语义不变|
|`TC-ART-003`|产物|产物 URL 为 24h 预签名，过期后不可访问（可重新查询获取新签名）|
|`TC-CHN-001`|通道|终态轮询响应与回调 body 同构（同 taskId 逐字段比对）|
|`TC-CHN-002`|通道|回调失败退避 1/5/15min 重试，3 次后进死信并告警|
|`TC-CHN-003`|通道|回调重复投递：调用方按 taskId 幂等（平台侧重试语义验证）|
|`TC-MAT-001`|素材|presign→PUT→confirm 全链：material 置 ACTIVE，sha256/size 回写|
|`TC-MAT-002`|素材|presign 后未 confirm：素材不可被 execute 引用|
|`TC-MAT-003`|素材|预签名 URL 过期后 PUT → 403|
|`TC-MAT-004`|素材|multipart 通道 ≤100MB 正常入库；超限拒绝|
|`TC-PRG-001`|进度|report\_progress 上报后查询接口 progress 可见；不支持的 Skill 为 null|
|TC\-FRE\-001|FREE|FREE Skill 经 execute 受理：0 点通过（不触 billing、无冻结），hold\_id=NULL，正常进 MQ；package 端点仍可下载分发包（v7\.0）|
|`TC-FRE-002`|FREE|package 下载：PUBLIC\+已审核放行（任意有效 AppKey\+独立限流）；PRIVATE 拒绝|
|`TC-FRE-003`|FREE|埋点上报 X\-Skill\-Token 鉴权 → skill\_io\_log 入库可查|

## 10\. 页面设计（管理后台 / 充值用户端）

**归属与鉴权**：管理后台前端 → gateway 管理面（内部账号体系，V1 角色：管理员 / 运营 / 只读）；充值用户端 → 充值平台 BFF（自有会话）\+ 执行平台用户 API（用户 AppKey 鉴权）。两套前端独立部署，不共用。

### 10\.1 管理后台（运营内部，PC）

|模块|页面与核心要素|对应接口|
|---|---|---|
|Skill 管理|列表（筛选：租户 / visibility / 计费模式 / 状态；日调用量与成功率）；上传表单（zip \+ required\_abilities \+ output\_config \+ pricing\_config \+ invocation\_spec）提交审核；详情（版本列表含 sha256/changelog/审核状态、审核通过/废弃、**默认版本切换=秒级回滚**、运营配置即时改、调用说明预览与三格式导出）|§5\.2|
|AppKey 与账户|key 列表（租户 / quota / 余额 / 禁用启用）；流水查询（按 key / 任务 / 类型 HOLD/SETTLE/RELEASE/RECHARGE）；人工调整（ADJUST）双人审批|§5\.7 admin|
|执行监控|看板（§8\.2 指标可视化：队列深度 / PENDING·RUNNING 年龄 / Job 成功率 / 40201·42901 占比 / 死信）；任务列表与详情（进度 / 沙箱 Job / 产物预览（预签名）/ 错误 / 计费明细 / Langfuse trace 链接）；**死信与 FROZEN 悬空处理台**（死信重发/放弃、悬空 hold 处置）|§5\.4 \+ admin 查询|
|市场管理|PUBLIC Skill 上下架、市场卡片（图/说明）配置、排序|§5\.2|
|模型与能力|model\_provider 维护（alias / provider / fallback\_alias / 成本单价 / max\_qps）与 LiteLLM 路由状态；KB 管理（知识库列表 / 文档入库状态 / 重建索引）|§6\.6 / §6\.11 admin|
|系统配置|全局配额默认值（QPS / 并发 / 日调用量）、限流参数、告警接收人|admin|

### 10\.2 充值用户端（对外，H5/PC 响应式）

|页面|核心要素|
|---|---|
|登录 / 注册|手机号 / 邮箱 \+ 验证码（V1）；后续对接微信扫码|
|工作台|余额（可用 / 冻结中分列）、APIKey 管理入口、文档中心入口|
|充值|点数套餐（阶梯赠送，如 100 点 / 550 点 / 1200 点）\+ 自定义金额；微信支付（Native / H5），支付宝预留；支付结果轮询展示|
|APIKey 管理|key 脱敏列表（sk\-\*\*\*\*）；**完整值仅签发 / 重置时展示一次**；AppSecret 重置=轮换（旧值立即失效，回调与请求签名同步更新）；quota 展示；手动禁用|
|流水|充值 / 扣点 / 退款流水，按任务关联（供用户对账）|
|任务查询|按 taskId 查执行状态 / 进度 / 产物（预签名下载）——**零代码查结果**，复用 §5\.4 查询 API|
|文档中心|接入指南、各 Skill 调用说明（invocation\_spec 渲染）、错误码表、轮询客户端用法|

### 10\.3 充值下单与签发流程

1. 用户登录 → 选套餐 → 充值平台创建订单（订单号幂等防重）

2. 拉起微信支付（Native / H5）→ 完成支付

3. 支付回调到充值平台 → 订单置已支付

4. 充值平台调 `POST /api/v1/admin/credits/recharge` 入账（订单号防重，重复回调幂等）

5. 首次购买：调 `POST /api/v1/admin/app-keys` 签发 AppKey \+ AppSecret

6. 前端轮询订单状态 → 成功页展示余额

7. **key / Secret 完整值仅此一次展示**（列表永久脱敏）；丢失走重置（轮换，旧值立即失效）

8. 用户持 key 按文档调用 API，或配置到 agent Skill 前端的 config\.json（如 LY\_API\_KEY）

**非功能**：全站 HTTPS；充值端 H5/PC 响应式；后管 PC。

### 10\.4 支付服务端设计（充值平台 recharge\-service）

充值平台 **recharge\-service 为独立服务**（轻量 Spring Boot 单体，独立 recharge 库，独立部署），**不并入 billing\-service**——三条理由：① **安全暴露面**：充值要接收公网支付回调、面向 C 端用户，而 billing 是纯内网服务，合并等于账务核心暴露公网与商户证书管理面；② **职责与审计**：渠道对接（证书轮换 / 验签 / 费率）变更频繁，账务核心要求极稳，混装则每次动支付都在碰钱的服务上发版；③ **迭代节奏**：充值平台带套餐 / 营销 / C 端用户体系，与 billing 的谨慎节奏不同。跨服务入账调用不是负担——**orderNo 幂等边界即防腐层**：渠道乱象止步于 recharge\-service，billing 只认 orderNo 入账。职责一句话：**recharge\-service 碰渠道和用户，billing 只碰账户和流水，两者间只有 §5\.7 两个 admin API**。核心原则与执行平台一脉相承：**状态机条件更新做幂等、先落自身状态再外调、外调失败由重试任务推进、对账兜底先查成因再动钱**。

**订单状态机**：

```
CREATED → PAYING    渠道统一下单成功（二维码 / 跳转 URL，15min 有效）
PAYING  → PAID      回调验签通过 且 金额一致；条件更新幂等（重复回调应答成功不重复处理）
PAID    → CREDITED 调执行平台 recharge 入账（+ 首次购买签发 AppKey）成功；
                   外调失败由重试任务推进（1/5/15min 退避），超 1h 告警人工
PAYING  → EXPIRED  15min 未支付：渠道关单 + 主动查单确认未付才置过期
退款：V1 不做自动退款，运营经后管 ADJUST 人工处理（负向调整 + 双人审批）
```

**渠道抽象**（新渠道只加适配器）：

```python
interface PaymentChannel:
    create(order)      -> code_url / 跳转URL     # 微信 Native（code_url）/ 支付宝 precreate（qr_code）
    verifyNotify(raw)  -> OrderPaid              # 微信 APIv3 平台证书验签 + AES-256-GDM 解密；
                                                  # 支付宝 RSA2 公钥验签
    query(order)       -> 渠道侧支付状态          # 主动查单（过期判定 + 对账兜底）
# 商户号 / 证书 / 平台私钥：AES 加密存储，永不落日志（复用平台密钥管理理念）
```

**安全与幂等五条**：

- 回调验签：按渠道官方方案验签，**原始报文全量落 payment\_notify\_log 留档**（审计 \+ 排障回放）

- 金额校验：回调实付金额 == amount\_fen 才进 PAID（防篡改）；内部金额一律用**分**，杜绝浮点

- 回调幂等：\`UPDATE recharge\_order SET status=PAID WHERE order\_no=:o AND status=PAYING\`——影响行数=0 即重复回调，直接应答成功

- **入账幂等**：调执行平台 recharge 带 `orderNo`，执行平台以 \(type=RECHARGE, orderNo\) 唯一约束幂等，重复调用返回已入账流水

- **查单兜底**：对账任务扫「PAYING 且临近过期」订单主动查渠道——已支付则补 PAID \+ 入账（回调丢失场景），未支付则关单置 EXPIRED；与执行平台「先查成因再动钱」同理念

> 📊 **扫码支付时序**（可编辑画板：下单 → 扫码 → 回调验签 → 幂等入账 → 查单兜底）
> 
> 

**recharge\_order 表**（充值平台 recharge 库；payment\_notify\_log 仅留档原始报文，结构从略）：

```sql
CREATE TABLE `recharge_order` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `order_no`        VARCHAR(64)  NOT NULL                COMMENT '平台订单号（R+时间戳+序号），全局唯一，入账幂等键',
    `user_id`         BIGINT       NOT NULL                COMMENT '充值平台用户',
    `channel`         VARCHAR(16)  NOT NULL                COMMENT 'WECHAT / ALIPAY',
    `sku_points`      INT          NOT NULL                COMMENT '购买点数（含赠送）',
    `amount_fen`      BIGINT       NOT NULL                COMMENT '应付金额（分）——内部一律分，杜绝浮点',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/PAYING/PAID/CREDITED/EXPIRED',
    `code_url`        VARCHAR(512) NULL                    COMMENT '渠道二维码链接 / 跳转 URL',
    `trade_no`        VARCHAR(64)  NULL                    COMMENT '渠道支付流水号（回调回填）',
    `paid_amount_fen` BIGINT       NULL                    COMMENT '实付金额（回调校验，须 == amount_fen）',
    `expire_at`       DATETIME     NOT NULL                COMMENT '二维码有效期（15min）',
    `paid_at`         DATETIME     NULL                    COMMENT '支付成功时间',
    `credited_at`     DATETIME     NULL                    COMMENT '入账+签发完成时间',
    `retry_count`     INT          NOT NULL DEFAULT 0      COMMENT '入账外调重试次数（v7.0 补录）',
    `next_retry_at`   DATETIME     NULL                    COMMENT '下次重试时间（1/5/15min 退避，v7.0 补录）',
    `last_error`      VARCHAR(256) NULL                    COMMENT '最近一次外调失败原因（v7.0 补录）',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order` (`order_no`),
    KEY `idx_user_created` (`user_id`, `created_at`),
    KEY `idx_status_expire` (`status`, `expire_at`)   -- 对账任务扫临期 PAYING
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='充值订单表（充值平台独立库）：状态机 + 幂等 + 兜底查单的载体';
```

**用户体系（V1 最简）**：手机号 / 邮箱 \+ 验证码登录（JWT 会话）；user 表 \+ 验证码发送限流；user 与 AppKey 一对多（首次购买自动签发，后续充值默认入第一把 key 的账户，可在 APIKey 管理页指定）。

## 11\. 决策记录

|决策|理由|
|---|---|
|一次请求 = 一个 Skill|极简|
|纯黑盒（无嵌套感知）|Skill 内部组合自己解决|
|纯 async \+ 结果双通道（回调 / 轮询）|WorkBuddy 等无回调能力；3\-5 分钟任务同步等待不现实，sync 模式砍掉|
|轮询为一等公民|终态查询响应与回调同构（含 artifacts/billing），前端客户端零回调依赖；先落产物再置终态，无「完成无结果」竞态|
|两层输入（materials \+ instructions）|params 砍掉——AI Skill 自己理解自然语言|
|无 Schema 校验|上传和执行都更简单|
|凭据平台管|LiteLLM \+ env；OSS 凭据不出平台（预签名 URL）|
|版本不覆盖|sha256 \+ 沙箱镜像 digest|
|计费 = 预冻结\-结算\-对账|并发不花超、失败退款、结算消息丢失有兜底|
|FREE = 0 点平台执行 \+ package 公开分发 \+ 埋点采集 IO|0 点执行降低试用门槛换数据，价值分析后择优转收费版；package 端点保留为离线分发通道；埋点技术上可被摘除，样本尽力而为|
|key 即账户（V1）|扣点模型最简；多 key 共账后续加账户主体层|
|充值平台管支付、billing 管账|支付合规与账务核心分离|
|大文件 presign 直传|网关不做大文件中转|
|每任务 K8s Job 沙箱|不可信代码隔离 \+ 按任务弹性伸缩；缓存后冷启动 5\-10s，对分钟级任务无感|
|不做 DAG / 配置态内核|与纯黑盒冲突，Skill 内部自己组合|
|不做 batch；砍 Git 导入；MCP 预留|count 已覆盖多产物；zip 上传够用|
|不做 Agent Runtime / 意图路由|会话与选 Skill 是调用方（如 WorkBuddy）的能力；平台只做执行 \+ 结果交付|
|Milvus / Langfuse 自建|向量规模与观测数据自主可控|
|不分批交付：全部功能一次交付|范围即交付清单，仅定开发顺序、不做批次切割（评审 \#13 不采纳）|
|MQ 选型 RocketMQ|延迟消息做回调退避、内置 DLQ；事务消息留作受理 Outbox 演进|
|超产 = 交付不截断、按 min\(实际, count\) 计费|对调用方友好；freeze 已封顶平台风险|
|沙箱出公网可绕过模型网关（旁路承认）|黑盒\+开放出口的固有权衡：绕过计量/审计/回退；per\-skill egress 白名单列为演进项|
|素材统一落平台 OSS，agent 场景由前端脚本代上传|presign 直传凭据不出平台；link 类由沙箱自行下载，agent 平台零存储责任|
|V1 不做版本流量灰度|退款\+秒级回滚\+成功率监控已覆盖爆炸半径；canary\_config 路由钩子预留（gateway 版本解析处集中分流）|
|支付回调幂等 \+ 金额校验 \+ 查单兜底|与执行平台对账同理念：先落 PAID 再外调入账，外调失败重试任务推进；金额一律用分|
|recharge\-service 独立，不并入 billing\-service|支付公网回调暴露面与渠道职责不进账务核心；orderNo 幂等入账即防腐层；第三方充值渠道不可用，自建|

**v7\.0 新增决策**：① **模型网关自研（gateway 内置 /v1/chat/completions）**——路由即 model\_provider 表配置、实时生效，免维护外部 LiteLLM；代价：spend 日志 / Langfuse 集成移除，usage 全靠 toolset 上报。② **FREE 由「仅分发」改为「0 点可执行」**——修复 FREE Skill 无法执行的功能缺陷；0 点受理不冻结（hold\_id=NULL），package 端点保留为离线分发通道，两者不互斥。③ **AGENT 包类型（SKILL\.md）**——兼容 Claude/Cursor 生态技能包零改造接入（agent runner \+ agent\-skill\-bridge 桥）。

---

### 



