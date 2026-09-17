# skill-worker（沙箱执行编排，Python）

SkillGate 的沙箱执行编排服务（技术方案 v7.0 §3.4/§4.5-4.7/§4.10/§5.5）：消费 `skill-execute`，把每个任务编排为一个受加固的 K8s Job（沙箱），按「先落产物 → 再结算 → 最后置终态」的顺序不变量交付结果，并经回调 / 轮询双通道返回。

## scheduler（Deployment 常驻）

```
consume skill-execute
  → load execution；非 PENDING 跳过（幂等）
  → 条件更新 PENDING→RUNNING（防双消费；影响行数=1 才继续）
  → 预备工作区（共享 PVC）：下载 input.json + Skill 包（sha256 校验、解压）
  → create Job（activeDeadlineSeconds=timeoutSeconds，§4.6 加固：非 root /
     readOnlyRootFilesystem / drop ALL / seccomp RuntimeDefault / 资源两档 2C4G、视频 4C8G）
  → watch 终态（期间轮询 CANCELLING 标记 → 杀 Job 走失败路径）
  → collect artifacts/（manifest.json 优先，缺省按后缀推断；单文件 500MB / 总量 2GB /
     数量 maxCount×2 或 ≤4，超限 OUTPUT_LIMIT）
  → 空产物 → FAILED(EMPTY_OUTPUT) 全额退款
  → upload OSS {tenantId}/artifacts/{taskId}/ + insert artifact 表     ① 先落产物
  → settle 同步 RPC（重试 1 次；双次失败发 skill-settle MQ 兜底，      ② 再结算
     回调带预估值 settled=false）
  → 置终态（条件更新，仅 RUNNING/CANCELLING 可进）                    ③ 最后置终态
  → 有 callbackUrl 则发 skill-callback-retry（HMAC 签名体）
```

## 看门狗（每 1min，§4.10）

| 段 | 行为 |
|-|-|
| PENDING | >5min 重投 MQ；>30min 置 FAILED(INTERNAL) + 全额退款 |
| RUNNING | 超 timeout+5min 查 Job 实际态：SUCCEEDED 补采集/结算/置终态；仍在跑等下轮；missing/FAILED 连续 2 周期确认 → delete Job → 条件回滚 PENDING → 重投（不变量接管，杜绝重投死循环） |
| CANCELLING | 杀 Job → 按失败路径结算（PER_EXECUTION 全退 / METERED 按已发生用量）→ CANCELLED |

## 回调（§5.5）

- 签名：`X-Skill-Signature = HMAC-SHA256(AppSecret, timestamp + body)` + `X-Skill-Timestamp`（±5min 容差）；AppSecret 从 app_key.secret_cipher AES-256-GCM 解密（**与 gateway Java 实现跨语言同构**，测试内置 Java 生成向量）
- 退避：RocketMQ 延迟等级 5/9/14（=1/5/15min），3 次失败进 `skill-callback-dlq` + DEAD_LETTER
- 重复投递由调用方按 taskId 幂等

## toolset（Job 内注入 = skill-sdk 薄包）

| 能力 | 说明 |
|-|-|
| `load_input()` | 读 env `SKILL_INPUT` 指向的 input.json（§4.7：不走 stdin/命令行） |
| `save_artifact(name, content, type, meta)` | 写 artifacts/ + manifest 登记 |
| `report_progress(0-100)` | Redis `task:progress:{taskId}`（查询接口可见粒度=轮询间隔） |
| `LlmClient.chat` | OpenAI 兼容经 env 走 LiteLLM；usage 自动上报 + X-Task-Id 透传 |
| `VideoGen.generate` | 特殊协议提交/轮询/取结果（指数退避至 30s）；回退链；进度映射 10%→95% |
| `UsageReporter` | Redis `task:usage:{taskId}` 分类计数 + **单任务调用上限硬闸**（BudgetExceeded） |

## 运行与测试

```bash
python3 -m pytest                  # 54 个测试（零外部依赖：sqlite/进程内替身）
pip install -r requirements.txt    # 生产依赖
python -m skill_worker.scheduler.main   # K8s 内运行（env 见 config.py）
```

- 外部依赖全部端口化：`Database`(pymysql/sqlite) / `KvPort`(redis/内存) / `OssPort`(boto3/内存) / `SandboxPort`(kubernetes/Fake) / `MqPort`(rocketmq/内存) / `BillingPort`(HTTP)
- 生产装配见 `scheduler/main.py`（PushConsumer 回调驱动 + 看门狗线程）；本地/测试经 `tests/fakes.py` 装配替身
- 镜像：`Dockerfile`（scheduler）+ `sandbox-images/base-python/`（沙箱镜像与 skill-entrypoint：pip wheelhouse 缓存 → `python main.py`）

## 与其他服务的契约

| 依赖 | 说明 |
|-|-|
| MySQL skill_platform | 直写 execution / artifact（状态机条件更新）；读 skill（abilities/镜像选型）/ app_key（回调签名） |
| billing-service | `POST /internal/billing/settle`（同步主路径，失败立即重试 1 次） |
| Redis | usage / progress / 看门狗防抖计数 |
| OSS | 产物上传（{tenantId}/artifacts/{taskId}/）+ 包/入参读取 |
| gateway | 无直接调用（终态经 DB + 回调通道交付；轮询由 gateway 读 DB） |
