"""skill-worker：Skill 执行平台的沙箱执行编排服务（技术方案 v6.9 §3.4/§4.5-4.7/§4.10/§5.5）。

scheduler（常驻 Deployment）：
- 消费 skill-execute → 条件更新接手 → 创建 K8s Job（沙箱）→ watch 终态
- 采集 artifacts/ → 上传 OSS → 同步结算（RPC 主路径，MQ 兜底）→ 置终态 → 回调
- 顺序不变量：先落产物 → 再结算 → 最后置终态（无「完成但无结果」竞态）

toolset（Job 内注入）：模型调用 / video_gen / kb_query / 进度与 usage 上报。
"""

__version__ = "1.0.0"
