# skill-gateway（接入与管理服务）

SkillGate 的统一接入网关（鉴权 / 请求转发 / 执行调度 / 用量统计 / 限流 / 监控，技术方案 v7.0 §3.1）：execute 受理五关、Skill 生命周期与市场、素材双通道、执行查询/取消、账户与管理接口、FREE 埋点。

## 受理五关（POST /api/v1/execute）

```
① HMAC 鉴权（X-Skill-AppKey/Timestamp/Signature）→ 40101
② Redis 限流/配额（QPS / 日调用量 / 租户并发 RUNNING）→ 42901
③ 计费预校验+冻结（同步调 billing-service）→ 40201（带 rechargeUrl）
④ 调度（skill-execute MQ；可感知失败当场回滚）→ 50001
⑤ 返回 {taskId, resolvedVersion, count, status:PENDING}
```

结果获取双通道：回调（worker HMAC POST callbackUrl，域名白名单+保留地址校验在受理时完成）或轮询 `GET /api/v1/executions/{taskId}`（终态响应与回调 body 同构）。

### 请求签名（与回调签名同一 AppSecret）

```
X-Skill-AppKey / X-Skill-Timestamp（±5min 容差）
X-Skill-Signature = hex( HMAC-SHA256( secret,
    appKey "\n" timestamp "\n" METHOD "\n" path[?query] "\n" sha256Hex(body) ) )
```

- multipart 上传的签名 body 摘要取空串（大文件不缓存；方法与路径仍签名，传输安全由 TLS 保证）

## API 总览

| 分组 | 接口 |
|-|-|
| 执行 | `POST /execute`、`GET /executions/{taskId}`、`POST /executions/{taskId}/cancel` |
| Skill | `POST /skills/upload`、`GET /skills`、`GET /skills/{code}`、`PUT .../default-version`、`PUT .../config`（即时生效）、`GET .../package`（PUBLIC+已发布放行）、`GET .../invocation-spec?format=markdown|openapi|tool-schema` |
| 市场 | `GET /marketplace/skills` |
| 素材 | `POST /materials/presign`（15min 预签名 PUT）、`POST /materials/{id}/confirm`、`POST /materials/upload`（multipart ≤100MB） |
| 账户 | `GET /account/balance`、`GET /account/transactions?taskId=` |
| 管理 | `POST /admin/app-keys`（secret 一次性返回）、`POST /admin/credits/recharge`（Header `X-Admin-Token`） |
| 埋点 | `POST /telemetry/skill-io`（Header `X-Skill-Token: skt_...`） |

错误码见 §5.6（40401 为本服务扩展：资源不存在，承载轮询「404 视为终态」语义）。

## 运行

```bash
mvn spring-boot:run          # local：H2 + 进程内 OSS/KV + Noop MQ（需先启动 billing-service）
mvn test                     # 71 个测试

# dev：本地真实设施（MySQL dev-skill 单库 + Redis 127.0.0.1:6379 + RocketMQ 127.0.0.1:9876，端口 8888）
# 注意：RocketMQ broker 以 host.docker.internal 注册地址时，宿主机需可解析该域名
#   sudo sh -c 'echo "127.0.0.1 host.docker.internal" >> /etc/hosts'
MYSQL_USER=<账号> MYSQL_PASSWORD=<密码> SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
SPRING_PROFILES_ACTIVE=prod \
MYSQL_HOST=... CRYPTO_KEY=...(base64 32B) ADMIN_TOKEN=... INTERNAL_TOKEN=... \
ROCKETMQ_NAMESRV=... OSS_ENDPOINT=... OSS_BUCKET=... OSS_ACCESS_KEY=... OSS_SECRET_KEY=... \
BILLING_BASE_URL=http://billing:8081 \
java -jar target/skill-gateway-1.0.0-SNAPSHOT.jar
```

- 生产 DDL：`src/main/resources/db/schema-skill-platform.sql`（skill_platform 库）
- prod profile 装配：MySQL + Redis + S3 兼容 OSS + RocketMQ；local profile 全部为进程内替身，开箱即跑

## 关键不变量（与 worker 的契约）

- **幂等**：clientRequestId 同 AppKey 唯一；并发冲突败者立即释放 hold
- **回滚**：MQ 发送失败 → 删 execution + 释放 hold + 50001，不留悬空
- **取消**：PENDING → CANCELLED（直接退款）；RUNNING → CANCELLING（worker reconciler 收殓）；终态幂等
- **快照**：执行时 output/pricing 配置快照入 execution，改价不影响进行中任务
- **并发计数**：受理获取 Redis 租户计数，终态由 worker 落库、`RunningCounterRepairJob` 每分钟按 DB 真值重置（漂移收敛）

## 代码结构

```
common/      统一响应、错误码（§5.6）、全局异常
security/    HMAC 验签、AppKey/Admin 拦截器、请求体缓存过滤器、AES-GCM 凭据加密
ratelimit→   service/RateLimitService（QPS/日配额/租户并发，KvStore 端口）
infra/       KvStore / ObjectStorage / ExecuteMessageProducer 端口 + local（进程内）与 prod（Redis/S3/RocketMQ）实现
billing/     BillingClient 端口 + HTTP 实现（超时/错误码透传）
dal/         10 张表实体与 Mapper（条件更新承载状态机不变量）
service/     Execute（五关）/ ExecutionQuery（同构查询+取消）/ Skill / Material / Account / Telemetry / InvocationSpecExporter
controller/  REST 入口
```
