-- ============================================================
-- 支付宝沙箱支付闭环 V13
-- 背景：pay_payment_record 此前只有平台流水号，无渠道交易号，无法与支付宝对账；
--      且系统内不存在任何退款表 ——「订单已关闭但支付成功」时只能回滚整笔回调
--      （见 PaymentServiceImpl 旧注释），资金无出路。
--
-- 本迁移补齐三件事：
--   1) 支付单落渠道交易号（支付宝 trade_no），支撑对账与主动查单；
--   2) 支付单增加退款摘要位，作为「同一支付单只发起一次退款」的 CAS 闸门；
--   3) 新建退款单表，记录退款生命周期（退款为同步模型：结果在渠道 HTTP 响应里）。
--
-- 不变量（应用层强约束）：
--   1) 支付单 CAS：`update ... where status != 1` 是「回调 / 主动查单」两条入口
--      唯一致成功判定，保证同一支付单只落账一次；
--   2) 退款闸门：payment.refund_status 的 0 → 1 CAS 是唯一入口，
--      配合 refund_no 唯一索引，保证同一支付单只产生一张退款单；
--   3) refund_no 同时作为支付宝 out_request_no，交给渠道做二次幂等。
-- ============================================================

ALTER TABLE pay_payment_record
    ADD COLUMN channel VARCHAR(20) NOT NULL DEFAULT 'local'
        COMMENT '支付渠道：local（本地模拟）/ alipay（支付宝沙箱）',
    ADD COLUMN channel_trade_no VARCHAR(64) DEFAULT NULL
        COMMENT '渠道交易号（支付宝 trade_no），对账用',
    ADD COLUMN prepay_qr VARCHAR(512) DEFAULT NULL
        COMMENT '预下单二维码内容（支付宝 qr_code）；复用待支付单时直接返回，避免重复调网关',
    ADD COLUMN refund_status TINYINT NOT NULL DEFAULT 0
        COMMENT '退款状态：0 未退款 1 退款中 2 已退款 3 退款失败',
    ADD COLUMN refund_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00
        COMMENT '累计退款金额',
    MODIFY COLUMN callback_content VARCHAR(2000) DEFAULT NULL
        COMMENT '回调原文（留痕，超长截断）',
    ADD KEY idx_channel_trade_no (channel_trade_no),
    -- 主动查单补偿任务按「待支付 + 创建时间窗口」扫描，走这条索引
    ADD KEY idx_status_create (status, create_time);

CREATE TABLE IF NOT EXISTS pay_refund_record (
    id                BIGINT         NOT NULL AUTO_INCREMENT,
    refund_no         VARCHAR(64)    NOT NULL COMMENT '平台退款单号（= 支付宝 out_request_no）',
    trade_no          VARCHAR(64)    NOT NULL COMMENT '关联支付单号（pay_payment_record.trade_no）',
    order_no          VARCHAR(32)    NOT NULL COMMENT '关联订单号',
    user_id           BIGINT         NOT NULL,
    channel           VARCHAR(20)    NOT NULL COMMENT '渠道：local/alipay',
    channel_refund_no VARCHAR(64)             DEFAULT NULL COMMENT '渠道退款单号',
    amount            DECIMAL(10, 2) NOT NULL COMMENT '退款金额',
    status            TINYINT        NOT NULL DEFAULT 0 COMMENT '0 处理中 1 已退款 2 退款失败',
    reason            VARCHAR(255)            DEFAULT NULL COMMENT '退款原因',
    callback_time     DATETIME                DEFAULT NULL COMMENT '渠道确认时间',
    callback_content  VARCHAR(1000)           DEFAULT NULL COMMENT '渠道响应原文（留痕）',
    deleted           TINYINT        NOT NULL DEFAULT 0,
    create_time       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_no (refund_no),
    KEY idx_trade_no (trade_no),
    KEY idx_order_no (order_no),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '退款单（同步模型：结果以渠道响应为准）';
