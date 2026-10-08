-- ============================================================
-- 数码商品「最近两年发布」数据 V10
--
-- 目标：
--   1) 为 pms_product 补充「发布时间」字段 release_time（此前只能靠 create_time 近似）；
--   2) 落库最近两年（CURDATE() - 2 YEAR ~ CURDATE()）公开发布的数码商品线索；
--   3) 通过唯一性判断去重、通过日期区间过滤做时间范围校验，脚本可重复执行。
--
-- 数据口径：
--   · release_time 取各厂商公开「发布会/开售」日期（Asia/Shanghai）；
--   · 分类沿用 V2 既有分类体系（phone-* / computer-laptop / pad / audio / watch）；
--   · brand_id 沿用 V2 既有编号（1 苹果 2 华为 3 小米 4 OPPO 5 荣耀 6 三星 7 联想 8 戴尔），
--     新增 9 vivo（vivo 与小米(红米) 复用各自集团品牌号）。
-- ============================================================

-- ---------- 1. 商品发布时间字段 ----------
ALTER TABLE pms_product
    ADD COLUMN release_time DATETIME DEFAULT NULL COMMENT '发布时间（厂商公开发布/开售时间）' AFTER is_new;

ALTER TABLE pms_product
    ADD KEY idx_release_time (release_time);

-- ---------- 2. 待入库线索（先落到临时表，便于统一做去重与时间范围校验） ----------
DROP TEMPORARY TABLE IF EXISTS tmp_digital_product_2y;
CREATE TEMPORARY TABLE tmp_digital_product_2y (
    title         VARCHAR(200) NOT NULL,
    category_id   BIGINT       NOT NULL,
    category_key  VARCHAR(64)  NOT NULL,
    parent_key    VARCHAR(64)  NOT NULL,
    category_name VARCHAR(64)           DEFAULT NULL,
    brand_id      BIGINT                DEFAULT NULL,
    brand_name    VARCHAR(64)  NOT NULL,
    price         DECIMAL(10, 2) NOT NULL,
    old_price     DECIMAL(10, 2)         DEFAULT NULL,
    cost          DECIMAL(10, 2)         DEFAULT NULL,
    spec          VARCHAR(128)           DEFAULT NULL,
    stock         INT          NOT NULL DEFAULT 0,
    safe_stock    INT          NOT NULL DEFAULT 10,
    sales         INT          NOT NULL DEFAULT 0,
    views         INT          NOT NULL DEFAULT 0,
    rating        DECIMAL(2, 1) NOT NULL DEFAULT 5.0,
    cover         VARCHAR(255)           DEFAULT NULL,
    tags          VARCHAR(255)           DEFAULT NULL,
    is_hot        TINYINT      NOT NULL DEFAULT 0,
    is_new        TINYINT      NOT NULL DEFAULT 0,
    release_time  DATETIME     NOT NULL,
    PRIMARY KEY (title, brand_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '最近两年数码商品入库暂存';

-- 商品线索：商品名称 / 品牌 / 类型 / 发布时间 / 核心卖点(tags)
INSERT INTO tmp_digital_product_2y
(title, category_id, category_key, parent_key, category_name, brand_id, brand_name,
 price, old_price, cost, spec, stock, safe_stock, sales, views, rating, cover, tags,
 is_hot, is_new, release_time) VALUES
-- ===== 手机：旗舰 =====
('华为 Mate 70 Pro',          7, 'phone-pro',  'phone', '旗舰机型',   2, '华为', 6499.00,  6999.00, 5600.00, '12G+512G 曜石黑',   88, 10, 0, 0, 5.0, '/img/n1.jpg',  '热销,原生鸿蒙,卫星通信', 1, 0, '2024-11-26 14:30:00'),
('小米 15',                   7, 'phone-pro',  'phone', '旗舰机型',   3, '小米', 4499.00,  4799.00, 3900.00, '12G+256G 丁香紫',  120, 10, 0, 0, 5.0, '/img/n2.jpg',  '热销,徕卡影像,澎湃OS',   1, 0, '2024-10-29 19:00:00'),
('三星 Galaxy S25 Ultra',     7, 'phone-pro',  'phone', '旗舰机型',   6, '三星', 9699.00, 10199.00, 8500.00, '12G+256G 钛黑',     45,  5, 0, 0, 5.0, '/img/n3.jpg',  '影像旗舰,Galaxy AI',     0, 1, '2025-01-23 02:00:00'),
('小米 15 Ultra',             7, 'phone-pro',  'phone', '旗舰机型',   3, '小米', 6499.00,  6999.00, 5700.00, '16G+512G 经典黑',   60, 10, 0, 0, 5.0, '/img/n4.jpg',  '影像旗舰,徕卡,一英寸主摄', 1, 0, '2025-02-27 19:00:00'),
('OPPO Find X8 Ultra',        7, 'phone-pro',  'phone', '旗舰机型',   4, 'OPPO', 6499.00,  6999.00, 5700.00, '16G+512G 大漠银月', 50, 10, 0, 0, 5.0, '/img/n5.jpg',  '影像旗舰,哈苏,双潜望',   0, 1, '2025-04-10 19:00:00'),
('vivo X200 Ultra',           7, 'phone-pro',  'phone', '旗舰机型',   9, 'vivo', 6499.00,  6999.00, 5750.00, '16G+512G 钛色',     50, 10, 0, 0, 5.0, '/img/n6.jpg',  '影像旗舰,蔡司,APO长焦',  0, 1, '2025-04-29 19:30:00'),
('华为 Pura 80 Ultra',        7, 'phone-pro',  'phone', '旗舰机型',   2, '华为', 9999.00, 10999.00, 8800.00, '16G+512G 鎏光金',   35,  5, 0, 0, 5.0, '/img/n7.jpg',  '影像旗舰,双潜望长焦',    0, 1, '2025-06-11 14:30:00'),
('iPhone 17 Pro Max',         7, 'phone-pro',  'phone', '旗舰机型',   1, '苹果',10999.00, 11999.00, 9600.00, '256G 星宇橙',      100, 10, 0, 0, 5.0, '/img/n8.jpg',  '热销,首发,A19 Pro',     1, 1, '2025-09-10 01:00:00'),
('小米 17 Pro',               7, 'phone-pro',  'phone', '旗舰机型',   3, '小米', 5499.00,  5799.00, 4800.00, '12G+512G 森野绿',   90, 10, 0, 0, 5.0, '/img/n9.jpg',  '新品,妙享背屏,骁龙8至尊', 0, 1, '2025-09-25 19:00:00'),
('vivo X300 Pro',             7, 'phone-pro',  'phone', '旗舰机型',   9, 'vivo', 5999.00,  6499.00, 5300.00, '16G+512G 银白',     70, 10, 0, 0, 5.0, '/img/n10.jpg', '新品,蔡司,自研影像芯片', 0, 1, '2025-10-13 19:30:00'),
('荣耀 Magic8 Pro',           7, 'phone-pro',  'phone', '旗舰机型',   5, '荣耀', 5699.00,  5999.00, 5000.00, '12G+512G 绒黑色',   80, 10, 0, 0, 5.0, '/img/n11.jpg', '新品,AI影像,青海湖电池', 0, 1, '2025-10-15 19:30:00'),
('OPPO Find X9 Pro',          7, 'phone-pro',  'phone', '旗舰机型',   4, 'OPPO', 5999.00,  6499.00, 5300.00, '16G+512G 霜白',     70, 10, 0, 0, 5.0, '/img/n12.jpg', '新品,哈苏,天玑9500',     0, 1, '2025-10-16 19:00:00'),
('华为 Mate 80 Pro',          7, 'phone-pro',  'phone', '旗舰机型',   2, '华为', 6499.00,  6999.00, 5700.00, '12G+512G 曜金黑',   90, 10, 0, 0, 5.0, '/img/n13.jpg', '新品,原生鸿蒙,麒麟9030', 0, 1, '2025-11-25 14:30:00'),
-- ===== 手机：性价比 =====
('红米 K80',                  8, 'phone-mid',  'phone', '性价比机型', 3, '小米', 2499.00,  2799.00, 2050.00, '12G+256G 雪岩白',  300, 20, 0, 0, 5.0, '/img/n14.jpg', '性价比,热销,2K直屏',     1, 0, '2024-11-27 19:00:00'),
-- ===== 手机：折叠屏 =====
('华为 Pura X',               9, 'phone-fold', 'phone', '折叠屏',     2, '华为', 7499.00,  7999.00, 6600.00, '12G+256G 幻夜黑',   40,  5, 0, 0, 5.0, '/img/n15.jpg', '折叠屏,原生鸿蒙5,阔折叠', 0, 1, '2025-03-20 14:30:00'),
('三星 Galaxy Z Fold7',       9, 'phone-fold', 'phone', '折叠屏',     6, '三星',13999.00, 14999.00,12500.00, '16G+512G 暗影蓝',   25,  5, 0, 0, 5.0, '/img/n16.jpg', '折叠屏,超薄,2亿像素',    0, 1, '2025-07-09 21:00:00'),
('华为 Mate X7',              9, 'phone-fold', 'phone', '折叠屏',     2, '华为',12999.00, 13999.00,11500.00, '16G+512G 曜石黑',   30,  5, 0, 0, 5.0, '/img/n17.jpg', '新品,折叠屏,双潜望',     0, 1, '2025-11-25 14:30:00'),
-- ===== 笔记本 =====
('联想拯救者 Y9000P 2025',    10, 'computer-laptop', 'computer', '笔记本电脑', 7, '联想', 9999.00, 10999.00, 8800.00, 'Ultra 9 + RTX 5070Ti', 55, 10, 0, 0, 5.0, '/img/n18.jpg', '游戏本,热销,2.5K 240Hz', 1, 0, '2025-04-15 20:00:00'),
('MacBook Air M5',            10, 'computer-laptop', 'computer', '笔记本电脑', 1, '苹果', 8999.00,  9499.00, 7900.00, '16G+512G 天蓝色',  50, 10, 0, 0, 5.0, '/img/n19.jpg', '新品,轻薄本,18小时续航', 0, 1, '2026-03-11 21:00:00'),
-- ===== 平板 =====
('iPad Pro M5',                3, 'pad',   'pad',   '平板电视',   1, '苹果', 8999.00,  9499.00, 7900.00, '256G WiFi 深空黑', 60, 10, 0, 0, 5.0, '/img/n20.jpg', '新品,生产力,双层OLED',   0, 1, '2025-10-22 21:00:00'),
-- ===== 耳机 / 音频 =====
('华为 FreeBuds Pro 4',        4, 'audio', 'audio', '智能穿戴',   2, '华为', 1499.00,  1599.00, 1200.00, '陶瓷白',          200, 20, 0, 0, 5.0, '/img/n21.jpg', '降噪,原生鸿蒙,星闪连接', 0, 1, '2024-11-26 14:30:00'),
('AirPods Pro 3',              4, 'audio', 'audio', '智能穿戴',   1, '苹果', 1899.00,  1999.00, 1600.00, 'USB-C 无线充电盒', 180, 20, 0, 0, 5.0, '/img/n22.jpg', '新品,主动降噪,心率监测', 0, 1, '2025-09-10 01:00:00'),
-- ===== 智能手表 =====
('Apple Watch Series 11',      5, 'watch', 'watch', '智能手表',   1, '苹果', 2999.00,  3199.00, 2500.00, '46mm 铝金属',     120, 15, 0, 0, 5.0, '/img/n23.jpg', '新品,健康监测,5G蜂窝',   0, 1, '2025-09-10 01:00:00'),
('Apple Watch Ultra 3',        5, 'watch', 'watch', '智能手表',   1, '苹果', 6499.00,  6799.00, 5600.00, '49mm 钛金属',      60, 10, 0, 0, 5.0, '/img/n24.jpg', '新品,运动,卫星通信',     0, 1, '2025-09-10 01:00:00');

-- ---------- 3. 去重 + 时间范围校验后入库 ----------
-- · 去重：同「商品名 + 品牌」在库中已存在（未逻辑删除）则跳过，脚本重复执行不产生脏数据；
-- · 时间范围：release_time 必须落在 [CURDATE() - 2 YEAR, CURDATE()] 内，区间外线索自动剔除。
INSERT INTO pms_product
(title, category_id, category_key, parent_key, category_name, brand_id, brand_name,
 price, old_price, cost, spec, stock, safe_stock, sales, views, rating, cover, tags,
 is_hot, is_new, status, merchant_status, release_time)
SELECT t.title, t.category_id, t.category_key, t.parent_key, t.category_name, t.brand_id, t.brand_name,
       t.price, t.old_price, t.cost, t.spec, t.stock, t.safe_stock, t.sales, t.views, t.rating, t.cover, t.tags,
       t.is_hot, t.is_new, 1, 'on', t.release_time
FROM tmp_digital_product_2y t
WHERE t.release_time >= DATE_SUB(CURDATE(), INTERVAL 2 YEAR)
  AND t.release_time <= CURDATE()
  AND NOT EXISTS (
      SELECT 1
      FROM pms_product p
      WHERE p.deleted = 0
        AND p.shop_id = 1
        AND p.title = t.title
        AND p.brand_name = t.brand_name
  );

DROP TEMPORARY TABLE IF EXISTS tmp_digital_product_2y;
