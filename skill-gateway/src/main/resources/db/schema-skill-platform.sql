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
    `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待入库 1=已入库 2=失败',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc` (`doc_id`),
    KEY `idx_kb` (`kb_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识库文档表';

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
