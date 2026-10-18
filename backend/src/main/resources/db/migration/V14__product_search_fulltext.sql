-- ============================================================
-- V14：商品搜索全文索引（MySQL FULLTEXT + ngram）
--
-- 背景：
--   买家端搜索原本是 `title/brand_name/category_name LIKE '%kw%'`，
--   前置通配符让 B+ 树索引完全失效，实测 100 万行时低频词要 5.2 s。
--
-- 本迁移只加索引，不改查询——实际是否启用由 `mall.search.engine` 控制
-- （默认 mysql-like，行为与升级前完全一致，可随时切回）。
--
-- 为什么用 ngram 分词器：
--   MySQL 默认分词按空格/标点切分，中文整句会被当成一个 token，
--   搜索「华为」匹配不到「华为 Mate 60」。ngram 按固定长度（默认 2）
--   切分，才能支持中文子串检索。
--
-- 查询侧注意（重要）：
--   ngram 下必须用【短语模式】（关键词整体加双引号），例如
--     MATCH(...) AGAINST('"折叠屏"' IN BOOLEAN MODE)
--   而非短语模式会把「折叠屏」拆成「折叠」「叠屏」两个 token 做 OR，
--   命中集比 `LIKE '%折叠屏%'` 大，语义不等价。
--
-- 索引体积：100 万行实测约 +180 MB，构建耗时约 50 s。
-- 可重复执行：先判断索引是否存在，避免重复迁移报错。
-- ============================================================

SET @idx_exists := (
    SELECT COUNT(1)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pms_product'
      AND INDEX_NAME = 'ft_search'
);

SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE pms_product ADD FULLTEXT INDEX ft_search (title, brand_name, category_name) WITH PARSER ngram',
    'SELECT ''ft_search already exists, skipped'' AS msg');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
