package com.geekmall.modules.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.geekmall.common.event.ProductChangedEvent;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.product.service.ProductStockService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 商品库存与批量读取实现。
 *
 * <p>扣减/回滚直接落到 {@code ProductMapper} 的条件更新语句上，
 * 把「影响行数为 0 即库存不足」的判读收敛到这里 —— 这是防超卖的唯一口径。</p>
 *
 * <p>商品维护类写入（新增 / 乐观锁更新 / 批量维护更新）成功后发布
 * {@link ProductChangedEvent}：搜索索引这类派生数据统一从这里扇出，
 * 而不是让每个调用方各发一次 —— 调用方会变多，扇出点必须只有一个。</p>
 */
@Service
@RequiredArgsConstructor
public class ProductStockServiceImpl implements ProductStockService {

    private final ProductMapper productMapper;

    private final ApplicationEventPublisher eventPublisher;

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
        publishChanged(product.getId(), "insert");
        return product.getId();
    }

    @Override
    public boolean updateWithVersion(Product product) {
        boolean updated = productMapper.updateById(product) > 0;
        if (updated) {
            publishChanged(product.getId(), "update");
        }
        return updated;
    }

    @Override
    public int updateProduct(Product product) {
        int rows = productMapper.updateById(product);
        if (rows > 0) {
            publishChanged(product.getId(), "maintain");
        }
        return rows;
    }

    /**
     * 发布商品变更事件。
     *
     * <p>只在<b>写入成功后</b>发布：乐观锁冲突（影响行数 0）时商品并没有变化，
     * 发事件只会让索引多做一次无意义的回表。事件本身不携带字段快照，
     * 消费方按 id 重新读库，因此这里不需要也不应该等待任何返回值。</p>
     */
    private void publishChanged(Long productId, String reason) {
        if (productId != null) {
            eventPublisher.publishEvent(ProductChangedEvent.of(productId, reason));
        }
    }
}
