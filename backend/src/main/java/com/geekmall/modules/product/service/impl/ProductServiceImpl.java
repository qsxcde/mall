package com.geekmall.modules.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.geekmall.common.cache.CacheNames;
import com.geekmall.common.cache.RedisBloomFilter;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.PageResult;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.product.converter.ProductConverter;
import com.geekmall.modules.product.dto.ProductQueryDTO;
import com.geekmall.modules.product.entity.Category;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.CategoryMapper;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.product.service.ProductService;
import com.geekmall.modules.product.vo.CategoryVO;
import com.geekmall.modules.product.vo.HomeFloorVO;
import com.geekmall.modules.product.vo.ProductCardVO;
import com.geekmall.modules.product.vo.ProductDetailVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商品查询服务实现（纯读多写少的场景，可在此叠加 Redis 缓存）。
 */
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private static final int ON_SHELF = 1;

    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    /** 商品 ID 布隆过滤器：挡掉「一定不存在」的详情请求，防缓存穿透。 */
    private final RedisBloomFilter productBloomFilter;
    /** 自引用代理：detail 需要走带缓存的内部方法，自调用不会经过 Spring 缓存切面。 */
    private final ObjectProvider<ProductServiceImpl> selfProvider;

    @Override
    @Cacheable(cacheNames = CacheNames.CATEGORY_TREE)
    public List<CategoryVO> categoryTree() {
        List<Category> all = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .orderByAsc(Category::getSort)
                .orderByAsc(Category::getId));
        Map<Long, CategoryVO> voMap = new HashMap<>();
        List<CategoryVO> roots = new ArrayList<>();
        for (Category category : all) {
            voMap.put(category.getId(), ProductConverter.toCategoryVO(category));
        }
        for (Category category : all) {
            CategoryVO vo = voMap.get(category.getId());
            Long parentId = category.getParentId();
            if (parentId == null || parentId == 0L) {
                roots.add(vo);
            } else {
                CategoryVO parent = voMap.get(parentId);
                if (parent != null) {
                    parent.getChildren().add(vo);
                } else {
                    roots.add(vo);
                }
            }
        }
        return roots;
    }

    @Override
    public PageResult<ProductCardVO> page(ProductQueryDTO query) {
        LambdaQueryWrapper<Product> wrapper = buildQueryWrapper(query);
        applySort(wrapper, query.getSort());
        Page<Product> page = new Page<>(query.getPage(), query.getPageSize());
        IPage<Product> result = productMapper.selectPage(page, wrapper);
        return PageResult.of(result, ProductConverter::toCard);
    }

    /**
     * 商品详情（缓存三防的入口）。
     *
     * <p>防护顺序是刻意的：</p>
     * <ol>
     *   <li><b>布隆过滤器</b>：判定「一定不存在」直接 404，不查库、不写缓存 ——
     *       这是挡「随机 id 扫描」的关键（空值缓存对每次都换新 id 的攻击无效）；</li>
     *   <li><b>空值缓存</b>：被布隆误判放行的无效 id 只会查库一次，
     *       之后由 {@code productDetail} 中的空值缓存挡住（TTL 见 {@code mall.cache.null-ttl}）；</li>
     *   <li><b>互斥重建</b>：热点商品缓存失效时只有一个节点回源（{@code sync = true} 触发
     *       {@code ResilientRedisCache} 的分布式锁路径）。</li>
     * </ol>
     */
    @Override
    public ProductDetailVO detail(Long id) {
        if (id == null) {
            throw BizException.of(ResultCode.NOT_FOUND);
        }
        if (!productBloomFilter.mightContain(String.valueOf(id))) {
            throw BizException.of(ResultCode.NOT_FOUND);
        }
        ProductDetailVO vo = selfProvider.getObject().cachedDetail(id);
        if (vo == null) {
            throw BizException.of(ResultCode.NOT_FOUND);
        }
        return vo;
    }

    /**
     * 走缓存的详情读取：<b>找不到时返回 null 而不是抛异常</b>。
     *
     * <p>这是「空值缓存」能否生效的前提 —— {@code @Cacheable} 方法一旦抛异常就不会写缓存，
     * 无效 id 会次次打库。因此把「不存在」表达为返回值，由调用方 {@link #detail} 决定抛 404。</p>
     *
     * <p>{@code sync = true} 会让并发未命中走 {@code Cache#get(key, Callable)}，
     * 即 {@link com.geekmall.common.cache.ResilientRedisCache} 中带分布式锁的重建路径。</p>
     */
    @Cacheable(cacheNames = CacheNames.PRODUCT_DETAIL, key = "#id", sync = true)
    public ProductDetailVO cachedDetail(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null || (product.getStatus() != null && product.getStatus() != ON_SHELF)) {
            return null;
        }
        return ProductConverter.toDetail(product);
    }

    @Override
    @Cacheable(cacheNames = CacheNames.PRODUCT_RECOMMEND, key = "#id + ':' + #limit")
    public List<ProductCardVO> recommend(Long id, int limit) {
        // 不存在的商品返回空列表（会被缓存），同样先过布隆过滤器避免无效 id 打库
        if (id == null || !productBloomFilter.mightContain(String.valueOf(id))) {
            return List.of();
        }
        Product product = productMapper.selectById(id);
        if (product == null) {
            return List.of();
        }
        List<Product> list = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, ON_SHELF)
                .eq(Product::getParentKey, product.getParentKey())
                .ne(Product::getId, id)
                .orderByDesc(Product::getSales)
                .last("limit " + Math.max(1, limit)));
        return list.stream().map(ProductConverter::toCard).toList();
    }

    @Override
    @Cacheable(cacheNames = CacheNames.HOME_FLOORS)
    public HomeFloorVO homeFloors() {
        HomeFloorVO vo = new HomeFloorVO();
        vo.setCategories(categoryTree());
        vo.setHotProducts(productMapper.selectList(new LambdaQueryWrapper<Product>()
                        .eq(Product::getStatus, ON_SHELF)
                        .orderByDesc(Product::getSales)
                        .last("limit 8"))
                .stream().map(ProductConverter::toCard).toList());
        vo.setNewProducts(productMapper.selectList(new LambdaQueryWrapper<Product>()
                        .eq(Product::getStatus, ON_SHELF)
                        .eq(Product::getIsNew, 1)
                        .orderByDesc(Product::getId)
                        .last("limit 8"))
                .stream().map(ProductConverter::toCard).toList());
        return vo;
    }

    @Override
    public long countOnShelf() {
        Long count = productMapper.selectCount(new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, ON_SHELF));
        return count == null ? 0L : count;
    }

    @Override
    public List<ProductCardVO> cardsByIds(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = productIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Product> productMap = productMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity(), (a, b) -> a));
        // 保持传入顺序（收藏/足迹按时间排序），并跳过已删除的商品
        return ids.stream()
                .map(productMap::get)
                .filter(Objects::nonNull)
                .map(ProductConverter::toCard)
                .toList();
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    private LambdaQueryWrapper<Product> buildQueryWrapper(ProductQueryDTO query) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Product::getStatus, ON_SHELF);

        // 子分类优先，其次顶级分类
        if (StringUtils.hasText(query.getSub())) {
            wrapper.eq(Product::getCategoryKey, query.getSub());
        } else if (StringUtils.hasText(query.getCat())) {
            wrapper.eq(Product::getParentKey, query.getCat());
        }

        // 关键词：标题 / 品牌 / 分类名，OR 需要与前面的 AND 分组，避免条件泄漏
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(w -> w.like(Product::getTitle, keyword)
                    .or().like(Product::getBrandName, keyword)
                    .or().like(Product::getCategoryName, keyword));
        }

        if (StringUtils.hasText(query.getBrand())) {
            List<String> brands = Arrays.stream(query.getBrand().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (!brands.isEmpty()) {
                wrapper.in(Product::getBrandName, brands);
            }
        }
        if (query.getPriceMin() != null) {
            wrapper.ge(Product::getPrice, query.getPriceMin());
        }
        if (query.getPriceMax() != null) {
            wrapper.le(Product::getPrice, query.getPriceMax());
        }
        if (Boolean.TRUE.equals(query.getHot())) {
            wrapper.eq(Product::getIsHot, 1);
        }
        if (Boolean.TRUE.equals(query.getIsNew())) {
            wrapper.eq(Product::getIsNew, 1);
        }
        return wrapper;
    }

    private void applySort(LambdaQueryWrapper<Product> wrapper, String sort) {
        if (sort == null) {
            sort = "";
        }
        switch (sort) {
            case "sales" -> wrapper.orderByDesc(Product::getSales);
            case "price_asc" -> wrapper.orderByAsc(Product::getPrice);
            case "price_desc" -> wrapper.orderByDesc(Product::getPrice);
            case "rating" -> wrapper.orderByDesc(Product::getRating);
            case "new" -> wrapper.orderByDesc(Product::getId);
            default -> wrapper.orderByDesc(Product::getIsHot).orderByDesc(Product::getSales);
        }
    }
}
