-- ============================================================
-- 极客数码商城 初始化脚本 V1
-- 约定：所有业务表带 deleted 逻辑删除位与 create_time/update_time
-- ============================================================

-- ---------- 用户域 ----------
CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username    VARCHAR(64)  NOT NULL COMMENT '登录账号',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文',
    phone       VARCHAR(20)  NOT NULL COMMENT '手机号',
    nickname    VARCHAR(64)           DEFAULT NULL COMMENT '昵称',
    avatar      VARCHAR(255)          DEFAULT NULL COMMENT '头像地址',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 0 禁用',
    level_id    BIGINT       NOT NULL DEFAULT 1 COMMENT '会员等级 ID',
    points      INT          NOT NULL DEFAULT 0 COMMENT '可用积分',
    growth      INT          NOT NULL DEFAULT 0 COMMENT '成长值',
    gender      TINYINT      NOT NULL DEFAULT 0 COMMENT '0 未知 1 男 2 女',
    birthday    DATE                  DEFAULT NULL COMMENT '生日',
    email       VARCHAR(128)          DEFAULT NULL COMMENT '邮箱',
    bio         VARCHAR(255)          DEFAULT NULL COMMENT '个人简介',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户表';

CREATE TABLE IF NOT EXISTS ums_member_level (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    level_name    VARCHAR(32) NOT NULL COMMENT '等级名称',
    growth_min    INT         NOT NULL DEFAULT 0 COMMENT '成长值下限',
    growth_max    INT         NOT NULL DEFAULT 0 COMMENT '成长值上限',
    discount_rate DECIMAL(4, 2) NOT NULL DEFAULT 1.00 COMMENT '折扣率',
    benefits      VARCHAR(500)         DEFAULT NULL COMMENT '权益，逗号分隔',
    deleted       TINYINT     NOT NULL DEFAULT 0,
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '会员等级';

CREATE TABLE IF NOT EXISTS ums_user_address (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL COMMENT '用户 ID',
    name        VARCHAR(32)  NOT NULL COMMENT '收货人',
    phone       VARCHAR(20)  NOT NULL COMMENT '手机号',
    province    VARCHAR(32)           DEFAULT NULL,
    city        VARCHAR(32)           DEFAULT NULL,
    district    VARCHAR(32)           DEFAULT NULL,
    detail      VARCHAR(255) NOT NULL COMMENT '详细地址',
    is_default  TINYINT      NOT NULL DEFAULT 0 COMMENT '1 默认 0 非默认',
    deleted     TINYINT      NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user (user_id, is_default)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '收货地址';

CREATE TABLE IF NOT EXISTS ums_user_sign (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    user_id     BIGINT   NOT NULL,
    sign_date   DATE     NOT NULL COMMENT '签到日期',
    points      INT      NOT NULL DEFAULT 0 COMMENT '本次获得积分',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_date (user_id, sign_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '每日签到';

-- ---------- 商品域 ----------
CREATE TABLE IF NOT EXISTS pms_category (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    parent_id    BIGINT      NOT NULL DEFAULT 0 COMMENT '父级 ID，0 为顶级',
    category_key VARCHAR(64) NOT NULL COMMENT '分类标识',
    name         VARCHAR(64) NOT NULL COMMENT '分类名称',
    description  VARCHAR(255)         DEFAULT NULL,
    icon         VARCHAR(255)         DEFAULT NULL,
    sort         INT         NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    create_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_key (category_key),
    KEY idx_parent (parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商品分类';

CREATE TABLE IF NOT EXISTS pms_product (
    id            BIGINT         NOT NULL AUTO_INCREMENT,
    title         VARCHAR(200)   NOT NULL COMMENT '商品标题',
    category_id   BIGINT         NOT NULL COMMENT '叶子分类 ID',
    category_key  VARCHAR(64)    NOT NULL COMMENT '叶子分类 key',
    parent_key    VARCHAR(64)    NOT NULL COMMENT '顶级分类 key',
    category_name VARCHAR(64)             DEFAULT NULL COMMENT '分类名（冗余）',
    brand_id      BIGINT                  DEFAULT NULL,
    brand_name    VARCHAR(64)             DEFAULT NULL COMMENT '品牌名（冗余）',
    price         DECIMAL(10, 2) NOT NULL COMMENT '售价',
    old_price     DECIMAL(10, 2)          DEFAULT NULL COMMENT '划线价',
    spec          VARCHAR(128)            DEFAULT NULL COMMENT '默认规格描述',
    stock         INT            NOT NULL DEFAULT 0 COMMENT '库存',
    sales         INT            NOT NULL DEFAULT 0 COMMENT '销量',
    rating        DECIMAL(2, 1)  NOT NULL DEFAULT 5.0 COMMENT '评分',
    cover         VARCHAR(255)            DEFAULT NULL COMMENT '封面图',
    tags          VARCHAR(255)            DEFAULT NULL COMMENT '标签，逗号分隔',
    is_hot        TINYINT        NOT NULL DEFAULT 0 COMMENT '热销',
    is_new        TINYINT        NOT NULL DEFAULT 0 COMMENT '新品',
    status        TINYINT        NOT NULL DEFAULT 1 COMMENT '1 上架 0 下架',
    deleted       TINYINT        NOT NULL DEFAULT 0,
    create_time   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_category (category_key, parent_key),
    KEY idx_price (price),
    KEY idx_sales (sales),
    KEY idx_hot_new (is_hot, is_new)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商品';

CREATE TABLE IF NOT EXISTS pms_review (
    id              BIGINT     NOT NULL AUTO_INCREMENT,
    user_id         BIGINT     NOT NULL,
    order_no        VARCHAR(32)         DEFAULT NULL,
    product_id      BIGINT     NOT NULL,
    score_desc      TINYINT    NOT NULL DEFAULT 5 COMMENT '描述相符',
    score_logistics TINYINT    NOT NULL DEFAULT 5 COMMENT '物流服务',
    score_service   TINYINT    NOT NULL DEFAULT 5 COMMENT '服务态度',
    content         VARCHAR(500)        DEFAULT NULL,
    images          VARCHAR(1000)       DEFAULT NULL COMMENT '图片地址，逗号分隔',
    anonymous       TINYINT    NOT NULL DEFAULT 0,
    reply           VARCHAR(500)        DEFAULT NULL COMMENT '商家回复',
    deleted         TINYINT    NOT NULL DEFAULT 0,
    create_time     DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_product (product_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商品评价';

-- ---------- 营销域 ----------
CREATE TABLE IF NOT EXISTS mkt_coupon_template (
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    name       VARCHAR(64)    NOT NULL COMMENT '券名称',
    type       VARCHAR(20)    NOT NULL COMMENT '券类型：full/percent/shipping/new',
    amount     DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '面额',
    unit       VARCHAR(8)     NOT NULL DEFAULT '¥' COMMENT '单位：¥ 或 折',
    threshold  DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '使用门槛',
    scope      VARCHAR(64)             DEFAULT NULL COMMENT '适用范围',
    valid_from DATE                    DEFAULT NULL,
    valid_to   DATE                    DEFAULT NULL,
    total      INT            NOT NULL DEFAULT 0 COMMENT '发行量',
    stock      INT            NOT NULL DEFAULT 0 COMMENT '剩余量',
    per_limit  INT            NOT NULL DEFAULT 1 COMMENT '每人限领',
    percent    INT            NOT NULL DEFAULT 0 COMMENT '已领百分比（展示用）',
    limited    TINYINT        NOT NULL DEFAULT 0 COMMENT '是否限时',
    soldout    TINYINT        NOT NULL DEFAULT 0 COMMENT '是否已抢光',
    status     TINYINT        NOT NULL DEFAULT 1,
    deleted    TINYINT        NOT NULL DEFAULT 0,
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_type (type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '优惠券模板';

CREATE TABLE IF NOT EXISTS mkt_user_coupon (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL,
    template_id  BIGINT      NOT NULL,
    status       TINYINT     NOT NULL DEFAULT 0 COMMENT '0 未使用 1 已使用 2 已过期',
    order_no     VARCHAR(32)          DEFAULT NULL COMMENT '核销订单号',
    receive_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    use_time     DATETIME             DEFAULT NULL,
    PRIMARY KEY (id),
    -- 唯一索引保证「重复领取」天然幂等
    UNIQUE KEY uk_user_template (user_id, template_id),
    KEY idx_user_status (user_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户优惠券';

CREATE TABLE IF NOT EXISTS mkt_seckill_session (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    session_time VARCHAR(16) NOT NULL COMMENT '场次时间，如 10:00',
    label       VARCHAR(32) NOT NULL COMMENT '场次标签',
    state       VARCHAR(16) NOT NULL DEFAULT 'wait' COMMENT 'wait/running/done',
    sort        INT         NOT NULL DEFAULT 0,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '秒杀场次';

CREATE TABLE IF NOT EXISTS mkt_seckill_item (
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    session_id  BIGINT         NOT NULL,
    product_id  BIGINT         NOT NULL,
    seckill_price DECIMAL(10, 2) NOT NULL COMMENT '秒杀价',
    old_price   DECIMAL(10, 2)          DEFAULT NULL,
    stock       INT            NOT NULL DEFAULT 0 COMMENT '剩余库存',
    total       INT            NOT NULL DEFAULT 0 COMMENT '总库存',
    sold        INT            NOT NULL DEFAULT 0 COMMENT '已售',
    tip         VARCHAR(64)             DEFAULT NULL,
    not_start   TINYINT        NOT NULL DEFAULT 0,
    deleted     TINYINT        NOT NULL DEFAULT 0,
    create_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '秒杀商品';

CREATE TABLE IF NOT EXISTS mkt_points_goods (
    id     BIGINT      NOT NULL AUTO_INCREMENT,
    name   VARCHAR(64) NOT NULL COMMENT '积分商品名',
    points INT         NOT NULL COMMENT '所需积分',
    icon   VARCHAR(32)          DEFAULT NULL COMMENT '图标',
    category VARCHAR(32)        DEFAULT NULL COMMENT '分类',
    description VARCHAR(255)    DEFAULT NULL,
    stock  INT         NOT NULL DEFAULT 0,
    deleted TINYINT    NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '积分商品';

CREATE TABLE IF NOT EXISTS mkt_points_exchange (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    goods_id   BIGINT      NOT NULL,
    goods_name VARCHAR(64) NOT NULL,
    points     INT         NOT NULL COMMENT '消耗积分',
    create_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '积分兑换记录';

-- ---------- 交易域 ----------
CREATE TABLE IF NOT EXISTS oms_cart_item (
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    user_id     BIGINT         NOT NULL,
    product_id  BIGINT         NOT NULL,
    title       VARCHAR(200)            DEFAULT NULL COMMENT '标题快照',
    cover       VARCHAR(255)            DEFAULT NULL COMMENT '封面快照',
    price       DECIMAL(10, 2) NOT NULL COMMENT '价格快照',
    spec        VARCHAR(128)            DEFAULT NULL COMMENT '规格',
    qty         INT            NOT NULL DEFAULT 1,
    checked     TINYINT        NOT NULL DEFAULT 1 COMMENT '是否勾选',
    deleted     TINYINT        NOT NULL DEFAULT 0,
    create_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '购物车';

CREATE TABLE IF NOT EXISTS oms_order (
    id            BIGINT         NOT NULL AUTO_INCREMENT,
    order_no      VARCHAR(32)    NOT NULL COMMENT '订单号',
    user_id       BIGINT         NOT NULL,
    status        TINYINT        NOT NULL DEFAULT 0 COMMENT '0 待付款 1 待发货 2 待收货 3 待评价 4 已完成 5 已取消',
    goods_amount  DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '商品总额',
    shipping_fee  DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '运费',
    discount      DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '优惠金额',
    pay_amount    DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '应付金额',
    coupon_id     BIGINT                  DEFAULT NULL,
    address_snap  VARCHAR(500)            DEFAULT NULL COMMENT '地址快照 JSON',
    remark        VARCHAR(255)            DEFAULT NULL COMMENT '订单备注',
    pay_method    VARCHAR(20)             DEFAULT NULL COMMENT '支付方式',
    pay_time      DATETIME                DEFAULT NULL,
    trade_no      VARCHAR(64)             DEFAULT NULL COMMENT '支付流水号',
    deliver_time  DATETIME                DEFAULT NULL,
    receive_time  DATETIME                DEFAULT NULL,
    finish_time   DATETIME                DEFAULT NULL,
    cancel_time   DATETIME                DEFAULT NULL,
    cancel_reason VARCHAR(128)            DEFAULT NULL,
    expire_time   DATETIME                DEFAULT NULL COMMENT '超时未支付取消时间',
    deleted       TINYINT        NOT NULL DEFAULT 0,
    create_time   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user_status (user_id, status),
    KEY idx_expire (status, expire_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '订单主表';

CREATE TABLE IF NOT EXISTS oms_order_item (
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    order_id    BIGINT         NOT NULL,
    order_no    VARCHAR(32)    NOT NULL,
    product_id  BIGINT         NOT NULL,
    title       VARCHAR(200)   NOT NULL COMMENT '标题快照',
    cover       VARCHAR(255)            DEFAULT NULL,
    spec        VARCHAR(128)            DEFAULT NULL,
    price       DECIMAL(10, 2) NOT NULL COMMENT '成交单价',
    qty         INT            NOT NULL,
    create_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_order (order_id),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '订单明细';

CREATE TABLE IF NOT EXISTS oms_order_status_log (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    order_no    VARCHAR(32) NOT NULL,
    from_status TINYINT     NOT NULL,
    to_status   TINYINT     NOT NULL,
    operator    VARCHAR(32)          DEFAULT NULL COMMENT '操作人/系统',
    remark      VARCHAR(255)         DEFAULT NULL,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '订单状态流转日志';

CREATE TABLE IF NOT EXISTS oms_aftersale (
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    user_id     BIGINT         NOT NULL,
    order_no    VARCHAR(32)    NOT NULL,
    type        VARCHAR(20)    NOT NULL COMMENT 'refund/return/exchange/repair',
    type_name   VARCHAR(32)             DEFAULT NULL,
    reason      VARCHAR(100)            DEFAULT NULL,
    content     VARCHAR(500)            DEFAULT NULL,
    images      VARCHAR(1000)           DEFAULT NULL COMMENT '凭证图片，逗号分隔',
    phone       VARCHAR(20)             DEFAULT NULL,
    amount      DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '退款金额',
    status      TINYINT        NOT NULL DEFAULT 0 COMMENT '0 处理中 1 已完成 2 已取消',
    deleted     TINYINT        NOT NULL DEFAULT 0,
    create_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user (user_id),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '售后申请';

-- ---------- 消息 & 内容域 ----------
CREATE TABLE IF NOT EXISTS sys_message (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL COMMENT '0 表示系统广播',
    type        VARCHAR(20) NOT NULL COMMENT 'order/logistics/coupon/system',
    title       VARCHAR(128) NOT NULL,
    description VARCHAR(500)         DEFAULT NULL,
    link        VARCHAR(255)         DEFAULT NULL COMMENT '点击跳转地址',
    is_read     TINYINT     NOT NULL DEFAULT 0 COMMENT '是否已读',
    deleted     TINYINT     NOT NULL DEFAULT 0,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_read (user_id, is_read)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '站内消息';

CREATE TABLE IF NOT EXISTS cms_faq (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    question    VARCHAR(255) NOT NULL,
    answer      VARCHAR(1000) NOT NULL,
    sort        INT          NOT NULL DEFAULT 0,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '帮助中心 FAQ';

CREATE TABLE IF NOT EXISTS cms_policy (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    title       VARCHAR(128) NOT NULL,
    items       TEXT COMMENT '条款，分行存储',
    sort        INT         NOT NULL DEFAULT 0,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '政策条款';
