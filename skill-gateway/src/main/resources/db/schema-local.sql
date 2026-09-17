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
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_id      VARCHAR(64) NOT NULL,
    kb_id       VARCHAR(64) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL,
    oss_key     VARCHAR(512) NOT NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    status      TINYINT NOT NULL DEFAULT 0,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_doc UNIQUE (doc_id)
);

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
