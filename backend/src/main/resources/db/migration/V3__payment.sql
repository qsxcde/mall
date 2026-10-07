-- ============================================================
-- 支付单表 V3
-- 说明：支付回调必须按 trade_no 唯一索引做幂等，重复回调直接返回成功。
-- ============================================================

CREATE TABLE IF NOT EXISTS pay_payment_record (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    trade_no         VARCHAR(64)    NOT NULL COMMENT '支付流水号（平台生成）',
    order_no         VARCHAR(32)    NOT NULL COMMENT '关联订单号',
    user_id          BIGINT         NOT NULL,
    pay_method       VARCHAR(20)    NOT NULL COMMENT 'wechat/alipay/card/balance',
    amount           DECIMAL(10, 2) NOT NULL COMMENT '支付金额',
    status           TINYINT        NOT NULL DEFAULT 0 COMMENT '0 待支付 1 成功 2 失败 3 已关闭',
    callback_time    DATETIME                DEFAULT NULL COMMENT '回调时间',
    callback_content VARCHAR(1000)           DEFAULT NULL COMMENT '回调原文（留痕）',
    deleted          TINYINT        NOT NULL DEFAULT 0,
    create_time      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trade_no (trade_no),
    KEY idx_order_no (order_no),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '支付单';
