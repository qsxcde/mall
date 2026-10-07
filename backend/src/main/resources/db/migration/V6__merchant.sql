-- ============================================================
-- 商家域（Merchant Center） V6
-- 约定：所有业务表带 deleted 逻辑删除位与 create_time/update_time
-- 说明：商家端在既有买家侧数据之上叠加「店铺维度」，
--       商品 / 订单通过 shop_id 归属店铺，默认 1（演示店铺）。
-- ============================================================

-- ---------- 店铺 ----------
CREATE TABLE IF NOT EXISTS mms_shop (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    name         VARCHAR(64)   NOT NULL COMMENT '店铺名称',
    logo         VARCHAR(255)           DEFAULT NULL COMMENT '店铺 logo 文本/地址',
    level        VARCHAR(32)            DEFAULT NULL COMMENT '卖家等级，如 金牌卖家',
    verified     TINYINT       NOT NULL DEFAULT 0 COMMENT '1 已认证 0 未认证',
    today_target DECIMAL(14, 2) NOT NULL DEFAULT 0 COMMENT '今日目标成交额',
    status       TINYINT       NOT NULL DEFAULT 1 COMMENT '1 正常 0 停业',
    deleted      TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '店铺';

-- ---------- 商家账号 ----------
CREATE TABLE IF NOT EXISTS mms_merchant_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    username    VARCHAR(64)  NOT NULL COMMENT '登录账号',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文',
    nickname    VARCHAR(64)           DEFAULT NULL COMMENT '姓名 / 昵称',
    avatar      VARCHAR(255)          DEFAULT NULL COMMENT '头像',
    role        VARCHAR(32)           DEFAULT '超级管理员' COMMENT '角色名',
    shop_id     BIGINT       NOT NULL COMMENT '所属店铺',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 0 禁用',
    deleted     TINYINT      NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_shop (shop_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商家账号';

-- ---------- 营销活动 ----------
CREATE TABLE IF NOT EXISTS mms_promotion (
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    shop_id     BIGINT         NOT NULL COMMENT '所属店铺',
    name        VARCHAR(128)   NOT NULL COMMENT '活动名称',
    type        VARCHAR(32)    NOT NULL COMMENT 'discount/seckill/coupon/bundle/group/gift',
    status      VARCHAR(32)    NOT NULL COMMENT 'running/pending/paused/ended/audit',
    start_at    DATETIME                DEFAULT NULL,
    end_at      DATETIME                DEFAULT NULL,
    budget      DECIMAL(14, 2) NOT NULL DEFAULT 0 COMMENT '预算',
    cost        DECIMAL(14, 2) NOT NULL DEFAULT 0 COMMENT '已花费',
    roi         DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '投产比',
    gmv         DECIMAL(14, 2) NOT NULL DEFAULT 0 COMMENT '带动成交额',
    sold        INT            NOT NULL DEFAULT 0 COMMENT '带动销量',
    joined      INT            NOT NULL DEFAULT 0 COMMENT '参与商品数',
    deleted     TINYINT        NOT NULL DEFAULT 0,
    create_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_shop_status (shop_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商家营销活动';

-- ---------- 结算单 ----------
CREATE TABLE IF NOT EXISTS mms_settlement (
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    shop_id     BIGINT         NOT NULL COMMENT '所属店铺',
    settle_no   VARCHAR(64)    NOT NULL COMMENT '结算单号',
    range_label VARCHAR(64)    NOT NULL COMMENT '账期文案，如 10-01 ~ 10-05',
    gmv         DECIMAL(14, 2) NOT NULL DEFAULT 0 COMMENT '成交额',
    commission  DECIMAL(14, 2) NOT NULL DEFAULT 0 COMMENT '平台佣金',
    service     DECIMAL(14, 2) NOT NULL DEFAULT 0 COMMENT '支付服务费',
    refund      DECIMAL(14, 2) NOT NULL DEFAULT 0 COMMENT '退款扣减',
    settle      DECIMAL(14, 2) NOT NULL DEFAULT 0 COMMENT '实结到账',
    rate        DECIMAL(6, 4)  NOT NULL DEFAULT 0 COMMENT '实结率',
    status      VARCHAR(32)    NOT NULL COMMENT 'settled/settling/pending',
    deleted     TINYINT        NOT NULL DEFAULT 0,
    create_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settle_no (settle_no),
    KEY idx_shop (shop_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '结算单';

-- ---------- 资金流水 ----------
CREATE TABLE IF NOT EXISTS mms_fund_flow (
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    shop_id     BIGINT         NOT NULL COMMENT '所属店铺',
    type        VARCHAR(32)    NOT NULL COMMENT 'commission/service/refund/settle',
    title       VARCHAR(128)   NOT NULL COMMENT '流水标题',
    amount      DECIMAL(14, 2) NOT NULL DEFAULT 0 COMMENT '金额（正数）',
    direction   TINYINT        NOT NULL DEFAULT 1 COMMENT '1 入账 -1 出账',
    occupy_time DATETIME                DEFAULT NULL COMMENT '发生时间',
    deleted     TINYINT        NOT NULL DEFAULT 0,
    create_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_shop_type (shop_id, type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商家资金流水';

-- ---------- 既有表补充店铺归属与商家侧字段 ----------
ALTER TABLE pms_product ADD COLUMN shop_id BIGINT NOT NULL DEFAULT 1 COMMENT '所属店铺' AFTER id;
ALTER TABLE pms_product ADD COLUMN merchant_status VARCHAR(16) NOT NULL DEFAULT 'on' COMMENT '商家侧状态：on 在售/ware 仓库中/audit 审核中/sold 售罄/off 下架/trash 回收站' AFTER status;
ALTER TABLE pms_product ADD COLUMN safe_stock INT NOT NULL DEFAULT 10 COMMENT '安全库存线' AFTER stock;
ALTER TABLE pms_product ADD COLUMN views INT NOT NULL DEFAULT 0 COMMENT '浏览量' AFTER sales;
ALTER TABLE pms_product ADD COLUMN cost DECIMAL(10, 2) DEFAULT NULL COMMENT '成本价（商家可见）' AFTER old_price;
ALTER TABLE pms_product ADD KEY idx_shop_status (shop_id, status);

ALTER TABLE oms_order ADD COLUMN shop_id BIGINT NOT NULL DEFAULT 1 COMMENT '所属店铺（多店铺拆单后为实际店铺）' AFTER id;
ALTER TABLE oms_order ADD COLUMN merchant_note VARCHAR(255) DEFAULT NULL COMMENT '商家备注' AFTER remark;
ALTER TABLE oms_order ADD COLUMN express_company VARCHAR(32) DEFAULT NULL COMMENT '快递公司' AFTER trade_no;
ALTER TABLE oms_order ADD COLUMN waybill_no VARCHAR(32) DEFAULT NULL COMMENT '运单号' AFTER express_company;
ALTER TABLE oms_order ADD KEY idx_shop_status (shop_id, status);

ALTER TABLE oms_aftersale ADD COLUMN shop_id BIGINT NOT NULL DEFAULT 1 COMMENT '所属店铺' AFTER id;
ALTER TABLE oms_aftersale ADD COLUMN merchant_status VARCHAR(32) DEFAULT NULL COMMENT '商家侧工单状态：pending/wait_return/wait_receive/done/rejected' AFTER status;
ALTER TABLE oms_aftersale ADD COLUMN reject_reason VARCHAR(255) DEFAULT NULL COMMENT '商家拒绝理由' AFTER merchant_status;
ALTER TABLE oms_aftersale ADD KEY idx_shop_status (shop_id, status);

ALTER TABLE pms_review ADD COLUMN shop_id BIGINT NOT NULL DEFAULT 1 COMMENT '所属店铺' AFTER id;
ALTER TABLE pms_review ADD COLUMN ignored TINYINT NOT NULL DEFAULT 0 COMMENT '商家是否忽略 1 是 0 否' AFTER reply;
ALTER TABLE pms_review ADD KEY idx_shop (shop_id);

-- ---------- 种子数据 ----------
INSERT INTO mms_shop (id, name, logo, level, verified, today_target, status)
VALUES (1, '极客数码旗舰店', '旗', '金牌卖家', 1, 1000000.00, 1)
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO mms_promotion (shop_id, name, type, status, start_at, end_at, budget, cost, roi, gmv, sold, joined)
VALUES
    (1, '双十一抢先购 · 全场满减', 'discount', 'running', NOW() - INTERVAL 6 DAY, NOW() + INTERVAL 12 DAY, 100000, 62000, 6.30, 390600, 486, 28),
    (1, 'iPhone 17 系列新品直降 500', 'discount', 'running', NOW() - INTERVAL 3 DAY, NOW() + INTERVAL 9 DAY, 50000, 21000, 5.10, 107100, 132, 6),
    (1, '直播间专属秒杀 · 每晚 8 点', 'seckill', 'running', NOW() - INTERVAL 10 DAY, NOW() + INTERVAL 15 DAY, 20000, 9000, 4.40, 39600, 210, 12),
    (1, '数码配件满 299 减 50', 'coupon', 'running', NOW() - INTERVAL 20 DAY, NOW() + INTERVAL 25 DAY, 5000, 2600, 3.20, 8320, 96, 18),
    (1, 'Apple 生态套装立省 1200', 'bundle', 'pending', NOW() + INTERVAL 4 DAY, NOW() + INTERVAL 34 DAY, 200000, 0, 0, 0, 0, 9),
    (1, '智能穿戴拼团 3 人成团', 'group', 'pending', NOW() + INTERVAL 12 DAY, NOW() + INTERVAL 27 DAY, 50000, 0, 0, 0, 0, 14),
    (1, '满 5000 赠蓝牙音箱', 'gift', 'paused', NOW() - INTERVAL 15 DAY, NOW() + INTERVAL 25 DAY, 20000, 8000, 2.80, 22400, 64, 11),
    (1, '开学季数码焕新专场', 'discount', 'ended', NOW() - INTERVAL 52 DAY, NOW() - INTERVAL 32 DAY, 100000, 100000, 7.10, 710000, 812, 30),
    (1, '会员日专享 8 折', 'coupon', 'audit', NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 29 DAY, 50000, 0, 0, 0, 0, 8);

INSERT INTO mms_settlement (shop_id, settle_no, range_label, gmv, commission, service, refund, settle, rate, status)
VALUES
    (1, 'JS2026100101', '10-01 ~ 10-05', 486200, 24310, 2917, 12400, 446573, 0.9185, 'settled'),
    (1, 'JS2026092602', '09-26 ~ 09-30', 452800, 22640, 2717, 18600, 408843, 0.9029, 'settling'),
    (1, 'JS2026092103', '09-21 ~ 09-25', 398600, 19930, 2392, 24200, 352078, 0.8833, 'settled'),
    (1, 'JS2026091604', '09-16 ~ 09-20', 421300, 21065, 2528, 16800, 380907, 0.9041, 'settled'),
    (1, 'JS2026091105', '09-11 ~ 09-15', 377500, 18875, 2265, 21400, 334960, 0.8873, 'settled'),
    (1, 'JS2026090606', '09-06 ~ 09-10', 356200, 17810, 2137, 18600, 317653, 0.8918, 'settled');

INSERT INTO mms_fund_flow (shop_id, type, title, amount, direction, occupy_time)
VALUES
    (1, 'settle', '账期 10-01 ~ 10-05 结算入账', 446573, 1, NOW() - INTERVAL 1 DAY),
    (1, 'commission', '平台佣金扣除', 24310, -1, NOW() - INTERVAL 1 DAY),
    (1, 'service', '支付服务费扣除', 2917, -1, NOW() - INTERVAL 1 DAY),
    (1, 'refund', '售后退款扣减', 12400, -1, NOW() - INTERVAL 2 DAY),
    (1, 'settle', '账期 09-26 ~ 09-30 结算入账', 408843, 1, NOW() - INTERVAL 6 DAY),
    (1, 'refund', '订单 GK202610050312 退款', 699, -1, NOW() - INTERVAL 3 DAY),
    (1, 'commission', '平台佣金扣除', 22640, -1, NOW() - INTERVAL 6 DAY),
    (1, 'service', '支付服务费扣除', 2717, -1, NOW() - INTERVAL 6 DAY);
