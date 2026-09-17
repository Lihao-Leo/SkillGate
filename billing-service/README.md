# billing-service（计费服务）

SkillGate 的计费服务（技术方案 v7.0 §3.2/§4.8）：点数账户（key 即账户）、预校验、冻结/结算/退款、不可变流水、对账兜底。独立部署、独立审计。

## 核心机制（预冻结-结算-对账闭环）

| 操作 | 语义 | 幂等性 |
|-|-|-|
| `freeze(taskId, appKey, amount)` | balance → frozen 单行原子迁移（`WHERE balance >= :amt`，天然防并发花超） | 按 taskId（uk_task） |
| `settle(taskId, actualPoints)` | 实扣从 frozen 划扣、差额解冻；实扣封顶冻结额 | 按 hold 状态（非 FROZEN 直接返回） |
| `release(holdId / taskId)` | 全额退款（受理回滚 / PENDING 取消 / 对账兜底） | 同上 |
| `recharge / adjust` | 入账 / 人工调整（负向不得调穿） | 受信管理通道 |
| 对账任务（每 5min） | 扫 FROZEN>24h：任务已终态→按 pricing 快照补结算或释放；非终态→先置 CANCELLED 再释放（止损）+ 高级告警；无任务记录→孤儿释放 | settle/release 天然幂等 |

结算主路径为 worker → billing 同步 RPC；`skill-settle` MQ（`skill-billing.mq.enabled=true` 开启）为兜底重试通道，at-least-once 语义由 hold 状态机消化重复。

## API

内部 API（`/internal/billing/**`，Header `X-Internal-Token`）：

| 接口 | 说明 |
|-|-|
| `POST /internal/billing/freeze` | 冻结（余额不足 40201 / HTTP 402） |
| `POST /internal/billing/settle` | 结算 `{taskId, actualPoints}` |
| `POST /internal/billing/release` | 释放 `{holdId}` 或 `{taskId}` |
| `GET /internal/billing/balance?appKeyId=` | 余额 |
| `GET /internal/billing/holds?taskId=` | 冻结单状态（gateway 终态 billing 块数据源） |
| `GET /internal/billing/transactions?appKeyId=&taskId=` | 流水分页 |
| `POST /internal/billing/accounts` `recharge` `adjust` | 开户 / 入账 / 调整 |

统一响应 `{code, message, data}`；错误码与 HTTP 状态同时返回（40201→402、40401→404、40001→400）。

## 运行

```bash
mvn spring-boot:run          # local profile：H2 内存库 + 对账任务
mvn test                     # 27 个测试

# dev：本地真实设施（MySQL dev-skill + Redis + RocketMQ），凭据经环境变量注入
MYSQL_USER=<账号> MYSQL_PASSWORD=<密码> SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
SPRING_PROFILES_ACTIVE=prod \
MYSQL_HOST=... MYSQL_USER=... MYSQL_PASSWORD=... \
INTERNAL_TOKEN=... \
java -jar target/billing-service-1.0.0-SNAPSHOT.jar
```

- 生产 DDL：`src/main/resources/db/schema-skill-billing.sql`（skill_billing 库）
- **前提**（§4.8）：skill_platform 与 skill_billing 同 MySQL 实例分库（对账跨库 JOIN）；分实例演进时对账改调 gateway 查询 API
- 发布前先手动跑一轮对账清零悬空 hold（§8.3）：`reconcileService.reconcile()` 经触发器或临时端点调用

## 关键配置

| 键 | 默认 | 说明 |
|-|-|-|
| `skill-billing.internal-token` | 变更值 | 服务间认证（与 gateway 共享，经 Secret 注入） |
| `skill-billing.reconcile.cron` | `0 */5 * * * ?` | 对账周期 |
| `skill-billing.reconcile.overdue-hours` | 24 | 悬空判定窗口 |
| `skill-billing.mq.enabled` | false | skill-settle 兜底消费（生产 true） |

## 代码结构

```
common/     统一响应、错误码、全局异常
config/     内部认证拦截器、MyBatis-Plus、Web 配置
controller/ 内部 API + 探针
dal/        实体与 Mapper（冻结/结算为 §4.8 真实单行原子 SQL；对账跨库查询）
dto/        请求/响应 record
mq/         skill-settle 兜底消费（SmartLifecycle）
service/    BillingService（幂等编排）/ BillingTxService（事务原语）/ ReconcileService（对账）
```
