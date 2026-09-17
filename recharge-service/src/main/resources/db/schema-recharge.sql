-- =====================================================================
-- recharge 库生产 DDL（技术方案 v6.9 §10.4，MySQL 8）
-- 独立库独立部署：渠道乱象止步于本库，billing 只认 orderNo 入账
-- =====================================================================

CREATE TABLE IF NOT EXISTS `recharge_order` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `order_no`        VARCHAR(64)  NOT NULL                COMMENT '平台订单号（R+时间戳+序号），全局唯一，入账幂等键',
    `user_id`         BIGINT       NOT NULL                COMMENT '充值平台用户',
    `channel`         VARCHAR(16)  NOT NULL                COMMENT 'WECHAT / ALIPAY',
    `sku_points`      INT          NOT NULL                COMMENT '购买点数（含赠送）',
    `amount_fen`      BIGINT       NOT NULL                COMMENT '应付金额（分）——内部一律分，杜绝浮点',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/PAYING/PAID/CREDITED/EXPIRED',
    `code_url`        VARCHAR(512) NULL                    COMMENT '渠道二维码链接 / 跳转 URL',
    `trade_no`        VARCHAR(64)  NULL                    COMMENT '渠道支付流水号（回调回填）',
    `paid_amount_fen` BIGINT       NULL                    COMMENT '实付金额（回调校验，须 == amount_fen）',
    `expire_at`       DATETIME     NOT NULL                COMMENT '二维码有效期（15min）',
    `paid_at`         DATETIME     NULL                    COMMENT '支付成功时间',
    `credited_at`     DATETIME     NULL                    COMMENT '入账+签发完成时间',
    `retry_count`     INT          NOT NULL DEFAULT 0      COMMENT '入账外调重试次数（v6.9 退避推进）',
    `next_retry_at`   DATETIME     NULL                    COMMENT '下次重试时间（1/5/15min 退避）',
    `last_error`      VARCHAR(256) NULL                    COMMENT '最近一次外调失败原因',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order` (`order_no`),
    KEY `idx_user_created` (`user_id`, `created_at`),
    KEY `idx_status_expire` (`status`, `expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='充值订单表：状态机 + 幂等 + 兜底查单的载体';

CREATE TABLE IF NOT EXISTS `payment_notify_log` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `notify_id`       VARCHAR(64)  NOT NULL                COMMENT '留档ID',
    `channel`         VARCHAR(16)  NOT NULL                COMMENT 'WECHAT / ALIPAY',
    `order_no`        VARCHAR(64)  NULL                    COMMENT '解析出的订单号（无法解析为 NULL）',
    `raw_body`        TEXT         NOT NULL                COMMENT '原始报文全量留档（审计 + 排障回放）',
    `signature_valid` TINYINT      NOT NULL DEFAULT 0      COMMENT '验签是否通过',
    `verify_message`  VARCHAR(256) NULL                    COMMENT '校验结论（ok / 验签失败 / 金额不符 / 幂等重复）',
    `amount_fen`      BIGINT       NULL                    COMMENT '报文金额（分）',
    `processed`       TINYINT      NOT NULL DEFAULT 0      COMMENT '是否推进订单状态',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '留档时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_notify` (`notify_id`),
    KEY `idx_order` (`order_no`),
    KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='支付回调留档：原始报文全量落库';

CREATE TABLE IF NOT EXISTS `user_account` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `identifier`      VARCHAR(128) NOT NULL                COMMENT '手机号或邮箱（唯一）',
    `identifier_type` VARCHAR(16)  NOT NULL                COMMENT 'PHONE / EMAIL',
    `status`          TINYINT      NOT NULL DEFAULT 1      COMMENT '1=正常 0=禁用',
    `last_login_at`   DATETIME     NULL                    COMMENT '最近登录',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_identifier` (`identifier`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='充值平台用户（V1 最简：验证码登录）';

CREATE TABLE IF NOT EXISTS `verification_code` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `identifier` VARCHAR(128) NOT NULL               COMMENT '手机号或邮箱',
    `scene`      VARCHAR(16) NOT NULL DEFAULT 'LOGIN' COMMENT '场景',
    `code`       VARCHAR(8)  NOT NULL                COMMENT '验证码',
    `expires_at` DATETIME    NOT NULL                COMMENT '过期时间（10min）',
    `used_at`    DATETIME    NULL                    COMMENT '使用时间（NULL=未使用）',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间（限流依据）',
    PRIMARY KEY (`id`),
    KEY `idx_identifier_created` (`identifier`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='登录验证码（60s/条、5 条/日）';

CREATE TABLE IF NOT EXISTS `user_app_key` (
    `id`             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `user_id`        BIGINT      NOT NULL                COMMENT '用户',
    `app_key_id`     VARCHAR(64) NOT NULL                COMMENT '执行平台 AppKey（sk- 前缀）',
    `is_primary`     TINYINT     NOT NULL DEFAULT 0      COMMENT '1=主 key（入账默认账户）',
    `secret_pending` VARCHAR(512) NULL                   COMMENT '待展示 AppSecret 密文（AES，一次性，读后清空）',
    `created_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_key` (`app_key_id`),
    KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户与 AppKey 一对多映射（首充自动签发）';

CREATE TABLE IF NOT EXISTS `sku` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `sku_id`     VARCHAR(32) NOT NULL                COMMENT '套餐ID',
    `name`       VARCHAR(64) NOT NULL                COMMENT '套餐名',
    `points`     INT         NOT NULL                COMMENT '购买点数（含赠送）',
    `price_fen`  BIGINT      NOT NULL                COMMENT '价格（分）',
    `active`     TINYINT     NOT NULL DEFAULT 1      COMMENT '1=上架 0=下架',
    `sort`       INT         NOT NULL DEFAULT 0      COMMENT '排序',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sku` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='点数套餐（阶梯赠送）';
