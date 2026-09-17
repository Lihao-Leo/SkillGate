-- =====================================================================
-- 本地/测试 schema（H2, MODE=MySQL）：
--   billing 三表 + 对账所需的 skill_platform 只读投影（execution / artifact 最小列集）
-- 幂等：CREATE ... IF NOT EXISTS（H2 mem 库可重复初始化）
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS skill_platform;

CREATE TABLE IF NOT EXISTS credit_account (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    app_key_id VARCHAR(64) NOT NULL,
    tenant_id  VARCHAR(64) NOT NULL,
    balance    BIGINT NOT NULL DEFAULT 0,
    frozen     BIGINT NOT NULL DEFAULT 0,
    version    BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_key UNIQUE (app_key_id)
);

CREATE TABLE IF NOT EXISTS billing_hold (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    hold_id        VARCHAR(64) NOT NULL,
    task_id        VARCHAR(64) NOT NULL,
    app_key_id     VARCHAR(64) NOT NULL,
    amount         BIGINT NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'FROZEN',
    settled_amount BIGINT,
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settled_at     DATETIME,
    CONSTRAINT uk_hold UNIQUE (hold_id),
    CONSTRAINT uk_task UNIQUE (task_id)
);

CREATE INDEX IF NOT EXISTS idx_hold_status_created ON billing_hold (status, created_at);

CREATE TABLE IF NOT EXISTS billing_transaction (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tx_id         VARCHAR(64) NOT NULL,
    app_key_id    VARCHAR(64) NOT NULL,
    task_id       VARCHAR(64),
    type          VARCHAR(16) NOT NULL,
    amount        BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    remark        VARCHAR(256),
    order_no      VARCHAR(64),
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tx UNIQUE (tx_id),
    CONSTRAINT uk_type_order UNIQUE (type, order_no)
);

CREATE INDEX IF NOT EXISTS idx_tx_key_task ON billing_transaction (app_key_id, task_id);
CREATE INDEX IF NOT EXISTS idx_tx_created ON billing_transaction (created_at);

-- 对账只读投影：非终态止损取消 + SUCCEEDED 补结算取产物数
CREATE TABLE IF NOT EXISTS skill_platform.execution (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id           VARCHAR(64) NOT NULL,
    status            VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    expected_count    INT,
    pricing_snapshot  TEXT,
    error_code        VARCHAR(32),
    error_message     TEXT,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at       DATETIME,
    CONSTRAINT uk_exec_task UNIQUE (task_id)
);

CREATE TABLE IF NOT EXISTS skill_platform.artifact (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id   VARCHAR(64) NOT NULL,
    type      VARCHAR(32) NOT NULL,
    file_size BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_exec_status ON skill_platform.execution (status);
CREATE INDEX IF NOT EXISTS idx_artifact_task ON skill_platform.artifact (task_id);
