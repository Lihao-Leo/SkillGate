# recharge-service（充值平台）

SkillGate 的充值服务（技术方案 v7.0 §10.4）：订单状态机、支付渠道抽象、C 端用户体系。**独立 recharge 库、独立部署**——不并入 billing-service（安全暴露面 / 职责审计 / 迭代节奏三条理由见方案）。

**职责一句话：recharge-service 碰渠道和用户，billing 只碰账户和流水，两者间只有 §5.7 两个 admin API（签发 / 入账）——orderNo 幂等入账即防腐层。**

## 订单状态机

```
CREATED → PAYING    渠道统一下单成功（code_url，15min 有效）
PAYING  → PAID      回调验签通过 且 金额一致（分）；条件更新幂等（重复回调应答成功）
PAID    → CREDITED  调执行平台 recharge 入账（+ 首充签发 AppKey）成功；
                    外调失败由重试任务推进（1/5/15min 退避），超 1h 告警人工
PAYING  → EXPIRED   15min 未支付：渠道关单 + 主动查单确认未付才置过期
退款：V1 不做自动退款，运营经后管 ADJUST 人工处理
```

## 安全与幂等五条（§10.4）

1. **回调验签**：按渠道官方方案验签（微信 APIv3 平台公钥 RSA-SHA256 + AES-256-GCM 解密 / 支付宝 RSA2），**原始报文全量落 `payment_notify_log`** 留档（审计 + 排障回放）
2. **金额校验**：实付（分）== 应付（分）才进 PAID；**内部金额一律用分，杜绝浮点**
3. **回调幂等**：`UPDATE ... SET status=PAID WHERE order_no=? AND status=PAYING`——影响行数 0 即重复回调，直接应答成功
4. **入账幂等**：调执行平台 recharge 带 `orderNo`，billing 以 `(type=RECHARGE, orderNo)` 唯一约束幂等，重复调用返回已入账流水
5. **查单兜底**：每分钟扫临期/已过期订单主动查渠道——已支付补 PAID + 入账（回调丢失），未支付且已过期则关单置 EXPIRED

> 注：回调 header 在 Controller 边界归一化为大小写不敏感（Tomcat 会将 header 名小写）。

## API

| 分组 | 接口 | 说明 |
|-|-|-|
| 认证 | `POST /api/user/auth/send-code` `{identifier}` | 手机号/邮箱验证码（60s 一条、5 条/日） |
| | `POST /api/user/auth/login` `{identifier, code}` | JWT 会话（7d） |
| | `GET /api/user/auth/skus` | 套餐列表（阶梯赠送） |
| 订单 | `POST /api/user/orders` `{channel, skuId? \| customAmountFen?}` | 下单（渠道下单成功转 PAYING） |
| | `GET /api/user/orders/{orderNo}` | 轮询状态；CREDITED 附一次性 key 展示 |
| key 管理 | `GET /api/user/keys` | 脱敏列表（`sk-****xxxx`） |
| | `POST /api/user/keys`、`POST .../{id}/reset-secret`、`POST .../{id}/disable` | 新增（完整值一次性）/ AppSecret 轮换 / 禁用 |
| 回调 | `POST /api/notify/{channel}/pay` | 渠道异步通知（按渠道协议应答） |

## 渠道抽象

```java
interface PaymentChannel {
    create(order)      // 统一下单：微信 Native code_url / 支付宝 precreate qr_code
    verifyNotify(raw)  // 验签 + 解密 → 订单号 / 流水号 / 实付（分）
    query(order)       // 主动查单（过期判定 + 兜底）
    closeOrder(order)  // 关单 best-effort
    ackResponse(ok)    // 渠道要求的应答体
}
```

内置适配器：**WeChatNativeChannel**（APIv3：商户 RSA 签名 / 平台公钥验签 / AES-GCM 资源解密）、**AlipayPrecreateChannel**（RSA2，V1 预留）、**MockChannel**（local/联调，生产 `enabled: false`）。新渠道加 Bean 即生效（`ChannelRegistry` 自动注册）。商户证书 / APIv3 key 经 Secret 注入，永不落日志。

## 运行

```bash
mvn spring-boot:run          # local：H2 + Mock 渠道（需先启动 gateway，billing 由 gateway 代理）
mvn test                     # 20 个测试

# dev：本地真实设施（MySQL dev-skill 单库；gateway 位于 http://127.0.0.1:8888）
MYSQL_USER=<账号> MYSQL_PASSWORD=<密码> SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
SPRING_PROFILES_ACTIVE=prod \
MYSQL_HOST=... MYSQL_USER=recharge MYSQL_PASSWORD=... \
JWT_SECRET=... CRYPTO_KEY=... \
GATEWAY_BASE_URL=http://gateway:8888 GATEWAY_ADMIN_TOKEN=... \
WECHAT_APPID=... WECHAT_MCHID=... （微信商户凭据，Secret 注入） \
java -jar target/recharge-service-1.0.0-SNAPSHOT.jar
```

- 生产 DDL：`src/main/resources/db/schema-recharge.sql`（recharge 库；`recharge_order` 在方案 DDL 之上追加了 `retry_count/next_retry_at/last_error` 三列承载退避重试，只加列向前兼容）
- `payment_notify_log` 结构方案从略，由本服务定义（原始报文 / 验签结论 / 金额 / 处理状态）
- 首充流程：无主 key 用户先签发（AppKey+Secret，secret AES 暂存待**一次性展示**后清空）再入账；后续充值默认入主 key 账户

## 关键配置

| 键 | 默认 | 说明 |
|-|-|-|
| `recharge.platform.base-url` / `admin-token` | — | gateway 管理面（X-Admin-Token 专属凭据） |
| `recharge.channels.mock.enabled` | local true / prod false | Mock 渠道（生产禁用） |
| `recharge.channels.wechat.*` | — | 微信 APIv3 商户凭据（Secret） |
| `recharge.jobs.credit-retry.cron` | 每 1min | 入账退避重试 + 超 1h 告警 |
| `recharge.jobs.order-expire.cron` | 每 1min | 查单兜底 / 关单过期 |
| `recharge.auth.dev-echo-code` | local true / prod **false** | 验证码回显（仅联调） |

## 代码结构

```
common/      统一响应、错误码、全局异常、订单号生成
security/    JWT（HS256 手写最简实现）、会话过滤器、AES-GCM（待展示密钥加密）
channel/     PaymentChannel + 微信/支付宝/Mock 适配器 + ChannelCrypto（RSA/AES-GCM 原语）
platform/    SkillPlatformClient 端口 + gateway 管理面 HTTP 实现
dal/         recharge_order / payment_notify_log / user_account / verification_code / user_app_key / sku
service/     UserAuth / Sku / Order / Notify（回调五条）/ Credit（PAID→CREDITED + 退避）/ UserKey(BFF)
service/jobs/ CreditRetryJob（入账重试）/ OrderExpireJob（查单兜底）
controller/  用户端 BFF + 回调 webhook + 探针
```
