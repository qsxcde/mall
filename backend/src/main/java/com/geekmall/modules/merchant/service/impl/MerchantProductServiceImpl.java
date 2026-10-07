package com.geekmall.modules.merchant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.geekmall.common.cache.CacheNames;
import com.geekmall.common.cache.RedisBloomFilter;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.merchant.converter.MerchantProductConverter;
import com.geekmall.modules.merchant.dto.MerchantProductQueryDTO;
import com.geekmall.modules.merchant.dto.ProductBatchDTO;
import com.geekmall.modules.merchant.dto.ProductSaveDTO;
import com.geekmall.modules.merchant.mapper.MerchantStatsMapper;
import com.geekmall.modules.merchant.service.MerchantProductService;
import com.geekmall.modules.merchant.support.Numbers;
import com.geekmall.modules.merchant.vo.MerchantPageVO;
import com.geekmall.modules.merchant.vo.MerchantProductVO;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.security.MerchantSecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家端商品管理服务实现。
 */
@Service
@RequiredArgsConstructor
public class MerchantProductServiceImpl implements MerchantProductService {

    /** 商家侧商品状态全集，与前端 Tab 一一对应。 */
    private static final List<String> STATUS_KEYS = List.of("all", "on", "ware", "audit", "sold", "off", "trash");

    /** 「在售」状态码：只有该状态才对买家侧可见（pms_product.status = 1）。 */
    private static final String ON_SHELF_STATUS = "on";

    private final ProductMapper productMapper;
    private final MerchantStatsMapper statsMapper;
    /** 商品上架后必须同步进布隆过滤器，否则新品详情会被防穿透机制误判为「不存在」。 */
    private final RedisBloomFilter productBloomFilter;

    @Override
    public MerchantPageVO<MerchantProductVO> page(MerchantProductQueryDTO query) {
        Long shopId = MerchantSecurityUtils.getShopId();
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Product::getShopId, shopId);
        if (StringUtils.hasText(query.getStatus()) && !"all".equals(query.getStatus())) {
            wrapper.eq(Product::getMerchantStatus, query.getStatus());
        }
        if (StringUtils.hasText(query.getCat()) && !"all".equals(query.getCat())) {
            wrapper.eq(Product::getCategoryName, query.getCat());
        }
        if (StringUtils.hasText(query.getBrand()) && !"all".equals(query.getBrand())) {
            wrapper.eq(Product::getBrandName, query.getBrand());
        }
        applyStockFilter(wrapper, query.getStock());
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(w -> w.like(Product::getTitle, keyword)
                    .or().like(Product::getBrandName, keyword)
                    .or().like(Product::getCategoryName, keyword));
        }
        applySort(wrapper, query.getSort());

        Page<Product> page = new Page<>(query.getPage(), query.getSize());
        IPage<Product> result = productMapper.selectPage(page, wrapper);
        List<MerchantProductVO> list = result.getRecords().stream().map(MerchantProductConverter::toVO).toList();

        MerchantPageVO<MerchantProductVO> vo = new MerchantPageVO<>(
                list, result.getTotal(), result.getCurrent(), result.getSize());
        vo.put("tabs", buildTabs(shopId));
        vo.put("stats", buildStats(shopId));
        return vo;
    }

    @Override
    public Map<String, Object> filters() {
        Long shopId = MerchantSecurityUtils.getShopId();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("categories", statsMapper.distinctCategories(shopId));
        map.put("brands", statsMapper.distinctBrands(shopId));
        return map;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    // 商品变更后清空买家侧缓存，避免详情/首页读到旧数据
    @CacheEvict(cacheNames = {CacheNames.HOME_FLOORS, CacheNames.CATEGORY_TREE,
            CacheNames.PRODUCT_DETAIL, CacheNames.PRODUCT_RECOMMEND}, allEntries = true)
    public Long save(ProductSaveDTO dto) {
        Long shopId = MerchantSecurityUtils.getShopId();
        Product product = new Product();
        product.setShopId(shopId);
        product.setTitle(dto.getName());
        product.setCategoryName(dto.getCat());
        product.setBrandName(dto.getBrand());
        product.setPrice(dto.getPrice());
        product.setOldPrice(dto.getListPrice());
        product.setCost(dto.getCost());
        product.setSpec(dto.getSpec());
        product.setStock(dto.getStock());
        product.setSafeStock(dto.getSafeStock() == null ? 10 : dto.getSafeStock());
        product.setTags(dto.getTag());
        product.setCover(dto.getCover());
        product.setMerchantStatus(normalizeStatus(dto.getStatus()));
        // 买家侧可见性：仅「在售」上架
        product.setStatus(ON_SHELF_STATUS.equals(product.getMerchantStatus()) ? 1 : 0);

        if (dto.getId() == null) {
            // 新增：分类/品牌为冗余展示字段，这里用名称回填分类 key，保证买家侧筛选可用
            product.setCategoryId(0L);
            product.setCategoryKey(StringUtils.hasText(dto.getCat()) ? dto.getCat() : "other");
            product.setParentKey(StringUtils.hasText(dto.getCat()) ? dto.getCat() : "other");
            product.setRating(new BigDecimal("5.0"));
            product.setSales(0);
            product.setViews(0);
            product.setIsHot(0);
            product.setIsNew(1);
            productMapper.insert(product);
        } else {
            Product exist = productMapper.selectById(dto.getId());
            if (exist == null || !shopId.equals(exist.getShopId())) {
                throw BizException.of(ResultCode.NOT_FOUND);
            }
            product.setId(dto.getId());
            // 乐观锁：回传提交时读到的版本号；为空时插件不追加版本条件（兼容老客户端）
            product.setVersion(dto.getVersion());
            if (productMapper.updateById(product) == 0) {
                throw new BizException(ResultCode.BIZ_ERROR, "商品信息已被其他运营修改，请刷新后重新编辑");
            }
        }
        // 上架商品必须同步进布隆过滤器，否则买家访问该商品详情会被防穿透机制判为「不存在」
        if (product.getStatus() != null && product.getStatus() == 1) {
            productBloomFilter.add(String.valueOf(product.getId()));
        }
        return product.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = {CacheNames.HOME_FLOORS, CacheNames.CATEGORY_TREE,
            CacheNames.PRODUCT_DETAIL, CacheNames.PRODUCT_RECOMMEND}, allEntries = true)
    public int updateStatus(ProductBatchDTO dto) {
        Long shopId = MerchantSecurityUtils.getShopId();
        String status = normalizeStatus(dto.getStatus());
        List<Product> targets = selectOwned(shopId, dto.getIds());
        int affected = 0;
        boolean onShelf = ON_SHELF_STATUS.equals(status);
        for (Product product : targets) {
            Product update = new Product();
            update.setId(product.getId());
            update.setMerchantStatus(status);
            update.setStatus(onShelf ? 1 : 0);
            affected += productMapper.updateById(update);
            if (onShelf) {
                // 上架即纳入布隆过滤器：布隆只能加不能删，下架商品无需移除，
                // 后续详情请求会命中空值缓存（下架即视为不存在）
                productBloomFilter.add(String.valueOf(product.getId()));
            }
        }
        return affected;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = {CacheNames.HOME_FLOORS, CacheNames.CATEGORY_TREE,
            CacheNames.PRODUCT_DETAIL, CacheNames.PRODUCT_RECOMMEND}, allEntries = true)
    public int batchUpdatePrice(ProductBatchDTO dto) {
        Long shopId = MerchantSecurityUtils.getShopId();
        if (dto.getValue() == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "改价数值不能为空");
        }
        List<Product> targets = selectOwned(shopId, dto.getIds());
        int affected = 0;
        for (Product product : targets) {
            BigDecimal price = product.getPrice() == null ? BigDecimal.ZERO : product.getPrice();
            BigDecimal next;
            if ("amount".equals(dto.getMode())) {
                next = price.add(dto.getValue());
            } else {
                // 默认按百分比调整：value 为 -10 表示降价 10%
                BigDecimal factor = BigDecimal.ONE.add(
                        dto.getValue().divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
                next = price.multiply(factor);
            }
            next = next.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            Product update = new Product();
            update.setId(product.getId());
            update.setPrice(next);
            // 改价是「读原价 → 按比例算新价 → 写回」，必须带版本号：
            // 否则两次并发改价会各自基于旧价计算，后写者把前者的结果覆盖掉
            update.setVersion(product.getVersion());
            affected += productMapper.updateById(update);
        }
        return affected;
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    private List<Product> selectOwned(Long shopId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getShopId, shopId)
                .in(Product::getId, ids));
    }

    private void applyStockFilter(LambdaQueryWrapper<Product> wrapper, String stock) {
        if (stock == null || "all".equals(stock)) {
            return;
        }
        switch (stock) {
            case "warn" -> wrapper.gt(Product::getStock, 0).apply("stock <= safe_stock");
            case "out" -> wrapper.eq(Product::getStock, 0);
            case "plenty" -> wrapper.apply("stock > safe_stock * 2");
            case "normal" -> wrapper.apply("stock > safe_stock AND stock <= safe_stock * 2");
            default -> {
                // 未知值等同于「全部库存」
            }
        }
    }

    private void applySort(LambdaQueryWrapper<Product> wrapper, String sort) {
        String key = sort == null ? "" : sort;
        switch (key) {
            case "sales_desc" -> wrapper.orderByDesc(Product::getSales);
            case "views_desc" -> wrapper.orderByDesc(Product::getViews);
            case "price_desc" -> wrapper.orderByDesc(Product::getPrice);
            case "price_asc" -> wrapper.orderByAsc(Product::getPrice);
            case "stock_asc" -> wrapper.orderByAsc(Product::getStock);
            default -> wrapper.orderByDesc(Product::getUpdateTime);
        }
    }

    private Map<String, Object> buildTabs(Long shopId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : statsMapper.productStatusCounts(shopId)) {
            String status = String.valueOf(row.get("merchantStatus"));
            counts.merge(status, Numbers.l(row.get("cnt")), Long::sum);
        }
        Map<String, Object> tabs = new LinkedHashMap<>();
        long all = 0;
        for (String key : STATUS_KEYS) {
            if ("all".equals(key)) {
                continue;
            }
            long value = counts.getOrDefault(key, 0L);
            tabs.put(key, value);
            all += value;
        }
        tabs.put("all", all);
        return reorder(tabs);
    }

    /** 让 all 排在最前，便于前端直接渲染 Tab。 */
    private Map<String, Object> reorder(Map<String, Object> tabs) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("all", tabs.get("all"));
        for (String key : STATUS_KEYS) {
            if (!"all".equals(key)) {
                ordered.put(key, tabs.getOrDefault(key, 0L));
            }
        }
        return ordered;
    }

    private Map<String, Object> buildStats(Long shopId) {
        Map<String, Object> summary = statsMapper.productSummary(shopId);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("warn", Numbers.l(summary == null ? null : summary.get("warn")));
        stats.put("out", Numbers.l(summary == null ? null : summary.get("out")));
        stats.put("onSale", Numbers.l(summary == null ? null : summary.get("onSale")));
        stats.put("sales", Numbers.l(summary == null ? null : summary.get("sales")));
        return stats;
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return "on";
        }
        String value = status.trim();
        if (!List.of("on", "ware", "audit", "sold", "off", "trash").contains(value)) {
            throw new BizException(ResultCode.PARAM_ERROR, "非法的商品状态：" + value);
        }
        return value;
    }

    /** 供概览复用：库存预警商品。 */
    public List<Map<String, Object>> stockAlerts() {
        return new ArrayList<>(statsMapper.stockAlerts(MerchantSecurityUtils.getShopId()));
    }
}
