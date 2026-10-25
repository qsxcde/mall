-- ============================================================
-- 多规格 SKU V17
-- 背景：此前是单商品模式，一件商品只有一个价格和一套库存（pms_product.price/stock），
--      表达不了「黑色 512G 卖光了、白色还有货」。本次引入结构化规格维度
--      （颜色×尺寸笛卡尔积）与 SKU 独立库存，全链路统一走 sku_id。
--
-- ⚠️ 词汇约定（避免与既有命名冲突）：
--      inv_bucket_rule.granularity 的枚举值 'SKU' 注释写的是「SKU（商品级）」，
--      它指的是**商品**、不是规格 SKU。本迁移起统一词汇：
--        · 规格 SKU = pms_sku.id（sku_id）
--        · 分桶粒度维持原值 PRODUCT/PRODUCT_BATCH 语义不变（改枚举值另起提交）
--
-- 三层守恒（应用层强约束）：
--      SUM(inv_bucket.stock WHERE sku_id=X)  == pms_sku.stock
--      SUM(pms_sku.stock   WHERE product_id=P) == pms_product.stock
--      一次出库 = 目标 SKU 若干桶各一次 CAS + pms_sku.stock 一次 CAS
--               + pms_product.stock 一次 CAS，同一事务，任一失败整体回滚
--
-- 存量迁移原则：每个商品生成 1 条「默认 SKU」承接全部历史库存与订单/购物车引用，
--      历史订单的 spec 展示文本一字不改（只补 sku_id）。
-- ============================================================

-- ---------- 规格维度：如「颜色」「尺寸」 ----------
CREATE TABLE IF NOT EXISTS pms_spec_dimension (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    product_id BIGINT       NOT NULL COMMENT '商品 ID',
    shop_id    BIGINT       NOT NULL DEFAULT 0 COMMENT '所属店铺',
    name       VARCHAR(64)  NOT NULL COMMENT '维度名：颜色/尺寸/内存',
    sort       INT          NOT NULL DEFAULT 0 COMMENT '展示排序，越小越前',
    deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_name (product_id, name),
    KEY idx_shop (shop_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商品规格维度';

-- ---------- 规格值：如「黑色」「XL」 ----------
CREATE TABLE IF NOT EXISTS pms_spec_value (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    dimension_id BIGINT       NOT NULL COMMENT '所属维度 ID',
    product_id   BIGINT       NOT NULL COMMENT '商品 ID（冗余，便于整商品读写）',
    value        VARCHAR(64)  NOT NULL COMMENT '规格值：黑色/XL/512G',
    image        VARCHAR(255) DEFAULT NULL COMMENT '该规格值的展示图（如颜色图）',
    sort         INT          NOT NULL DEFAULT 0 COMMENT '展示排序',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dimension_value (dimension_id, value),
    KEY idx_product (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商品规格值';

-- ---------- SKU：一个规格组合一行，独立价格与库存 ----------
CREATE TABLE IF NOT EXISTS pms_sku (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    product_id  BIGINT        NOT NULL COMMENT '商品 ID',
    shop_id     BIGINT        NOT NULL DEFAULT 0 COMMENT '所属店铺',
    sku_code    VARCHAR(64)   NOT NULL COMMENT 'SKU 编码，对外可见',
    spec_desc   VARCHAR(255)  NOT NULL DEFAULT '' COMMENT '规格展示快照：黑色/XL',
    spec_keys   VARCHAR(255)  NOT NULL DEFAULT '' COMMENT '组合唯一键：排序后的规格值 ID 用 | 拼接',
    price       DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT 'SKU 售价',
    cost        DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT 'SKU 成本价',
    stock       INT           NOT NULL DEFAULT 0 COMMENT 'SKU 库存（= SUM(inv_bucket.stock WHERE sku_id)）',
    safe_stock  INT           NOT NULL DEFAULT 0 COMMENT 'SKU 安全库存线',
    status      TINYINT       NOT NULL DEFAULT 1 COMMENT '1 可售 0 停售',
    deleted     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sku_code (sku_code),
    UNIQUE KEY uk_product_spec_keys (product_id, spec_keys),
    KEY idx_product_status (product_id, status),
    KEY idx_shop (shop_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商品 SKU';

-- ---------- SKU 与规格值关联（含展示快照，便于历史追溯） ----------
CREATE TABLE IF NOT EXISTS pms_sku_spec (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    sku_id         BIGINT       NOT NULL COMMENT 'SKU ID',
    product_id     BIGINT       NOT NULL COMMENT '商品 ID（冗余）',
    spec_value_id  BIGINT       NOT NULL COMMENT '规格值 ID',
    dimension_id   BIGINT       NOT NULL COMMENT '维度 ID（冗余，便于按维度分组展示）',
    value_snapshot VARCHAR(64)  NOT NULL COMMENT '规格值快照，规格值改名后历史 SKU 仍可追溯',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sku_value (sku_id, spec_value_id),
    KEY idx_value (spec_value_id),
    KEY idx_product (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'SKU 规格值关联';

-- ---------- 库存桶挂到 SKU ----------
-- sku_id 用 0 而非 NULL：NULL 在唯一键中互不判重，会导致同维度值重复建桶。
-- 0 表示「商品级 / 历史桶」，迁移后统一回填为默认 SKU。
ALTER TABLE inv_bucket
    ADD COLUMN sku_id BIGINT NOT NULL DEFAULT 0 COMMENT '规格 SKU ID，0=商品级/历史桶',
    DROP KEY uk_bucket,
    ADD UNIQUE KEY uk_bucket (product_id, sku_id, dimension, dimension_value),
    ADD KEY idx_sku_stock (sku_id, stock);

ALTER TABLE inv_bucket_log
    ADD COLUMN sku_id BIGINT NOT NULL DEFAULT 0 COMMENT '规格 SKU ID，0=商品级/历史流水';

-- ---------- 交易侧引用 SKU ----------
ALTER TABLE oms_cart_item
    ADD COLUMN sku_id BIGINT NOT NULL DEFAULT 0 COMMENT '规格 SKU ID，0=历史购物车行',
    ADD KEY idx_user_sku (user_id, sku_id);

ALTER TABLE oms_order_item
    ADD COLUMN sku_id BIGINT NOT NULL DEFAULT 0 COMMENT '规格 SKU ID，0=历史订单明细',
    ADD KEY idx_order_sku (order_no, sku_id);

-- ============================================================
-- 存量回填：为每个商品生成 1 条默认 SKU，承接全部历史库存/引用
-- ============================================================

-- ① 默认 SKU：spec_desc 取商品现有 spec 文本（历史展示兼容），价格/库存/成本取商品值
INSERT INTO pms_sku (product_id, shop_id, sku_code, spec_desc, spec_keys,
                     price, cost, stock, safe_stock, status)
SELECT p.id,
       p.shop_id,
       CONCAT('SKU-', p.id, '-D'),
       COALESCE(NULLIF(p.spec, ''), '默认规格'),
       '',
       p.price,
       COALESCE(p.cost, 0),
       COALESCE(p.stock, 0),
       COALESCE(p.safe_stock, 0),
       1
FROM pms_product p
WHERE p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM pms_sku s WHERE s.product_id = p.id AND s.deleted = 0);

-- ② 存量库存桶归属默认 SKU：迁移后 SKU 层守恒 SUM(桶)==pms_sku.stock 自然成立
UPDATE inv_bucket b
    JOIN pms_sku s ON s.product_id = b.product_id AND s.deleted = 0
SET b.sku_id = s.id
WHERE b.sku_id = 0;

UPDATE inv_bucket_log l
    JOIN pms_sku s ON s.product_id = l.product_id AND s.deleted = 0
SET l.sku_id = s.id
WHERE l.sku_id = 0;

-- ③ 购物车 / 订单明细只补 ID，不改任何展示文本（历史订单 spec 一字不动）
UPDATE oms_cart_item c
    JOIN pms_sku s ON s.product_id = c.product_id AND s.deleted = 0
SET c.sku_id = s.id
WHERE c.sku_id = 0;

UPDATE oms_order_item oi
    JOIN pms_sku s ON s.product_id = oi.product_id AND s.deleted = 0
SET oi.sku_id = s.id
WHERE oi.sku_id = 0;
