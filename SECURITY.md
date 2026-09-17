# 安全策略

## 报告漏洞

如果你发现了安全漏洞，请**不要**提交公开 Issue。请通过 GitHub Security Advisories（仓库页 → Security → Report a vulnerability）私下报告。

我们会在 72 小时内确认，并在修复后公开致谢报告者（除非你希望匿名）。

## 范围

- `skill-gateway` / `billing-service` / `skill-worker` / `recharge-service`
- `frontend/admin-console` / `frontend/user-portal`

## 已知设计边界（不视为漏洞）

- V1 沙箱为 runc + 容器加固（缺陷隔离），非恶意代码防御；独立节点池 / egress 白名单为演进项（见 `docs/ARCHITECTURE.md` §4.6）
- 沙箱出公网可绕过平台模型网关直接调用外部模型（旁路权衡，见决策记录）
- FREE 模式埋点上报可被摘除（样本尽力而为）

## 密钥管理

仓库不含任何真实凭证——所有密钥经环境变量注入（各服务 `.env.example` / `application*.yml` 占位符）。
如果你 fork 后部署，请自行管理密钥并开启 secret scanning。
