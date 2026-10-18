package com.geekmall.modules.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.product.service.ProductStockService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 商品库存与批量读取实现。
 *
 * <p>扣减/回滚直接落到 {@code ProductMapper} 的条件更新语句上，
 * 把「影响行数为 0 即库存不足」的判读收敛到这里 —— 这是防超卖的唯一口径。</p>
 */
@Service
@RequiredArgsConstructor
public class ProductStockServiceImpl implements ProductStockService {

    private final ProductMapper productMapper;

    @Override
    public boolean deductStock(Long productId, int qty) {
        return productMapper.deductStock(productId, qty) > 0;
    }

    @Override
    public void restoreStock(Long productId, int qty) {
        productMapper.restoreStock(productId, qty);
    }

    @Override
    public Product findById(Long productId) {
        return productMapper.selectById(productId);
    }

    @Override
    public Map<Long, Product> findByIds(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Product> result = new HashMap<>();
        for (Product product :
                productMapper.selectBatchIds(productIds.stream().distinct().toList())) {
            result.put(product.getId(), product);
        }
        return result;
    }

    @Override
    public List<Product> findByIdsInOrder(List<Long> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Product> byId = findByIds(orderedIds);
        List<Product> ordered = new ArrayList<>(orderedIds.size());
        for (Long id : orderedIds) {
            Product product = byId.get(id);
            if (product != null) {
                ordered.add(product);
            }
        }
        return ordered;
    }

    @Override
    public List<Product> findOnShelfByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return productMapper.selectOnShelfByIds(ids);
    }

    // ------------------------------------------------------------------
    // 商家端商品维护
    // ------------------------------------------------------------------

    @Override
    public IPage<Product> pageByShop(Long shopId, LambdaQueryWrapper<Product> wrapper, long page, long size) {
        LambdaQueryWrapper<Product> target = wrapper == null ? new LambdaQueryWrapper<>() : wrapper;
        return productMapper.selectPage(new Page<>(page, size), target.eq(Product::getShopId, shopId));
    }

    @Override
    public List<Product> listByShop(Long shopId, LambdaQueryWrapper<Product> wrapper) {
        LambdaQueryWrapper<Product> target = wrapper == null ? new LambdaQueryWrapper<>() : wrapper;
        return productMapper.selectList(target.eq(Product::getShopId, shopId));
    }

    @Override
    public Product findByShopAndId(Long shopId, Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null || !java.util.Objects.equals(shopId, product.getShopId())) {
            return null;
        }
        return product;
    }

    @Override
    public Long insertProduct(Product product) {
        productMapper.insert(product);
        return product.getId();
    }

    @Override
    public boolean updateWithVersion(Product product) {
        return productMapper.updateById(product) > 0;
    }

    @Override
    public int updateProduct(Product product) {
        return productMapper.updateById(product);
    }
}
