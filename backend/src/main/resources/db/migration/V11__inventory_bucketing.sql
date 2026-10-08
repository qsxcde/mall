-- ============================================================
-- 库存分桶 V11
-- 背景：pms_product.stock 是「商品级单行总量」，在单热点 SKU 下会把并发度压成 1
--      （见 loadtest/results/跨机压测第二轮-MQ对比.md 附录 C/D）。
--      本迁移把一份库存按维度（仓库 / 批次 / 效期 / 地区）拆成多个「桶」，
--      使出库时可命中不同桶行并行推进，而不是所有人抢同一行。
--
-- 不变量（应用层强约束）：
--      SUM(inv_bucket.stock) == pms_product.stock
--      一次出库 = 在若干桶上各做一次 CAS 扣减 + 商品总库存一次 CAS 扣减；
--      调拨 / 合并只在桶之间搬动，不改动 pms_product.stock。
-- ============================================================

-- ---------- 分桶规则：定义「按什么维度拆、出库按什么优先级挑桶」 ----------
CREATE TABLE IF NOT EXISTS inv_bucket_rule (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    rule_name     VARCHAR(64)  NOT NULL COMMENT '规则名称',
    dimension     VARCHAR(16)  NOT NULL COMMENT '分桶维度：WAREHOUSE/BATCH/EXPIRY/REGION',
    granularity   VARCHAR(16)  NOT NULL DEFAULT 'SKU' COMMENT '分桶粒度：SKU（商品级）/ SKU_BATCH（商品+批次）',
    deduct_policy VARCHAR(16)  NOT NULL DEFAULT 'FIFO' COMMENT '出库挑桶优先级：FIFO/EXPIRY_FIRST/MANUAL',
    shop_id       BIGINT       NOT NULL DEFAULT 0 COMMENT '所属店铺，0 表示平台级规则',
    product_id    BIGINT                DEFAULT NULL COMMENT '限定商品 ID，NULL 表示全店通用',
    enabled       TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
    remark        VARCHAR(255)          DEFAULT NULL COMMENT '备注',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_shop_rule_name (shop_id, rule_name),
    KEY idx_product (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '库存分桶规则';

-- ---------- 库存桶：一个 (商品 × 维度值) 一行 ----------
CREATE TABLE IF NOT EXISTS inv_bucket (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    product_id      BIGINT       NOT NULL COMMENT '商品 ID',
    shop_id         BIGINT       NOT NULL DEFAULT 0 COMMENT '所属店铺',
    rule_id         BIGINT                DEFAULT NULL COMMENT '来源分桶规则 ID',
    dimension       VARCHAR(16)  NOT NULL COMMENT '分桶维度',
    dimension_value VARCHAR(128) NOT NULL COMMENT '维度值：仓库名/批次号/效期(yyyy-MM-dd)/地区',
    warehouse       VARCHAR(32)           DEFAULT NULL COMMENT '仓库（冗余，便于筛选）',
    batch_no        VARCHAR(64)           DEFAULT NULL COMMENT '批次号',
    expire_date     DATE                  DEFAULT NULL COMMENT '效期（NULL 表示无效期）',
    region          VARCHAR(64)           DEFAULT NULL COMMENT '地区',
    stock           INT          NOT NULL DEFAULT 0 COMMENT '桶内余量（CAS 扣减对象）',
    total           INT          NOT NULL DEFAULT 0 COMMENT '桶内累计入桶量',
    priority        INT          NOT NULL DEFAULT 0 COMMENT '人工优先级，越小越先出（MANUAL 策略）',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 0 冻结',
    last_sync_time  DATETIME              DEFAULT NULL COMMENT '最近一次变更时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（桶不删除，仅置库存为 0）',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bucket (product_id, dimension, dimension_value),
    KEY idx_product_stock (product_id, stock),
    KEY idx_expire (product_id, expire_date),
    KEY idx_shop (shop_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '库存桶';

-- ---------- 调拨 / 合并操作单：桶间搬动的业务凭证 ----------
CREATE TABLE IF NOT EXISTS inv_bucket_operation (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    op_no          VARCHAR(40)  NOT NULL COMMENT '操作单号',
    op_type        VARCHAR(16)  NOT NULL COMMENT 'TRANSFER/MERGE',
    product_id     BIGINT       NOT NULL COMMENT '商品 ID',
    shop_id        BIGINT       NOT NULL DEFAULT 0 COMMENT '所属店铺',
    from_bucket_id BIGINT                DEFAULT NULL COMMENT '来源桶 ID',
    to_bucket_id   BIGINT                DEFAULT NULL COMMENT '目标桶 ID',
    qty            INT          NOT NULL DEFAULT 0 COMMENT '搬动量',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1 成功',
    operator       VARCHAR(64)           DEFAULT NULL COMMENT '操作人',
    remark         VARCHAR(255)          DEFAULT NULL COMMENT '备注',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_op_no (op_no),
    KEY idx_product (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '库存桶调拨/合并单';

-- ---------- 审计流水：append-only，任何桶余量变化都留痕 ----------
CREATE TABLE IF NOT EXISTS inv_bucket_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    bucket_id    BIGINT       NOT NULL COMMENT '桶 ID',
    product_id   BIGINT       NOT NULL COMMENT '商品 ID',
    shop_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '所属店铺（数据隔离维度）',
    biz_type     VARCHAR(20)  NOT NULL COMMENT 'ALLOCATE/OUTBOUND/TRANSFER_IN/TRANSFER_OUT/MERGE_IN/MERGE_OUT/ROLLBACK/ADJUST',
    change_qty   INT          NOT NULL COMMENT '变化量：正数增加、负数减少',
    before_stock INT          NOT NULL DEFAULT 0 COMMENT '变更前余量',
    after_stock  INT          NOT NULL DEFAULT 0 COMMENT '变更后余量',
    order_no     VARCHAR(40)           DEFAULT NULL COMMENT '关联业务单号（出库单 / 调拨单）',
    biz_id       VARCHAR(64)           DEFAULT NULL COMMENT '幂等键：同一业务动作唯一',
    bucket_key   VARCHAR(160)          DEFAULT NULL COMMENT '冗余：桶维度值，便于审计可读',
    operator     VARCHAR(64)           DEFAULT NULL COMMENT '操作人',
    remark       VARCHAR(255)          DEFAULT NULL COMMENT '备注',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_idempotent (biz_type, biz_id, bucket_id),
    KEY idx_bucket (bucket_id),
    KEY idx_shop_time (shop_id, create_time),
    KEY idx_product_time (product_id, create_time),
    KEY idx_order (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '库存桶审计流水';
