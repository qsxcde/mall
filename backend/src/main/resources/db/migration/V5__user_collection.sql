-- ============================================================
-- 我的收藏 / 浏览足迹 V5
--
-- 这两类数据「用户 × 商品」唯一，取消收藏/删除足迹是物理删除即可，
-- 不需要逻辑删除位（与订单/评价不同，它们没有审计价值）。
-- ============================================================

CREATE TABLE IF NOT EXISTS ums_user_favorite (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    user_id     BIGINT   NOT NULL,
    product_id  BIGINT   NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 唯一索引让「重复收藏」天然幂等，也可用 INSERT IGNORE 快速处理并发
    UNIQUE KEY uk_user_product (user_id, product_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '我的收藏';

CREATE TABLE IF NOT EXISTS ums_user_history (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    user_id    BIGINT   NOT NULL,
    product_id BIGINT   NOT NULL,
    view_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近浏览时间',
    PRIMARY KEY (id),
    -- 同一商品只保留一条，重复浏览只更新时间，避免足迹无限膨胀
    UNIQUE KEY uk_user_product (user_id, product_id),
    KEY idx_user_time (user_id, view_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '浏览足迹';
