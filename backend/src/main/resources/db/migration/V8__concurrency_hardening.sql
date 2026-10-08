-- ============================================================
-- 高并发治理 V8：一致性兜底索引 + 库存回退幂等表 + 商品检索索引
-- 对应 docs/高并发问题治理清单.md 阶段二 / 阶段三
-- ============================================================

-- ---------- P0-4 库存回退幂等表 ----------
-- 唯一索引 (order_no, product_id) 保证同一订单同一商品只回退一次，
-- 即便状态机 CAS 出现意外，也不会把库存退回两次。
CREATE TABLE IF NOT EXISTS inventory_rollback_log (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    order_no    VARCHAR(32) NOT NULL COMMENT '订单号',
    product_id  BIGINT      NOT NULL COMMENT '商品 ID',
    qty         INT         NOT NULL DEFAULT 0 COMMENT '本次回退数量',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_product (order_no, product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '库存回退幂等日志';

-- ---------- P1-4 下单幂等兜底：订单表落 request_id + 唯一索引 ----------
ALTER TABLE oms_order
    ADD COLUMN request_id VARCHAR(64) DEFAULT NULL COMMENT '客户端幂等键' AFTER coupon_id;

ALTER TABLE oms_order
    ADD UNIQUE KEY uk_user_request (user_id, request_id);

-- ---------- P2-1 售后防重复建单 ----------
ALTER TABLE oms_aftersale
    ADD UNIQUE KEY uk_order_doing (order_no, status);

-- ---------- P2-2 评价防重复提交 ----------
ALTER TABLE pms_review
    ADD UNIQUE KEY uk_user_order_product (user_id, order_no, product_id);

-- ---------- P2-3 商品买家侧索引 ----------
ALTER TABLE pms_product
    ADD KEY idx_status (status);

ALTER TABLE pms_product
    ADD KEY idx_status_hot_sales (status, is_hot, sales);
