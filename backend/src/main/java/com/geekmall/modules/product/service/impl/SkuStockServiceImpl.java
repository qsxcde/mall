package com.geekmall.modules.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.modules.product.entity.Sku;
import com.geekmall.modules.product.mapper.SkuMapper;
import com.geekmall.modules.product.service.SkuStockService;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * SKU 库存与批量读取实现。
 *
 * <p>扣减 / 回补落到 {@link SkuMapper} 的条件更新语句上（单语句两层同扣），
 * 把「影响行数为 0 即库存不足」的判读收敛到这里 —— 这是 SKU 防超卖的唯一口径。</p>
 */
@Service
@RequiredArgsConstructor
public class SkuStockServiceImpl implements SkuStockService {

    private final SkuMapper skuMapper;

    @Override
    public boolean deductSkuStock(Long skuId, int qty) {
        return skuMapper.deductStock(skuId, qty) > 0;
    }

    @Override
    public void restoreSkuStock(Long skuId, int qty) {
        skuMapper.restoreStock(skuId, qty);
    }

    @Override
    public Map<Long, Sku> findByIds(Collection<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Sku> result = new HashMap<>();
        for (Sku sku : skuMapper.selectBatchIds(skuIds.stream().distinct().toList())) {
            result.put(sku.getId(), sku);
        }
        return result;
    }

    @Override
    public List<Sku> findByProductId(Long productId) {
        if (productId == null) {
            return List.of();
        }
        return skuMapper.selectList(
                new LambdaQueryWrapper<Sku>().eq(Sku::getProductId, productId).orderByAsc(Sku::getId));
    }

    @Override
    public Long resolveSkuId(Long productId) {
        List<Sku> skus = findByProductId(productId);
        return skus.isEmpty() ? null : skus.get(0).getId();
    }
}
