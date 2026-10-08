-- ============================================================
-- 秒杀库存分桶 V12
-- 背景：mkt_seckill_item.stock 是「单行活动库存」，单热点 SKU 下所有订单都在争抢
--      同一行 InnoDB 行锁，落库并行度恒为 1（见 docs/benchmark/库存分桶实现分析.md §6.3）。
--      本迁移把一份活动库存预拆成 N 行桶，每单只扣其中 1 行，把并行度从 1 提到 ≤ N。
--
-- 不变量（应用层强约束）：
--      1) 守恒：SUM(mkt_seckill_bucket.total) - SUM(mkt_seckill_bucket.stock) == 成交单数；
--      2) 一人一单的判重集合不按桶拆（Redis 单 key），否则用户可借不同桶重复抢；
--      3) 回补必须还回原桶，因此 bucket_no 必须随消息体 / 方法参数一路传递。
--
-- 灰度：bucket_count 默认 1，等于「不分桶」——完全沿用 mkt_seckill_item.stock 单行路径，
--      既有商品零行为变化；把某个商品的 bucket_count 调到 >= 2 即对其启用分桶。
-- ============================================================

ALTER TABLE mkt_seckill_item
    ADD COLUMN bucket_count INT NOT NULL DEFAULT 1
        COMMENT '库存桶数：1=不分桶（沿用 mkt_seckill_item.stock 单行），>=2 启用库存分桶';

-- 分桶后 mkt_seckill_item.stock / sold 不再逐单更新（否则又退回单行热点），
-- 展示与对账的权威口径改为 SUM(mkt_seckill_bucket.*)。
CREATE TABLE IF NOT EXISTS mkt_seckill_bucket (
    item_id     BIGINT   NOT NULL COMMENT '秒杀活动商品 ID（mkt_seckill_item.id）',
    bucket_no   INT      NOT NULL COMMENT '桶号，0 起',
    stock       INT      NOT NULL DEFAULT 0 COMMENT '桶内余量（CAS 扣减对象）',
    total       INT      NOT NULL DEFAULT 0 COMMENT '桶内初始量，用于守恒对账',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (item_id, bucket_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '秒杀库存桶（并发单元：每单只扣一行）';
