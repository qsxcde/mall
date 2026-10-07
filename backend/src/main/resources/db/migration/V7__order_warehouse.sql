-- ============================================================
-- 发货仓 V7
-- 商家端「发货中心」按仓库筛选与展示，此前订单表未记录发货仓，
-- 导致该筛选条件与「发货仓库」列无数据可用。这里补齐并给存量订单兜底。
-- ============================================================

ALTER TABLE oms_order
    ADD COLUMN warehouse VARCHAR(32) NOT NULL DEFAULT '深圳总仓' COMMENT '发货仓库' AFTER waybill_no;

ALTER TABLE oms_order
    ADD KEY idx_shop_warehouse (shop_id, warehouse);
