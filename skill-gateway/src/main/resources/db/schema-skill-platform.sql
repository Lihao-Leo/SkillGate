-- =====================================================================
-- skill_platform 库生产 DDL（技术方案 v6.5 §6.1-6.7、§6.11-6.12，MySQL 8）
-- =====================================================================

CREATE TABLE IF NOT EXISTS `skill` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `tenant_id`          VARCHAR(64)  NOT NULL                COMMENT '租户标识（由 AppKey 推导）',
    `skill_code`         VARCHAR(128) NOT NULL                COMMENT 'Skill 唯一编码，同租户下唯一',
    `name`               VARCHAR(256) NOT NULL                COMMENT 'Skill 显示名称',
    `description`        TEXT                                 COMMENT 'Skill 功能描述，供市场与管理页展示',
    `visibility`         VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE' COMMENT '可见性：PRIVATE=仅本租户 PUBLIC=市场公开',
    `kind`               VARCHAR(16)  NOT NULL DEFAULT 'CODE'   COMMENT '包类型：CODE=main.py 代码技能 AGENT=SKILL.md 智能体技能（平台 agent runner 执行）',
    `required_abilities` JSON                                 COMMENT '所需平台能力列表',
    `output_config`      JSON                                 COMMENT '产出能力声明（运营性配置）',
    `pricing_config`     JSON                                 COMMENT '定价（运营性配置）',
    `invocation_spec`    JSON                                 COMMENT '调用说明元数据，一键导出 markdown/openapi/tool-schema',
    `kb_bindings`        JSON                                 COMMENT '默认授权知识库集 [{kbId,topK}]（V2.0 声明绑定；沙箱授权集=声明∪context.kbIds）',
    `telemetry_token`    VARCHAR(64)  NULL                    COMMENT '埋点令牌（skt- 前缀）；NULL=非公开分发',
    `default_version`    VARCHAR(32)  NULL                    COMMENT '当前默认执行版本号',
    `status`             TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1=启用 0=禁用',
    `created_by`         VARCHAR(64)  NOT NULL                COMMENT '上传人',
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_skill` (`tenant_id`, `skill_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Skill 元数据表';

CREATE TABLE IF NOT EXISTS `skill_version` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `skill_id`        BIGINT       NOT NULL                COMMENT '关联 skill 表 id',
    `version`         VARCHAR(32)  NOT NULL                COMMENT '语义化版本号；同一 skill 下唯一',
    `oss_key`         VARCHAR(512) NOT NULL                COMMENT 'skill/{tenantId}/{skillCode}/{version}/skill.zip（桶内相对路径）',
    `oss_url`         VARCHAR(1024) NULL                    COMMENT '对象绝对访问地址（bucket endpoint + key 稳定指针；读写仍走预签名）',
    `package_sha256`  CHAR(64)     NOT NULL                COMMENT '包 SHA-256（复现锚点 + 沙箱缓存 key）',
    `package_size`    BIGINT       NOT NULL                COMMENT '包大小（字节）',
    `changelog`       TEXT                                 COMMENT '本版变更说明',
    `status`          TINYINT      NOT NULL DEFAULT 0      COMMENT '0=已上传待审核 1=已发布可执行 2=已废弃',
    `uploaded_by`     VARCHAR(64)  NOT NULL                COMMENT '上传人',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_skill_version` (`skill_id`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Skill 版本表：包永不覆盖，保障历史任务可复现';

CREATE TABLE IF NOT EXISTS `execution` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `task_id`           VARCHAR(64)   NOT NULL COMMENT '全局唯一任务ID',
    `client_request_id` VARCHAR(128)  NULL    COMMENT '调用方幂等键；同 AppKey 下唯一',
    `app_key_id`        VARCHAR(64)   NOT NULL COMMENT '发起调用的 AppKey',
    `tenant_id`         VARCHAR(64)   NOT NULL COMMENT '租户标识',
    `skill_code`        VARCHAR(128)  NOT NULL COMMENT '执行的 Skill 编码',
    `skill_version`     VARCHAR(32)   NOT NULL COMMENT '实际解析使用的版本号',
    `package_sha256`    CHAR(64)      NOT NULL COMMENT 'Skill 包 SHA-256，复现锚点之一',
    `sandbox_digest`    VARCHAR(64)   NULL    COMMENT '沙箱镜像 digest，复现锚点之二',
    `input_ref`         VARCHAR(1024) NULL    COMMENT '完整入参 JSON 的 OSS key',
    `context`           JSON          NULL    COMMENT '调用方透传上下文（≤4KB）',
    `expected_count`    INT           NULL    COMMENT '期望产出数',
    `count_overridden`  TINYINT       NOT NULL DEFAULT 0 COMMENT 'count 超限经 override 放行：1=是',
    `output_config_snapshot` JSON     NULL    COMMENT '执行时 output_config 快照',
    `pricing_snapshot`  JSON          NULL    COMMENT '执行时 pricing_config 快照（计费按此复算）',
    `hold_id`           VARCHAR(64)   NULL    COMMENT '计费冻结单（FREE 任务为 NULL）',
    `status`            VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/CANCELLING/SUCCEEDED/FAILED/CANCELLED',
    `progress`          TINYINT       NULL    COMMENT '进度 0-100（NULL=不支持）',
    `error_code`        VARCHAR(32)   NULL    COMMENT 'TIMEOUT/BUDGET_EXCEEDED/EMPTY_OUTPUT/OUTPUT_LIMIT/NEED_MEDIA/INTERNAL/CANCELLED',
    `error_message`     TEXT          NULL    COMMENT '失败详细描述',
    `callback_url`      VARCHAR(512)  NULL    COMMENT '回调地址（可选通道）',
    `callback_status`   VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/RETRYING/DEAD_LETTER/NO_CALLBACK',
    `model_calls`       INT           NOT NULL DEFAULT 0 COMMENT '模型调用次数',
    `tokens_used`       INT           NOT NULL DEFAULT 0 COMMENT 'token 消耗',
    `duration_ms`       BIGINT        NULL    COMMENT '执行时长（毫秒）',
    `created_by`        VARCHAR(64)   NOT NULL COMMENT '发起人（AppKey 标识）',
    `created_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '任务创建时间',
    `started_at`        DATETIME      NULL    COMMENT '开始执行时间',
    `finished_at`       DATETIME      NULL    COMMENT '完成/终止时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_id` (`task_id`),
    UNIQUE KEY `uk_key_client_req` (`app_key_id`, `client_request_id`),
    KEY `idx_tenant_status` (`tenant_id`, `status`),
    KEY `idx_app_key` (`app_key_id`, `created_at`),
    KEY `idx_status_started` (`status`, `started_at`),
    KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='执行记录表：统一异步；idx_status_started 供看门狗扫描';

CREATE TABLE IF NOT EXISTS `execution_event` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `task_id`     VARCHAR(64)  NOT NULL COMMENT '关联执行任务ID',
    `tenant_id`   VARCHAR(64)  NOT NULL COMMENT '租户标识',
    `seq`         INT          NOT NULL COMMENT '同任务内递增序号（按 task_id+seq 排序展示）',
    `stage`       VARCHAR(32)  NOT NULL COMMENT '阶段：ACCEPT/AUTH/RATE_LIMIT/FREEZE/ENQUEUE/CLAIM/STAGE/SANDBOX/COLLECT/ARTIFACT_PUT/SETTLE/FINALIZE/CALLBACK/WATCHDOG/KB_RETRIEVE',
    `status`      VARCHAR(16)  NOT NULL COMMENT 'OK / FAILED / SKIPPED',
    `service`     VARCHAR(16)  NOT NULL COMMENT 'gateway / worker / billing',
    `duration_ms` BIGINT       NULL     COMMENT '该阶段耗时',
    `detail`      JSON         NULL     COMMENT '扩展：error_code/hold_id/points/attempt/alias/tokens/tail 等',
    `trace_id`    VARCHAR(64)  NULL     COMMENT 'OTel traceId（预留关联 Jaeger）',
    `occurred_at` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事件时间',
    PRIMARY KEY (`id`),
    KEY `idx_task_seq` (`task_id`, `seq`),
    KEY `idx_tenant_time` (`tenant_id`, `occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='执行事件链：观测数据，写入失败仅打日志不阻塞主链路';

CREATE TABLE IF NOT EXISTS `artifact` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `artifact_id`   VARCHAR(64)  NOT NULL COMMENT '全局唯一产物ID',
    `task_id`       VARCHAR(64)  NOT NULL COMMENT '关联的执行任务ID',
    `tenant_id`     VARCHAR(64)  NOT NULL COMMENT '租户标识（冗余存储）',
    `type`          VARCHAR(32)  NOT NULL COMMENT '产物类型',
    `oss_key`       VARCHAR(512) NOT NULL COMMENT '{tenantId}/artifacts/{taskId}/{filename}',
    `file_size`     BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
    `content_type`  VARCHAR(128) NULL    COMMENT 'MIME 类型',
    `meta`          JSON         NULL    COMMENT '扩展元数据',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '产出时间',
    `expires_at`    DATETIME     NULL    COMMENT '过期清理时间（默认90天）；NULL=永久保留',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_artifact_id` (`artifact_id`),
    KEY `idx_task` (`task_id`),
    KEY `idx_tenant_expires` (`tenant_id`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='执行产物表';

CREATE TABLE IF NOT EXISTS `material` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `material_id`        VARCHAR(64)  NOT NULL COMMENT '全局唯一素材ID',
    `tenant_id`          VARCHAR(64)  NOT NULL COMMENT '租户标识（受理时校验 oss:// 归属）',
    `material_type`      VARCHAR(32)  NOT NULL COMMENT 'video/image/audio/document/data/other',
    `oss_key`            VARCHAR(512) NOT NULL COMMENT '{tenantId}/materials/{materialId}.{ext}',
    `upload_channel`     VARCHAR(16)  NOT NULL DEFAULT 'multipart' COMMENT 'multipart / presign',
    `status`             TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待上传 1=可用 2=确认失败',
    `filename`           VARCHAR(256) NULL    COMMENT '原始文件名',
    `content_type`       VARCHAR(128) NULL    COMMENT 'MIME 类型',
    `file_size`          BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
    `sha256`             CHAR(64)     NULL    COMMENT '内容 SHA-256（confirm 时回写校验）',
    `created_by`         VARCHAR(64)  NULL    COMMENT '上传人（AppKey 标识）',
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    `last_referenced_at` DATETIME     NULL    COMMENT '最近被执行请求引用时间',
    `expires_at`         DATETIME     NULL    COMMENT '过期时间；NULL=永久',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_material_id` (`material_id`),
    KEY `idx_tenant` (`tenant_id`),
    KEY `idx_cleanup` (`last_referenced_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='素材表：双通道上传的输入文件；text/link 语义类型不落库';

CREATE TABLE IF NOT EXISTS `model_provider` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `alias`           VARCHAR(64)   NOT NULL COMMENT '模型能力别名',
    `provider`        VARCHAR(64)   NOT NULL COMMENT '供应商名称',
    `model_name`      VARCHAR(128)  NOT NULL COMMENT '供应商侧的模型名称',
    `endpoint`        VARCHAR(512)  NOT NULL COMMENT 'API 端点地址',
    `api_key_cipher`  VARCHAR(512)  NOT NULL COMMENT 'API Key（AES 加密存储）',
    `fallback_alias`  VARCHAR(64)   NULL    COMMENT '回退目标 alias；NULL=无回退',
    `max_qps`         INT           NOT NULL DEFAULT 10 COMMENT '该模型最大 QPS',
    `cost_per_1k_input_tokens`  DECIMAL(10,4) NULL COMMENT '每 1k 输入 token 成本（点数）',
    `cost_per_1k_output_tokens` DECIMAL(10,4) NULL COMMENT '每 1k 输出 token 成本（点数）',
    `cost_per_call`   DECIMAL(10,4) NULL COMMENT '按次成本（视频生成等）',
    `status`          TINYINT       NOT NULL DEFAULT 1 COMMENT '1=启用 0=禁用',
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_alias` (`alias`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='模型供应商配置表：Skill 只认 alias';

CREATE TABLE IF NOT EXISTS `app_key` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `app_key_id`       VARCHAR(64)  NOT NULL COMMENT '对外 AppKey（sk- 前缀）',
    `tenant_id`        VARCHAR(64)  NOT NULL COMMENT '租户标识',
    `secret_cipher`    VARCHAR(512) NOT NULL COMMENT 'AppSecret（AES 加密存储）',
    `quota`            JSON         NULL    COMMENT '限流配额 {"qps":10,"maxRunning":50,"dailyLimit":10000}',
    `callback_domains` JSON         NULL    COMMENT '回调域名白名单 JSON 数组',
    `status`           TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=禁用',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '签发时间',
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_key` (`app_key_id`),
    KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AppKey 表：V1 key 即账户主体';

CREATE TABLE IF NOT EXISTS `kb` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `kb_id`             VARCHAR(64)  NOT NULL COMMENT '知识库ID',
    `tenant_id`         VARCHAR(64)  NOT NULL COMMENT '租户标识（KB 隔离边界）',
    `name`              VARCHAR(128) NOT NULL COMMENT '知识库名称',
    `embedding_alias`   VARCHAR(64)  NOT NULL COMMENT '向量化模型 alias（经 LiteLLM）',
    `milvus_collection` VARCHAR(128) NOT NULL COMMENT '对应 Milvus collection（含 tenant 前缀）',
    `status`            TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=禁用',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_kb` (`kb_id`),
    KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识库表';

CREATE TABLE IF NOT EXISTS `kb_document` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `doc_id`      VARCHAR(64)  NOT NULL COMMENT '文档ID',
    `kb_id`       VARCHAR(64)  NOT NULL COMMENT '所属知识库',
    `tenant_id`   VARCHAR(64)  NOT NULL COMMENT '租户标识',
    `oss_key`     VARCHAR(512) NOT NULL COMMENT '原文 OSS 路径',
    `chunk_count` INT          NOT NULL DEFAULT 0 COMMENT '已入库分块数',
    `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待入库 3=处理中 1=已入库 2=失败',
    `error_message` VARCHAR(512) NULL COMMENT '入库失败原因（status=2 时可见）',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc` (`doc_id`),
    KEY `idx_kb` (`kb_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识库文档表';

CREATE TABLE IF NOT EXISTS `kb_chunk` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `chunk_id`    VARCHAR(80)  NOT NULL COMMENT '分块ID：{docId}::{idx}',
    `doc_id`      VARCHAR(64)  NOT NULL COMMENT '所属文档',
    `kb_id`       VARCHAR(64)  NOT NULL COMMENT '所属知识库',
    `tenant_id`   VARCHAR(64)  NOT NULL COMMENT '租户标识',
    `chunk_idx`   INT          NOT NULL COMMENT '文档内序号',
    `text`        TEXT         NOT NULL COMMENT '分块原文（预览/引用溯源）',
    `text_hash`   CHAR(64)     NOT NULL COMMENT '内容 SHA-256（变更检测）',
    `embed_model` VARCHAR(128) NOT NULL COMMENT '实际灌入的 embedding 模型（重灌判断依据）',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chunk` (`chunk_id`),
    KEY `idx_doc` (`doc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='KB 分块表：入库幂等与溯源';

CREATE TABLE IF NOT EXISTS `skill_io_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `skill_code`  VARCHAR(128) NOT NULL COMMENT 'Skill 编码',
    `version`     VARCHAR(32)  NULL    COMMENT 'Skill 版本（上报方声明）',
    `input`       JSON         NULL    COMMENT '输入摘要',
    `output`      JSON         NULL    COMMENT '输出产物元数据',
    `status`      VARCHAR(16)  NOT NULL COMMENT 'SUCCEEDED / FAILED',
    `duration_ms` BIGINT       NULL    COMMENT '执行耗时（毫秒）',
    `caller_hint` VARCHAR(128) NULL    COMMENT '调用方环境标识',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上报时间',
    PRIMARY KEY (`id`),
    KEY `idx_skill_created` (`skill_code`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='免费公开分发 Skill 的埋点日志';

CREATE TABLE IF NOT EXISTS `sys_config` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `config_key`    VARCHAR(128) NOT NULL COMMENT '配置键（如 quota.qps）',
    `config_value`  VARCHAR(512) NOT NULL COMMENT '配置值（覆盖 yml 默认）',
    `remark`        VARCHAR(256) NULL    COMMENT '备注',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='平台系统配置：DB 覆盖 yml 默认值';

-- ============================================================
-- V2.0_1 增量（存量库幂等加列；新库由上方 CREATE TABLE 直接建全）
-- MySQL 8 无 ADD COLUMN IF NOT EXISTS，借助 information_schema 判存后 PREPARE 执行
-- ============================================================
SET @ddl_kb_doc = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE kb_document ADD COLUMN error_message VARCHAR(512) NULL AFTER status',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_document' AND COLUMN_NAME = 'error_message');
PREPARE stmt1 FROM @ddl_kb_doc;
EXECUTE stmt1;
DEALLOCATE PREPARE stmt1;

SET @ddl_skill = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE skill ADD COLUMN kb_bindings JSON NULL AFTER invocation_spec',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'skill' AND COLUMN_NAME = 'kb_bindings');
PREPARE stmt2 FROM @ddl_skill;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

-- ============================================================
-- V2.1_1 增量（管理后台账号/权限、告警规则中心、模型调用明细）
-- 新表 CREATE IF NOT EXISTS 对新库/存量库均幂等；加列沿用判存 PREPARE 模式
-- ============================================================

-- 管理后台账号（BCrypt 哈希；超管账号由 AdminUserSeeder 启动时空表种子，避免 SQL 硬编码哈希）
CREATE TABLE IF NOT EXISTS `admin_user` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `username`      VARCHAR(64)  NOT NULL COMMENT '登录名',
    `password_hash` VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希（spring-security-crypto）',
    `display_name`  VARCHAR(64)  NULL    COMMENT '显示名',
    `role_code`     VARCHAR(32)  NOT NULL DEFAULT 'VIEWER' COMMENT '关联 admin_role.role_code',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=禁用',
    `last_login_at` DATETIME     NULL    COMMENT '最近登录时间',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_admin_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='管理后台账号';

CREATE TABLE IF NOT EXISTS `admin_role` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `role_code`  VARCHAR(32) NOT NULL COMMENT '角色编码：SUPER_ADMIN/SKILL_MANAGER/REVIEWER/OPS/VIEWER 及自定义',
    `role_name`  VARCHAR(64) NOT NULL COMMENT '角色名称',
    `remark`     VARCHAR(256) NULL   COMMENT '备注',
    `builtin`    TINYINT     NOT NULL DEFAULT 0 COMMENT '1=预置角色（前端不可删除/改码）',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='后台角色表';

CREATE TABLE IF NOT EXISTS `admin_permission` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `perm_code`  VARCHAR(64) NOT NULL COMMENT '权限点：skill:upload / skill:review / execution:manage 等',
    `perm_name`  VARCHAR(64) NOT NULL COMMENT '权限点名称',
    `group_name` VARCHAR(32) NOT NULL COMMENT '前端菜单分组',
    `sort_no`    INT         NOT NULL DEFAULT 0 COMMENT '组内排序',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_perm_code` (`perm_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='权限点字典';

CREATE TABLE IF NOT EXISTS `admin_role_permission` (
    `id`        BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `role_code` VARCHAR(32) NOT NULL COMMENT '角色编码',
    `perm_code` VARCHAR(64) NOT NULL COMMENT '权限点编码',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_perm` (`role_code`, `perm_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色-权限点映射';

-- 告警规则（worker 经 /internal/runtime-config/alerts 60s 热下发）
CREATE TABLE IF NOT EXISTS `alert_rule` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `rule_code`            VARCHAR(64)  NOT NULL COMMENT '规则编码（告警去重键组成部分）',
    `rule_name`            VARCHAR(128) NOT NULL COMMENT '规则名称',
    `rule_type`            VARCHAR(32)  NOT NULL COMMENT 'RUNNING_TIMEOUT/TOKEN_EXCESS/TASK_FAILED/PENDING_STUCK/CALLBACK_DEAD_LETTER/WATCHDOG_INTERVENTION',
    `skill_code`           VARCHAR(128) NULL COMMENT '限定 Skill 编码；NULL=全部 Skill',
    `threshold_value`      BIGINT       NULL COMMENT '阈值：秒（超时类）/ token 数（超量类）/ 次数',
    `severity`             VARCHAR(16)  NOT NULL DEFAULT 'WARN' COMMENT 'INFO/WARN/CRITICAL',
    `channels`             VARCHAR(128) NOT NULL DEFAULT 'feishu,record' COMMENT '通知通道：feishu=飞书 webhook record=仅落库',
    `dedup_window_seconds` INT          NOT NULL DEFAULT 86400 COMMENT '同任务同规则去重窗口（秒）',
    `enabled`              TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
    `created_at`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rule_code` (`rule_code`),
    KEY `idx_rule_type_enabled` (`rule_type`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='告警规则';

-- 告警记录（待处理数 = status=PENDING 计数；同任务同规则单条，重复触发刷 last_seen_at）
CREATE TABLE IF NOT EXISTS `alert_record` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `rule_id`       BIGINT       NULL COMMENT '触发规则 id（legacy 阈值告警为 NULL）',
    `rule_code`     VARCHAR(64)  NOT NULL COMMENT '规则编码（legacy 告警写 legacy.running-timeout 等）',
    `rule_type`     VARCHAR(32)  NOT NULL COMMENT '同 alert_rule.rule_type',
    `severity`      VARCHAR(16)  NOT NULL DEFAULT 'WARN' COMMENT 'INFO/WARN/CRITICAL',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACKED/RESOLVED',
    `task_id`       VARCHAR(64)  NULL COMMENT '关联任务（backlog 类可 NULL）',
    `skill_code`    VARCHAR(128) NULL COMMENT 'Skill 编码',
    `tenant_id`     VARCHAR(64)  NULL COMMENT '租户标识',
    `title`         VARCHAR(256) NOT NULL COMMENT '告警标题',
    `content`       TEXT         NULL COMMENT '告警正文',
    `detail`        JSON         NULL COMMENT '扩展明细（耗时/tokens/错误码等）',
    `notified`      TINYINT      NOT NULL DEFAULT 0 COMMENT '飞书是否已推送',
    `first_seen_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次触发时间',
    `last_seen_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近触发时间',
    `ack_by`        VARCHAR(64)  NULL COMMENT '认领人（admin username）',
    `ack_at`        DATETIME     NULL COMMENT '认领时间',
    `ack_note`      VARCHAR(512) NULL COMMENT '认领备注',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_rule` (`task_id`, `rule_code`),
    KEY `idx_alert_status_time` (`status`, `first_seen_at`),
    KEY `idx_alert_task` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='告警记录';

-- 逐次模型调用明细（token 消耗与费用；cost_points 由 gateway 落库时按 model_provider 费率折算）
CREATE TABLE IF NOT EXISTS `model_call` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `task_id`           VARCHAR(64)   NOT NULL COMMENT '关联执行任务ID',
    `tenant_id`         VARCHAR(64)   NULL COMMENT '租户标识',
    `skill_code`        VARCHAR(128)  NULL COMMENT 'Skill 编码',
    `seq`               INT           NOT NULL DEFAULT 0 COMMENT '任务内序号（与 task_id 联合防重）',
    `kind`              VARCHAR(16)   NOT NULL DEFAULT 'chat' COMMENT 'chat/embeddings/video',
    `alias`             VARCHAR(64)   NULL COMMENT '实际命中 alias（含 fallback 后）',
    `model_name`        VARCHAR(128)  NULL COMMENT '供应商侧模型名',
    `provider`          VARCHAR(64)   NULL COMMENT '供应商标识',
    `prompt_tokens`     INT           NULL COMMENT '输入 token',
    `completion_tokens` INT           NULL COMMENT '输出 token',
    `total_tokens`      INT           NULL COMMENT '总 token',
    `cost_points`       DECIMAL(12,4) NULL COMMENT '折算点数成本（费率 × token）',
    `duration_ms`       BIGINT        NULL COMMENT '调用耗时（毫秒）',
    `status`            VARCHAR(16)   NOT NULL DEFAULT 'OK' COMMENT 'OK/FAILED',
    `error_code`        VARCHAR(64)   NULL COMMENT '失败错误码',
    `created_at`        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '调用时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_seq` (`task_id`, `seq`),
    KEY `idx_mc_task` (`task_id`),
    KEY `idx_mc_skill_created` (`skill_code`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='逐次模型调用明细';

-- skill_version 审计列（审核人/时间/驳回原因）
SET @ddl_sv_reviewer = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE skill_version ADD COLUMN reviewer VARCHAR(64) NULL COMMENT ''审核人（admin username）'' AFTER uploaded_by',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'skill_version' AND COLUMN_NAME = 'reviewer');
PREPARE stmt3 FROM @ddl_sv_reviewer;
EXECUTE stmt3;
DEALLOCATE PREPARE stmt3;

SET @ddl_sv_reviewed_at = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE skill_version ADD COLUMN reviewed_at DATETIME NULL COMMENT ''审核时间'' AFTER reviewer',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'skill_version' AND COLUMN_NAME = 'reviewed_at');
PREPARE stmt4 FROM @ddl_sv_reviewed_at;
EXECUTE stmt4;
DEALLOCATE PREPARE stmt4;

SET @ddl_sv_reject = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE skill_version ADD COLUMN reject_reason VARCHAR(512) NULL COMMENT ''驳回原因（status=2 时）'' AFTER reviewed_at',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'skill_version' AND COLUMN_NAME = 'reject_reason');
PREPARE stmt5 FROM @ddl_sv_reject;
EXECUTE stmt5;
DEALLOCATE PREPARE stmt5;

-- 权限点字典（19 项；ON DUPLICATE 幂等，名称可随版本演进）
INSERT INTO admin_permission (perm_code, perm_name, group_name, sort_no) VALUES
    ('metrics:read',      '监控查看',   '监控',   1),
    ('skill:read',        '技能查看',   '技能',   1),
    ('skill:upload',      '上传技能',   '技能',   2),
    ('skill:review',      '审核版本',   '技能',   3),
    ('skill:publish',     '上架/下架',  '技能',   4),
    ('skill:rollback',    '版本回滚',   '技能',   5),
    ('skill:delete',      '删除技能',   '技能',   6),
    ('execution:read',    '执行查看',   '执行',   1),
    ('execution:manage',  '停止/重试',  '执行',   2),
    ('alert:read',        '告警查看',   '告警',   1),
    ('alert:manage',      '告警管理',   '告警',   2),
    ('model:read',        '模型查看',   '模型',   1),
    ('model:manage',      '模型管理',   '模型',   2),
    ('kb:manage',         '知识库管理', '知识库', 1),
    ('key:read',          'Key查看',    '接入',   1),
    ('key:manage',        'Key管理',    '接入',   2),
    ('user:manage',       '用户管理',   '系统',   1),
    ('sysconfig:read',    '配置查看',   '系统',   2),
    ('sysconfig:manage',  '配置管理',   '系统',   3)
ON DUPLICATE KEY UPDATE perm_name = VALUES(perm_name), group_name = VALUES(group_name), sort_no = VALUES(sort_no);

-- 预置角色
INSERT INTO admin_role (role_code, role_name, remark, builtin) VALUES
    ('SUPER_ADMIN',   '超级管理员', '全部权限',             1),
    ('SKILL_MANAGER', '技能管理',   '技能全生命周期',       1),
    ('REVIEWER',      '审核员',     '版本审核',             1),
    ('OPS',           '运维',       '执行/告警/模型运维',   1),
    ('VIEWER',        '只读',       '只读观察',             1)
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), builtin = 1;

-- 角色-权限映射
INSERT INTO admin_role_permission (role_code, perm_code) VALUES
    ('SUPER_ADMIN', 'metrics:read'), ('SUPER_ADMIN', 'skill:read'), ('SUPER_ADMIN', 'skill:upload'),
    ('SUPER_ADMIN', 'skill:review'), ('SUPER_ADMIN', 'skill:publish'), ('SUPER_ADMIN', 'skill:rollback'),
    ('SUPER_ADMIN', 'skill:delete'), ('SUPER_ADMIN', 'execution:read'), ('SUPER_ADMIN', 'execution:manage'),
    ('SUPER_ADMIN', 'alert:read'), ('SUPER_ADMIN', 'alert:manage'), ('SUPER_ADMIN', 'model:read'),
    ('SUPER_ADMIN', 'model:manage'), ('SUPER_ADMIN', 'kb:manage'), ('SUPER_ADMIN', 'key:read'),
    ('SUPER_ADMIN', 'key:manage'), ('SUPER_ADMIN', 'user:manage'), ('SUPER_ADMIN', 'sysconfig:read'),
    ('SUPER_ADMIN', 'sysconfig:manage'),
    ('SKILL_MANAGER', 'metrics:read'), ('SKILL_MANAGER', 'skill:read'), ('SKILL_MANAGER', 'skill:upload'),
    ('SKILL_MANAGER', 'skill:review'), ('SKILL_MANAGER', 'skill:publish'), ('SKILL_MANAGER', 'skill:rollback'),
    ('SKILL_MANAGER', 'skill:delete'), ('SKILL_MANAGER', 'execution:read'),
    ('REVIEWER', 'metrics:read'), ('REVIEWER', 'skill:read'), ('REVIEWER', 'skill:review'),
    ('OPS', 'metrics:read'), ('OPS', 'execution:read'), ('OPS', 'execution:manage'),
    ('OPS', 'alert:read'), ('OPS', 'alert:manage'), ('OPS', 'model:read'), ('OPS', 'key:read'),
    ('VIEWER', 'metrics:read'), ('VIEWER', 'skill:read'), ('VIEWER', 'execution:read'), ('VIEWER', 'alert:read')
ON DUPLICATE KEY UPDATE role_code = VALUES(role_code);

-- ============================================================
-- V2.2_1 增量（消耗看板：预算表 + 消耗权限点）
-- ============================================================

-- 消耗预算（月度；scope_value 用 '' 非 NULL，规避 MySQL 唯一索引 NULL 去重失效）
CREATE TABLE IF NOT EXISTS `budget` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `scope_type`          VARCHAR(16)  NOT NULL COMMENT 'GLOBAL / TENANT / SKILL',
    `scope_value`         VARCHAR(128) NOT NULL DEFAULT '' COMMENT 'GLOBAL 为空串；TENANT=租户ID；SKILL=skillCode',
    `period_type`         VARCHAR(16)  NOT NULL DEFAULT 'MONTHLY' COMMENT '周期：MONTHLY（v1 仅月度）',
    `amount_points`       BIGINT       NOT NULL COMMENT '周期额度（点）',
    `alert_threshold_pct` INT          NOT NULL DEFAULT 80 COMMENT '告警阈值百分比（达阈值产生告警记录）',
    `enabled`             TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
    `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_budget_scope` (`scope_type`, `scope_value`, `period_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='消耗预算（月度）';

-- 权限点（消耗看板）
INSERT INTO admin_permission (perm_code, perm_name, group_name, sort_no) VALUES
    ('consumption:read',   '消耗查看',   '消耗',   1),
    ('consumption:manage', '预算管理',   '消耗',   2)
ON DUPLICATE KEY UPDATE perm_name = VALUES(perm_name), group_name = VALUES(group_name), sort_no = VALUES(sort_no);

INSERT INTO admin_role_permission (role_code, perm_code) VALUES
    ('SUPER_ADMIN', 'consumption:read'), ('SUPER_ADMIN', 'consumption:manage'),
    ('OPS', 'consumption:read'), ('OPS', 'consumption:manage'),
    ('VIEWER', 'consumption:read'), ('SKILL_MANAGER', 'consumption:read')
ON DUPLICATE KEY UPDATE role_code = VALUES(role_code);
