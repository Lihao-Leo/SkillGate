-- =====================================================================
-- 本地/测试 schema（H2, MODE=MySQL）——JSON 列以 TEXT 承载（实体均按 String 映射）
-- =====================================================================

CREATE TABLE IF NOT EXISTS app_key (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    app_key_id       VARCHAR(64) NOT NULL,
    tenant_id        VARCHAR(64) NOT NULL,
    secret_cipher    VARCHAR(512) NOT NULL,
    quota            TEXT,
    callback_domains TEXT,
    status           TINYINT NOT NULL DEFAULT 1,
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_key UNIQUE (app_key_id)
);

CREATE INDEX IF NOT EXISTS idx_ak_tenant ON app_key (tenant_id);

CREATE TABLE IF NOT EXISTS skill (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id          VARCHAR(64) NOT NULL,
    skill_code         VARCHAR(128) NOT NULL,
    name               VARCHAR(256) NOT NULL,
    description        TEXT,
    visibility         VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
    kind               VARCHAR(16) NOT NULL DEFAULT 'CODE',
    required_abilities TEXT,
    output_config      TEXT,
    pricing_config     TEXT,
    invocation_spec    TEXT,
    kb_bindings        TEXT,
    telemetry_token    VARCHAR(64),
    default_version    VARCHAR(32),
    status             TINYINT NOT NULL DEFAULT 1,
    created_by         VARCHAR(64) NOT NULL,
    created_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tenant_skill UNIQUE (tenant_id, skill_code)
);

CREATE TABLE IF NOT EXISTS skill_version (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    skill_id       BIGINT NOT NULL,
    version        VARCHAR(32) NOT NULL,
    oss_key        VARCHAR(512) NOT NULL,
    oss_url        VARCHAR(1024),
    package_sha256 CHAR(64) NOT NULL,
    package_size   BIGINT NOT NULL,
    changelog      TEXT,
    status         TINYINT NOT NULL DEFAULT 0,
    uploaded_by    VARCHAR(64) NOT NULL,
    reviewer       VARCHAR(64),
    reviewed_at    DATETIME,
    reject_reason  VARCHAR(512),
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_skill_version UNIQUE (skill_id, version)
);

CREATE TABLE IF NOT EXISTS execution (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id                VARCHAR(64) NOT NULL,
    client_request_id      VARCHAR(128),
    app_key_id             VARCHAR(64) NOT NULL,
    tenant_id              VARCHAR(64) NOT NULL,
    skill_code             VARCHAR(128) NOT NULL,
    skill_version          VARCHAR(32) NOT NULL,
    package_sha256         CHAR(64) NOT NULL,
    sandbox_digest         VARCHAR(64),
    input_ref              VARCHAR(1024),
    context                TEXT,
    expected_count         INT,
    count_overridden       TINYINT NOT NULL DEFAULT 0,
    output_config_snapshot TEXT,
    pricing_snapshot       TEXT,
    hold_id                VARCHAR(64),
    status                 VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    progress               TINYINT,
    error_code             VARCHAR(32),
    error_message          TEXT,
    callback_url           VARCHAR(512),
    callback_status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    model_calls            INT NOT NULL DEFAULT 0,
    tokens_used            INT NOT NULL DEFAULT 0,
    duration_ms            BIGINT,
    created_by             VARCHAR(64) NOT NULL,
    created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at             DATETIME,
    finished_at            DATETIME,
    CONSTRAINT uk_task_id UNIQUE (task_id),
    CONSTRAINT uk_key_client_req UNIQUE (app_key_id, client_request_id)
);

CREATE INDEX IF NOT EXISTS idx_exec_tenant_status ON execution (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_exec_status ON execution (status);

-- 执行事件链：观测数据，写入失败不阻塞主链路（detail JSON 以 TEXT 承载）
CREATE TABLE IF NOT EXISTS execution_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id     VARCHAR(64) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL,
    seq         INT NOT NULL,
    stage       VARCHAR(32) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    service     VARCHAR(16) NOT NULL,
    duration_ms BIGINT,
    detail      TEXT,
    trace_id    VARCHAR(64),
    occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_event_task_seq ON execution_event (task_id, seq);
CREATE INDEX IF NOT EXISTS idx_event_tenant_time ON execution_event (tenant_id, occurred_at);

CREATE TABLE IF NOT EXISTS artifact (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id  VARCHAR(64) NOT NULL,
    task_id      VARCHAR(64) NOT NULL,
    tenant_id    VARCHAR(64) NOT NULL,
    type         VARCHAR(32) NOT NULL,
    oss_key      VARCHAR(512) NOT NULL,
    file_size    BIGINT NOT NULL DEFAULT 0,
    content_type VARCHAR(128),
    meta         TEXT,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at   DATETIME,
    CONSTRAINT uk_artifact_id UNIQUE (artifact_id)
);

CREATE INDEX IF NOT EXISTS idx_artifact_task ON artifact (task_id);

CREATE TABLE IF NOT EXISTS material (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_id         VARCHAR(64) NOT NULL,
    tenant_id           VARCHAR(64) NOT NULL,
    material_type       VARCHAR(32) NOT NULL,
    oss_key             VARCHAR(512) NOT NULL,
    upload_channel      VARCHAR(16) NOT NULL DEFAULT 'multipart',
    status              TINYINT NOT NULL DEFAULT 0,
    filename            VARCHAR(256),
    content_type        VARCHAR(128),
    file_size           BIGINT NOT NULL DEFAULT 0,
    sha256              CHAR(64),
    created_by          VARCHAR(64),
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_referenced_at  DATETIME,
    expires_at          DATETIME,
    CONSTRAINT uk_material_id UNIQUE (material_id)
);

CREATE TABLE IF NOT EXISTS model_provider (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    alias          VARCHAR(64) NOT NULL,
    provider       VARCHAR(64) NOT NULL,
    model_name     VARCHAR(128) NOT NULL,
    endpoint       VARCHAR(512) NOT NULL,
    api_key_cipher VARCHAR(512) NOT NULL,
    fallback_alias VARCHAR(64),
    max_qps        INT NOT NULL DEFAULT 10,
    cost_per_1k_input_tokens  DECIMAL(10,4),
    cost_per_1k_output_tokens DECIMAL(10,4),
    cost_per_call   DECIMAL(10,4),
    status         TINYINT NOT NULL DEFAULT 1,
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_alias UNIQUE (alias)
);

CREATE TABLE IF NOT EXISTS kb (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    kb_id             VARCHAR(64) NOT NULL,
    tenant_id         VARCHAR(64) NOT NULL,
    name              VARCHAR(128) NOT NULL,
    embedding_alias   VARCHAR(64) NOT NULL,
    milvus_collection VARCHAR(128) NOT NULL,
    status            TINYINT NOT NULL DEFAULT 1,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_kb UNIQUE (kb_id)
);

CREATE TABLE IF NOT EXISTS kb_document (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_id        VARCHAR(64) NOT NULL,
    kb_id         VARCHAR(64) NOT NULL,
    tenant_id     VARCHAR(64) NOT NULL,
    oss_key       VARCHAR(512) NOT NULL,
    chunk_count   INT NOT NULL DEFAULT 0,
    status        TINYINT NOT NULL DEFAULT 0,
    error_message VARCHAR(512),
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_doc UNIQUE (doc_id)
);

-- KB 分块表：入库幂等与溯源（text 以 TEXT 承载）
CREATE TABLE IF NOT EXISTS kb_chunk (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    chunk_id    VARCHAR(80) NOT NULL,
    doc_id      VARCHAR(64) NOT NULL,
    kb_id       VARCHAR(64) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL,
    chunk_idx   INT NOT NULL,
    text        TEXT NOT NULL,
    text_hash   CHAR(64) NOT NULL,
    embed_model VARCHAR(128) NOT NULL,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_chunk UNIQUE (chunk_id)
);

CREATE INDEX IF NOT EXISTS idx_chunk_doc ON kb_chunk (doc_id);

CREATE TABLE IF NOT EXISTS skill_io_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    skill_code  VARCHAR(128) NOT NULL,
    version     VARCHAR(32),
    input       TEXT,
    output      TEXT,
    status      VARCHAR(16) NOT NULL,
    duration_ms BIGINT,
    caller_hint VARCHAR(128),
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_config (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key    VARCHAR(128) NOT NULL,
    config_value  VARCHAR(512) NOT NULL,
    remark        VARCHAR(256),
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_config_key UNIQUE (config_key)
);

-- =====================================================================
-- V2.1_1 增量（管理后台账号/权限、告警规则中心、模型调用明细）
-- H2：JSON 以 TEXT 承载；种子用 MERGE INTO 幂等
-- =====================================================================

CREATE TABLE IF NOT EXISTS admin_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(64),
    role_code     VARCHAR(32) NOT NULL DEFAULT 'VIEWER',
    status        TINYINT NOT NULL DEFAULT 1,
    last_login_at DATETIME,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_admin_username UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS admin_role (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_code  VARCHAR(32) NOT NULL,
    role_name  VARCHAR(64) NOT NULL,
    remark     VARCHAR(256),
    builtin    TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_role_code UNIQUE (role_code)
);

CREATE TABLE IF NOT EXISTS admin_permission (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    perm_code  VARCHAR(64) NOT NULL,
    perm_name  VARCHAR(64) NOT NULL,
    group_name VARCHAR(32) NOT NULL,
    sort_no    INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_perm_code UNIQUE (perm_code)
);

CREATE TABLE IF NOT EXISTS admin_role_permission (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_code VARCHAR(32) NOT NULL,
    perm_code VARCHAR(64) NOT NULL,
    CONSTRAINT uk_role_perm UNIQUE (role_code, perm_code)
);

CREATE TABLE IF NOT EXISTS alert_rule (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_code             VARCHAR(64) NOT NULL,
    rule_name             VARCHAR(128) NOT NULL,
    rule_type             VARCHAR(32) NOT NULL,
    skill_code            VARCHAR(128),
    threshold_value       BIGINT,
    severity              VARCHAR(16) NOT NULL DEFAULT 'WARN',
    channels              VARCHAR(128) NOT NULL DEFAULT 'feishu,record',
    dedup_window_seconds  INT NOT NULL DEFAULT 86400,
    enabled               TINYINT NOT NULL DEFAULT 1,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_rule_code UNIQUE (rule_code)
);

CREATE INDEX IF NOT EXISTS idx_rule_type_enabled ON alert_rule (rule_type, enabled);

CREATE TABLE IF NOT EXISTS alert_record (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id       BIGINT,
    rule_code     VARCHAR(64) NOT NULL,
    rule_type     VARCHAR(32) NOT NULL,
    severity      VARCHAR(16) NOT NULL DEFAULT 'WARN',
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    task_id       VARCHAR(64),
    skill_code    VARCHAR(128),
    tenant_id     VARCHAR(64),
    title         VARCHAR(256) NOT NULL,
    content       TEXT,
    detail        TEXT,
    notified      TINYINT NOT NULL DEFAULT 0,
    first_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ack_by        VARCHAR(64),
    ack_at        DATETIME,
    ack_note      VARCHAR(512),
    CONSTRAINT uk_task_rule UNIQUE (task_id, rule_code)
);

CREATE INDEX IF NOT EXISTS idx_alert_status_time ON alert_record (status, first_seen_at);
CREATE INDEX IF NOT EXISTS idx_alert_task ON alert_record (task_id);

CREATE TABLE IF NOT EXISTS model_call (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id           VARCHAR(64) NOT NULL,
    tenant_id         VARCHAR(64),
    skill_code        VARCHAR(128),
    seq               INT NOT NULL DEFAULT 0,
    kind              VARCHAR(16) NOT NULL DEFAULT 'chat',
    alias             VARCHAR(64),
    model_name        VARCHAR(128),
    provider          VARCHAR(64),
    prompt_tokens     INT,
    completion_tokens INT,
    total_tokens      INT,
    cost_points       DECIMAL(12,4),
    duration_ms       BIGINT,
    status            VARCHAR(16) NOT NULL DEFAULT 'OK',
    error_code        VARCHAR(64),
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_task_seq UNIQUE (task_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_mc_task ON model_call (task_id);
CREATE INDEX IF NOT EXISTS idx_mc_skill_created ON model_call (skill_code, created_at);

-- 权限点字典（MERGE 幂等）
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('metrics:read', '监控查看', '监控', 1);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('skill:read', '技能查看', '技能', 1);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('skill:upload', '上传技能', '技能', 2);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('skill:review', '审核版本', '技能', 3);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('skill:publish', '上架/下架', '技能', 4);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('skill:rollback', '版本回滚', '技能', 5);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('skill:delete', '删除技能', '技能', 6);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('execution:read', '执行查看', '执行', 1);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('execution:manage', '停止/重试', '执行', 2);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('alert:read', '告警查看', '告警', 1);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('alert:manage', '告警管理', '告警', 2);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('model:read', '模型查看', '模型', 1);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('model:manage', '模型管理', '模型', 2);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('kb:manage', '知识库管理', '知识库', 1);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('key:read', 'Key查看', '接入', 1);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('key:manage', 'Key管理', '接入', 2);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('user:manage', '用户管理', '系统', 1);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('sysconfig:read', '配置查看', '系统', 2);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('sysconfig:manage', '配置管理', '系统', 3);

MERGE INTO admin_role (role_code, role_name, remark, builtin) KEY(role_code) VALUES ('SUPER_ADMIN', '超级管理员', '全部权限', 1);
MERGE INTO admin_role (role_code, role_name, remark, builtin) KEY(role_code) VALUES ('SKILL_MANAGER', '技能管理', '技能全生命周期', 1);
MERGE INTO admin_role (role_code, role_name, remark, builtin) KEY(role_code) VALUES ('REVIEWER', '审核员', '版本审核', 1);
MERGE INTO admin_role (role_code, role_name, remark, builtin) KEY(role_code) VALUES ('OPS', '运维', '执行/告警/模型运维', 1);
MERGE INTO admin_role (role_code, role_name, remark, builtin) KEY(role_code) VALUES ('VIEWER', '只读', '只读观察', 1);

MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'metrics:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'skill:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'skill:upload');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'skill:review');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'skill:publish');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'skill:rollback');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'skill:delete');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'execution:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'execution:manage');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'alert:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'alert:manage');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'model:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'model:manage');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'kb:manage');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'key:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'key:manage');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'user:manage');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'sysconfig:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'sysconfig:manage');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SKILL_MANAGER', 'metrics:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SKILL_MANAGER', 'skill:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SKILL_MANAGER', 'skill:upload');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SKILL_MANAGER', 'skill:review');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SKILL_MANAGER', 'skill:publish');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SKILL_MANAGER', 'skill:rollback');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SKILL_MANAGER', 'skill:delete');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SKILL_MANAGER', 'execution:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('REVIEWER', 'metrics:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('REVIEWER', 'skill:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('REVIEWER', 'skill:review');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('OPS', 'metrics:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('OPS', 'execution:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('OPS', 'execution:manage');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('OPS', 'alert:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('OPS', 'alert:manage');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('OPS', 'model:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('OPS', 'key:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('VIEWER', 'metrics:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('VIEWER', 'skill:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('VIEWER', 'execution:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('VIEWER', 'alert:read');

-- V2.2_1 增量（消耗看板：预算表 + 消耗权限点）
CREATE TABLE IF NOT EXISTS budget (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    scope_type    VARCHAR(16) NOT NULL,
    scope_value   VARCHAR(128) NOT NULL DEFAULT '',
    period_type   VARCHAR(16) NOT NULL DEFAULT 'MONTHLY',
    amount_points BIGINT NOT NULL,
    alert_threshold_pct INT NOT NULL DEFAULT 80,
    enabled       TINYINT NOT NULL DEFAULT 1,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_budget_scope UNIQUE (scope_type, scope_value, period_type)
);

MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('consumption:read', '消耗查看', '消耗', 1);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('consumption:manage', '预算管理', '消耗', 2);
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'consumption:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'consumption:manage');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('OPS', 'consumption:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('OPS', 'consumption:manage');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('VIEWER', 'consumption:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SKILL_MANAGER', 'consumption:read');

-- V2.3_1 增量（租户双层限流配额、问题工单）
CREATE TABLE IF NOT EXISTS tenant_quota (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    qps         INT,
    max_running INT,
    daily_limit INT,
    remark      VARCHAR(256),
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tenant_quota UNIQUE (tenant_id)
);

CREATE TABLE IF NOT EXISTS issue (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_no       VARCHAR(40) NOT NULL,
    title           VARCHAR(256) NOT NULL,
    type            VARCHAR(32) NOT NULL,
    severity        VARCHAR(16) NOT NULL DEFAULT 'WARN',
    status          VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    source_id       VARCHAR(64),
    related_task_id VARCHAR(64),
    tenant_id       VARCHAR(64),
    skill_code      VARCHAR(128),
    description     TEXT,
    root_cause      TEXT,
    resolution      TEXT,
    assignee        VARCHAR(64),
    created_by      VARCHAR(64) NOT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at       DATETIME,
    CONSTRAINT uk_ticket_no UNIQUE (ticket_no),
    CONSTRAINT uk_type_source UNIQUE (type, source_id)
);
CREATE INDEX IF NOT EXISTS idx_issue_status_time ON issue (status, created_at);
CREATE INDEX IF NOT EXISTS idx_issue_assignee ON issue (assignee);
CREATE INDEX IF NOT EXISTS idx_issue_task ON issue (related_task_id);
CREATE INDEX IF NOT EXISTS idx_exec_skill_created ON execution (skill_code, created_at);

MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('issue:read', '工单查看', '工单', 1);
MERGE INTO admin_permission (perm_code, perm_name, group_name, sort_no) KEY(perm_code) VALUES ('issue:manage', '工单管理', '工单', 2);
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'issue:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SUPER_ADMIN', 'issue:manage');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('OPS', 'issue:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('OPS', 'issue:manage');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('SKILL_MANAGER', 'issue:read');
MERGE INTO admin_role_permission (role_code, perm_code) KEY(role_code, perm_code) VALUES ('VIEWER', 'issue:read');
