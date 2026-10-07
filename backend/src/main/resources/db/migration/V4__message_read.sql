-- ============================================================
-- 站内消息「已读回执」表 V4
--
-- 背景：sys_message.user_id = 0 表示系统广播，一条广播被多个用户看到，
-- 若把已读状态写回 sys_message.is_read，就会出现「一个用户已读，所有人都变已读」。
-- 因此已读状态必须按「用户 × 消息」维度单独存。
-- sys_message.is_read 保留仅为兼容，不再作为已读依据。
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_user_message_read (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    user_id     BIGINT   NOT NULL,
    message_id  BIGINT   NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 唯一索引保证重复标记已读幂等
    UNIQUE KEY uk_user_message (user_id, message_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '站内消息已读回执';
