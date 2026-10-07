-- ============================================================
-- 商家域一致性加固 V9
--
-- 背景：商家端写路径此前缺少并发保护，存在三类正确性风险：
--   1) 提现不校验余额 → 并发提现可把余额提成负数；
--   2) 售后工单无状态 CAS → 并发点「同意退款」会重复退款；
--   3) 商品编辑全字段覆盖 → 多人同时编辑丢失更新。
--
-- 本迁移补齐所需的列与唯一约束：
--   · mms_shop.balance            —— 可用余额（提现走条件扣减）
--   · mms_fund_flow.request_id    —— 提现幂等键（唯一索引兜底并发重复提交）
--   · pms_product.version         —— 供应商编辑的乐观锁版本号
-- ============================================================

-- ---------- 1. 店铺可用余额 ----------
ALTER TABLE mms_shop
    ADD COLUMN balance DECIMAL(14, 2) NOT NULL DEFAULT 0 COMMENT '可用余额' AFTER today_target;

-- 存量回填：可用余额 = 已结算入账 - 已提现（与旧口径 fundSummary 完全一致，保证升级后数字不变）
UPDATE mms_shop s
SET s.balance = (SELECT COALESCE(SUM(settle), 0)
                 FROM mms_settlement
                 WHERE shop_id = s.id AND status = 'settled' AND deleted = 0)
    - (SELECT COALESCE(SUM(amount), 0)
       FROM mms_fund_flow
       WHERE shop_id = s.id AND type = 'withdraw' AND deleted = 0);

-- ---------- 2. 提现幂等键 ----------
ALTER TABLE mms_fund_flow
    ADD COLUMN request_id VARCHAR(64) DEFAULT NULL COMMENT '提现请求号（幂等键）' AFTER title;

-- MySQL 唯一索引允许多个 NULL，因此存量流水（request_id 为 NULL）不受影响
ALTER TABLE mms_fund_flow
    ADD UNIQUE KEY uk_shop_request (shop_id, request_id);

-- ---------- 3. 商品乐观锁 ----------
ALTER TABLE pms_product
    ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号' AFTER merchant_status;
