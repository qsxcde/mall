package com.geekmall.modules.inventory.support;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.inventory.dto.BucketAllocateDTO;
import com.geekmall.modules.inventory.entity.InvBucket;
import com.geekmall.modules.inventory.enums.BucketBizType;
import com.geekmall.modules.inventory.enums.BucketDimension;
import com.geekmall.modules.inventory.mapper.InvBucketMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 库存分配执行器：把商品总库存「重新布局」到各维度桶。
 *
 * <p>分配语义是覆盖式布局（stock 与 total 都置为目标量），而不是增量搬运；
 * 未被显式分配的余量落入「未分配」桶，保证 {@code SUM(桶) == 商品总库存} 守恒。</p>
 */
@Component
@RequiredArgsConstructor
public class BucketAllocator {

    private final InvBucketMapper bucketMapper;
    private final BucketStore store;

    /** 把明细按维度值合并，避免同一桶重复分配导致守恒被破坏。 */
    public Map<String, BucketAllocateDTO.Item> mergeItems(List<BucketAllocateDTO.Item> items) {
        Map<String, BucketAllocateDTO.Item> merged = new LinkedHashMap<>();
        for (BucketAllocateDTO.Item item : items) {
            String key = item.getDimensionValue().trim();
            BucketAllocateDTO.Item exist = merged.get(key);
            if (exist == null) {
                item.setDimensionValue(key);
                item.setQty(item.getQty() == null ? 0 : item.getQty());
                merged.put(key, item);
            } else {
                exist.setQty(exist.getQty() + (item.getQty() == null ? 0 : item.getQty()));
                if (item.getPriority() != null) {
                    exist.setPriority(item.getPriority());
                }
                if (item.getExpireDate() != null) {
                    exist.setExpireDate(item.getExpireDate());
                }
            }
        }
        return merged;
    }

    /**
     * 按合并后的明细重新布局该 SKU 的桶。
     *
     * <p>三步走：已有桶余量清零（留痕 ADJUST）→ 明细逐桶写入（ALLOCATE）→
     * 余量落入「未分配」桶。布局只动 {@code skuId} 自己的桶，
     * 保证 {@code SUM(桶 WHERE sku_id) == SKU 库存} 的 SKU 层守恒。</p>
     */
    public void relayout(
            Long productId,
            Long skuId,
            Long shopId,
            Long ruleId,
            BucketDimension dimension,
            Map<String, BucketAllocateDTO.Item> merged,
            int skuStock,
            String operator) {
        int placed = merged.values().stream()
                .mapToInt(i -> i.getQty() == null ? 0 : i.getQty())
                .sum();
        if (placed > skuStock) {
            throw new BizException(ResultCode.PARAM_ERROR, "分配总量 " + placed + " 超过库存 " + skuStock);
        }

        // 1) 重新布局：先把该 SKU 已有桶的余量清零（留痕为 ADJUST），再把明细逐桶写入。
        for (InvBucket exist : store.listBuckets(productId, skuId)) {
            int before = exist.getStock() == null ? 0 : exist.getStock();
            if (before > 0) {
                store.writeStock(exist, 0);
                store.log(
                        exist.getId(),
                        productId,
                        skuId,
                        shopId,
                        BucketBizType.ADJUST,
                        -before,
                        before,
                        0,
                        null,
                        null,
                        exist.getDimensionValue(),
                        operator,
                        "重新分配前清零");
            }
        }

        // 2) 写入显式分配的桶
        for (BucketAllocateDTO.Item item : merged.values()) {
            int qty = item.getQty() == null ? 0 : item.getQty();
            upsertBucket(productId, skuId, shopId, ruleId, dimension, item, qty, operator);
        }

        // 3) 未被显式分配的余量落入「未分配」桶，保证守恒
        int remainder = skuStock - placed;
        if (remainder > 0) {
            BucketAllocateDTO.Item rest = new BucketAllocateDTO.Item();
            rest.setDimensionValue(BucketStore.UNALLOCATED);
            upsertBucket(productId, skuId, shopId, ruleId, dimension, rest, remainder, operator);
        }
    }

    /** 写入 / 更新一个桶到指定数量（分配语义：stock 与 total 都置为 qty）。 */
    private void upsertBucket(
            Long productId,
            Long skuId,
            Long shopId,
            Long ruleId,
            BucketDimension dimension,
            BucketAllocateDTO.Item item,
            int qty,
            String operator) {
        InvBucket exist = store.findBucket(productId, skuId, dimension.name(), item.getDimensionValue());
        if (exist == null) {
            InvBucket bucket = new InvBucket();
            bucket.setProductId(productId);
            bucket.setSkuId(skuId == null ? 0L : skuId);
            bucket.setShopId(shopId);
            bucket.setRuleId(ruleId);
            bucket.setDimension(dimension.name());
            bucket.setDimensionValue(item.getDimensionValue());
            bucket.setWarehouse(item.getWarehouse());
            bucket.setBatchNo(item.getBatchNo());
            bucket.setExpireDate(item.getExpireDate());
            bucket.setRegion(item.getRegion());
            bucket.setStock(qty);
            bucket.setTotal(qty);
            bucket.setPriority(item.getPriority() == null ? 0 : item.getPriority());
            bucket.setStatus(1);
            bucket.setLastSyncTime(LocalDateTime.now());
            bucketMapper.insert(bucket);
            store.log(
                    bucket.getId(),
                    productId,
                    skuId,
                    shopId,
                    BucketBizType.ALLOCATE,
                    qty,
                    0,
                    qty,
                    null,
                    null,
                    item.getDimensionValue(),
                    operator,
                    "分配入桶");
            return;
        }
        int before = exist.getStock() == null ? 0 : exist.getStock();
        InvBucket update = new InvBucket();
        update.setId(exist.getId());
        update.setRuleId(ruleId);
        update.setWarehouse(item.getWarehouse());
        update.setBatchNo(item.getBatchNo());
        update.setExpireDate(item.getExpireDate());
        update.setRegion(item.getRegion());
        update.setStock(qty);
        update.setTotal(qty);
        update.setPriority(item.getPriority() == null ? 0 : item.getPriority());
        update.setLastSyncTime(LocalDateTime.now());
        bucketMapper.updateById(update);
        if (qty != before) {
            store.log(
                    exist.getId(),
                    productId,
                    skuId,
                    shopId,
                    BucketBizType.ALLOCATE,
                    qty - before,
                    before,
                    qty,
                    null,
                    null,
                    item.getDimensionValue(),
                    operator,
                    "分配调整");
        }
    }
}
