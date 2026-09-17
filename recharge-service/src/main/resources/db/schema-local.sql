-- =====================================================================
-- 本地/测试 schema（H2, MODE=MySQL）
-- =====================================================================

CREATE TABLE IF NOT EXISTS recharge_order (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no        VARCHAR(64) NOT NULL,
    user_id         BIGINT NOT NULL,
    channel         VARCHAR(16) NOT NULL,
    sku_points      INT NOT NULL,
    amount_fen      BIGINT NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'CREATED',
    code_url        VARCHAR(512),
    trade_no        VARCHAR(64),
    paid_amount_fen BIGINT,
    expire_at       DATETIME NOT NULL,
    paid_at         DATETIME,
    credited_at     DATETIME,
    retry_count     INT NOT NULL DEFAULT 0,
    next_retry_at   DATETIME,
    last_error      VARCHAR(256),
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_order UNIQUE (order_no)
);

CREATE INDEX IF NOT EXISTS idx_ro_user ON recharge_order (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_ro_status_expire ON recharge_order (status, expire_at);

CREATE TABLE IF NOT EXISTS payment_notify_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    notify_id       VARCHAR(64) NOT NULL,
    channel         VARCHAR(16) NOT NULL,
    order_no        VARCHAR(64),
    raw_body        TEXT NOT NULL,
    signature_valid TINYINT NOT NULL DEFAULT 0,
    verify_message  VARCHAR(256),
    amount_fen      BIGINT,
    processed       TINYINT NOT NULL DEFAULT 0,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_notify UNIQUE (notify_id)
);

CREATE TABLE IF NOT EXISTS user_account (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    identifier      VARCHAR(128) NOT NULL,
    identifier_type VARCHAR(16) NOT NULL,
    status          TINYINT NOT NULL DEFAULT 1,
    last_login_at   DATETIME,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_identifier UNIQUE (identifier)
);

CREATE TABLE IF NOT EXISTS verification_code (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    identifier VARCHAR(128) NOT NULL,
    scene      VARCHAR(16) NOT NULL DEFAULT 'LOGIN',
    code       VARCHAR(8) NOT NULL,
    expires_at DATETIME NOT NULL,
    used_at    DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_vc_identifier ON verification_code (identifier, created_at);

CREATE TABLE IF NOT EXISTS user_app_key (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id        BIGINT NOT NULL,
    app_key_id     VARCHAR(64) NOT NULL,
    is_primary     TINYINT NOT NULL DEFAULT 0,
    secret_pending VARCHAR(512),
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_key UNIQUE (app_key_id)
);

CREATE INDEX IF NOT EXISTS idx_uak_user ON user_app_key (user_id);

CREATE TABLE IF NOT EXISTS sku (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku_id     VARCHAR(32) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    points     INT NOT NULL,
    price_fen  BIGINT NOT NULL,
    active     TINYINT NOT NULL DEFAULT 1,
    sort       INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sku UNIQUE (sku_id)
);
