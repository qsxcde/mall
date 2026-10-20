-- ============================================================
-- 退款失败重试
--
-- 背景：渠道退款失败（网络抖动/网关超时）后，原实现只把 refund_status 置为失败，
-- 没有任何重试入口，用户会长期收不到退款。本迁移为退款单增加重试元数据。
--
-- 幂等：重试始终复用同一个 refund_no（= 支付宝 out_request_no），
--       由渠道侧做二次幂等；本地用 retry_count 乐观锁保证同一退款单只被一个实例抢到。
-- ============================================================

ALTER TABLE pay_refund_record
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0 COMMENT '已重试次数',
    ADD COLUMN next_retry_time DATETIME DEFAULT NULL COMMENT '下次可重试时间（NULL=立即可重试）',
    ADD COLUMN full_refund TINYINT NOT NULL DEFAULT 0 COMMENT '是否整单退款：1 是 0 否',
    ADD KEY idx_status_next_retry (status, next_retry_time);

-- 历史整单退款单回填：refund_no 规则为 RF<tradeNo> / RFAS<afterSaleNo>
UPDATE pay_refund_record
SET full_refund = 1
WHERE refund_no LIKE 'RF%' AND refund_no NOT LIKE 'RFAS%';

-- 历史失败单立即可重试
UPDATE pay_refund_record
SET next_retry_time = NOW()
WHERE status = 2 AND next_retry_time IS NULL;
