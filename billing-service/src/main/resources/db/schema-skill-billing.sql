-- =====================================================================
-- skill_billing 库生产 DDL（技术方案 v6.5 §6.8-6.10，MySQL 8）
-- 与 skill_platform 同实例分库；对账跨库 JOIN 依赖此前提
-- =====================================================================

CREATE TABLE IF NOT EXISTS `credit_account` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `app_key_id`  VARCHAR(64) NOT NULL COMMENT '关联 AppKey（V1：key 即账户，一 key 一账户）',
    `tenant_id`   VARCHAR(64) NOT NULL COMMENT '租户标识（冗余，报表用）',
    `balance`     BIGINT      NOT NULL DEFAULT 0 COMMENT '可用点数',
    `frozen`      BIGINT      NOT NULL DEFAULT 0 COMMENT '冻结中点数（在途任务 hold 合计）',
    `version`     BIGINT      NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（冻结/结算单行原子更新）',
    `updated_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_key` (`app_key_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='点数账户表：冻结=balance 减 frozen 加（WHERE balance 足够，单行原子）';

CREATE TABLE IF NOT EXISTS `billing_hold` (
    `id`             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `hold_id`        VARCHAR(64) NOT NULL COMMENT '冻结单ID',
    `task_id`        VARCHAR(64) NOT NULL COMMENT '关联执行任务（一任务一冻结单）',
    `app_key_id`     VARCHAR(64) NOT NULL COMMENT '扣点账户',
    `amount`         BIGINT      NOT NULL COMMENT '冻结点数（预估：单价×count 或 capPoints）',
    `status`         VARCHAR(16) NOT NULL DEFAULT 'FROZEN' COMMENT 'FROZEN=冻结中 SETTLED=已结算 RELEASED=已释放（退款/对账兜底）',
    `settled_amount` BIGINT      NULL COMMENT '实际结算点数（≤amount，差额已解冻）',
    `created_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '冻结时间',
    `settled_at`     DATETIME    NULL COMMENT '结算/释放时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_hold` (`hold_id`),
    UNIQUE KEY `uk_task` (`task_id`),
    KEY `idx_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='计费冻结单：对账任务扫 FROZEN 超 24h 未结算自动释放';

CREATE TABLE IF NOT EXISTS `billing_transaction` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `tx_id`         VARCHAR(64)  NOT NULL COMMENT '流水ID',
    `app_key_id`    VARCHAR(64)  NOT NULL COMMENT '账户',
    `task_id`       VARCHAR(64)  NULL COMMENT '关联任务（HOLD/SETTLE/RELEASE 带；充值/调整为 NULL）',
    `type`          VARCHAR(16)  NOT NULL COMMENT 'HOLD=冻结 SETTLE=结算 RELEASE=释放 RECHARGE=充值 ADJUST=人工调整',
    `amount`        BIGINT       NOT NULL COMMENT '点数变动（正/负）',
    `balance_after` BIGINT       NOT NULL COMMENT '变动后可用余额快照（审计）',
    `remark`        VARCHAR(256) NULL COMMENT '备注（退款原因等）',
    `order_no`      VARCHAR(64)  NULL COMMENT '充值订单号（RECHARGE 幂等键）：与 type 组成唯一约束，其余类型 NULL（v6.9 §10.4 入账幂等）',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tx` (`tx_id`),
    UNIQUE KEY `uk_type_order` (`type`, `order_no`),
    KEY `idx_key_task` (`app_key_id`, `task_id`),
    KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='计费流水表：不可变（只插入不更新），审计与对账依据；RECHARGE 按 orderNo 幂等';
