package com.geekmall.modules.inventory.support;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.inventory.dto.BucketDeductDTO;
import com.geekmall.modules.inventory.entity.InvBucket;
import com.geekmall.modules.inventory.entity.InvBucketLog;
import com.geekmall.modules.inventory.enums.BucketBizType;
import com.geekmall.modules.inventory.enums.DeductPolicy;
import com.geekmall.modules.inventory.mapper.InvBucketLogMapper;
import com.geekmall.modules.inventory.mapper.InvBucketMapper;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.service.ProductStockService;
import com.geekmall.modules.product.service.SkuStockService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 批量出库执行器：把一批订单按 SKU 归并后扣减桶库存。
 *
 * <p>与单条 {@link BucketStockDeductor} 的区别只在「并发单元」：</p>
 * <ul>
 *   <li>该 SKU 是否分桶、候选桶、商品信息都<b>每批每 SKU 只查一次</b>；</li>
 *   <li>同一批次落在同一桶的 N 单合并成<b>一次 CAS</b>；</li>
 *   <li>SKU 与商品两层库存每批每 SKU 只更新一次，而不是每单一次。</li>
 * </ul>
 *
 * <p><b>失败策略</b>：任意桶 CAS 失败或余量不足时直接抛异常，由外层批量事务整体回滚，
 * 再退回逐单路径。批量是吞吐优化，不是正确性机制 —— 不在批内做复杂的重分配，
 * 以免把「部分成功」的语义扩散到调用方。</p>
 *
 * <p><b>幂等键与单条路径一致 = (单号, SKU)</b>：同一订单的多个 SKU 各自扣减一次。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BucketStockBatchDeductor {

    private final InvBucketMapper bucketMapper;
    private final InvBucketLogMapper logMapper;
    private final ProductStockService productStockService;
    private final SkuStockService skuStockService;
    private final BucketStockDeductor singleDeductor;
    private final MeterRegistry meterRegistry;

    public void deductBatch(List<BucketDeductDTO> deductions) {
        if (deductions == null || deductions.isEmpty()) {
            return;
        }
        Map<SkuKey, List<BucketDeductDTO>> bySku = groupBySku(deductions);
        List<Long> productIds = bySku.keySet().stream()
                .map(SkuKey::productId)
                .distinct()
                .sorted()
                .toList();

        Map<Long, Product> products = productStockService.findByIds(productIds);
        Map<Long, List<InvBucket>> bucketsByProduct = groupBuckets(productIds);
        Set<String> existed = existedOutbounds(deductions);

        for (Map.Entry<SkuKey, List<BucketDeductDTO>> entry : bySku.entrySet()) {
            SkuKey key = entry.getKey();
            List<BucketDeductDTO> skuDeductions = entry.getValue().stream()
                    .filter(d -> !existed.contains(outboundKey(d.getOrderNo(), key.skuId(), key.productId())))
                    .toList();
            if (skuDeductions.isEmpty()) {
                continue;
            }
            Product product = products.get(key.productId());
            if (product == null) {
                throw new BizException(ResultCode.NOT_FOUND, "商品不存在：" + key.productId());
            }
            List<InvBucket> skuBuckets = bucketsByProduct.getOrDefault(key.productId(), List.of()).stream()
                    .filter(bucket -> key.skuId().equals(bucket.getSkuId() == null ? 0L : bucket.getSkuId()))
                    .toList();
            if (skuBuckets.isEmpty()) {
                deductSkuOnly(key, product, skuDeductions);
            } else {
                deductBuckets(key, product, skuDeductions, skuBuckets);
            }
        }
    }

    /** 未分桶 SKU：整批合并成一次汇总层 CAS（SKU + 商品两层），行为与旧链路一致。 */
    private void deductSkuOnly(SkuKey key, Product product, List<BucketDeductDTO> deductions) {
        int total = sumQty(deductions);
        if (!deductAggregate(key, total)) {
            meterRegistry
                    .counter("mall_inventory_batch_product_cas_total", "result", "fail")
                    .increment();
            throw new BizException(ResultCode.OUT_OF_STOCK);
        }
        meterRegistry
                .counter("mall_inventory_batch_product_cas_total", "result", "success")
                .increment();
    }

    /** 分桶 SKU：按策略分配 → 每桶一次 CAS → 回查最终值 → 批量写审计流水 → 汇总层一次。 */
    private void deductBuckets(SkuKey key, Product product, List<BucketDeductDTO> deductions, List<InvBucket> buckets) {
        DeductPolicy policy = singleDeductor.resolvePolicy(product.getId(), product.getShopId(), null);
        // sortCandidates 会原地排序，这里必须给可变列表（stream().toList() 是不可变的）
        List<InvBucket> candidates = new ArrayList<>();
        for (InvBucket bucket : buckets) {
            if (bucket.getStatus() != null
                    && bucket.getStatus() == 1
                    && bucket.getStock() != null
                    && bucket.getStock() > 0) {
                candidates.add(bucket);
            }
        }
        if (candidates.isEmpty()) {
            throw new BizException(ResultCode.OUT_OF_STOCK);
        }
        BucketStockDeductor.sortCandidates(candidates, policy);

        Map<Long, Integer> capacity = new LinkedHashMap<>();
        for (InvBucket bucket : candidates) {
            capacity.put(bucket.getId(), bucket.getStock());
        }
        List<Allocation> allocations = allocate(deductions, candidates, capacity);

        Map<Long, Integer> totals = new LinkedHashMap<>();
        for (Allocation allocation : allocations) {
            totals.merge(allocation.bucketId(), allocation.qty(), Integer::sum);
        }
        List<Long> bucketIds = new ArrayList<>(totals.keySet());
        bucketIds.sort(Long::compareTo);
        for (Long bucketId : bucketIds) {
            if (bucketMapper.deductStock(bucketId, totals.get(bucketId)) == 0) {
                meterRegistry
                        .counter("mall_inventory_batch_bucket_cas_total", "result", "fail")
                        .increment();
                throw new BizException(ResultCode.OUT_OF_STOCK);
            }
            meterRegistry
                    .counter("mall_inventory_batch_bucket_cas_total", "result", "success")
                    .increment();
        }

        Map<Long, Integer> finalStocks = finalStocks(bucketIds);
        logMapper.batchInsert(buildLogs(key, product, allocations, finalStocks));

        int total = sumQty(deductions);
        if (!deductAggregate(key, total)) {
            meterRegistry
                    .counter("mall_inventory_batch_product_cas_total", "result", "fail")
                    .increment();
            throw new BizException(ResultCode.OUT_OF_STOCK);
        }
        meterRegistry
                .counter("mall_inventory_batch_product_cas_total", "result", "success")
                .increment();
    }

    /**
     * 在内存里按策略把每单数量分配到桶。
     *
     * <p>余量不足直接抛业务异常：正常路径下 Redis 预扣已经把单数限制在库存内，
     * 这里不足意味着预扣与 DB 记账不一致，应整批回滚后逐单重试。</p>
     */
    private List<Allocation> allocate(
            List<BucketDeductDTO> deductions, List<InvBucket> candidates, Map<Long, Integer> capacity) {
        List<Allocation> allocations = new ArrayList<>();
        for (BucketDeductDTO deduction : deductions) {
            int remaining = deduction.getQty() == null ? 0 : deduction.getQty();
            if (remaining <= 0) {
                continue;
            }
            for (InvBucket bucket : candidates) {
                int available = capacity.getOrDefault(bucket.getId(), 0);
                if (available <= 0) {
                    continue;
                }
                int take = Math.min(remaining, available);
                allocations.add(
                        new Allocation(deduction.getOrderNo(), bucket.getId(), bucket.getDimensionValue(), take));
                capacity.put(bucket.getId(), available - take);
                remaining -= take;
                if (remaining == 0) {
                    break;
                }
            }
            if (remaining > 0) {
                throw new BizException(ResultCode.OUT_OF_STOCK);
            }
        }
        return allocations;
    }

    /**
     * 回查桶的最终余量，并按分配顺序倒推每单的 before/after。
     *
     * <p>并发事务在同一批内不会修改这些桶（它们已被本事务的 CAS 锁定），
     * 因此最终值是准确的；倒推比逐单 SELECT 少 N-1 次查询。</p>
     */
    private List<InvBucketLog> buildLogs(
            SkuKey key, Product product, List<Allocation> allocations, Map<Long, Integer> finalStocks) {
        Map<Long, List<Allocation>> byBucket = new LinkedHashMap<>();
        for (Allocation allocation : allocations) {
            byBucket.computeIfAbsent(allocation.bucketId(), k -> new ArrayList<>())
                    .add(allocation);
        }
        List<InvBucketLog> logs = new ArrayList<>(allocations.size());
        for (Map.Entry<Long, List<Allocation>> entry : byBucket.entrySet()) {
            Long bucketId = entry.getKey();
            int after = finalStocks.getOrDefault(bucketId, 0);
            List<Allocation> bucketAllocations = entry.getValue();
            for (int i = bucketAllocations.size() - 1; i >= 0; i--) {
                Allocation allocation = bucketAllocations.get(i);
                int before = after + allocation.qty();
                InvBucketLog entity = new InvBucketLog();
                entity.setBucketId(bucketId);
                entity.setProductId(product.getId());
                entity.setSkuId(key.skuId());
                entity.setShopId(product.getShopId());
                entity.setBizType(BucketBizType.OUTBOUND.name());
                entity.setChangeQty(-allocation.qty());
                entity.setBeforeStock(before);
                entity.setAfterStock(after);
                entity.setOrderNo(allocation.orderNo());
                entity.setBizId(allocation.orderNo());
                entity.setBucketKey(allocation.bucketKey());
                entity.setOperator("seckill-batch");
                entity.setRemark("秒杀批量出库");
                logs.add(entity);
                after = before;
            }
        }
        return logs;
    }

    private Map<Long, Integer> finalStocks(List<Long> bucketIds) {
        Map<Long, Integer> stocks = new LinkedHashMap<>();
        for (InvBucket bucket : bucketMapper.selectBatchIds(bucketIds)) {
            stocks.put(bucket.getId(), bucket.getStock() == null ? 0 : bucket.getStock());
        }
        return stocks;
    }

    /** 按 (SKU, 商品) 归并；未指定 SKU 的按商品默认 SKU（无 SKU 商品为 0）解析。 */
    private Map<SkuKey, List<BucketDeductDTO>> groupBySku(List<BucketDeductDTO> deductions) {
        Map<SkuKey, List<BucketDeductDTO>> bySku = new LinkedHashMap<>();
        Map<Long, Long> skuIdByProduct = new HashMap<>();
        for (BucketDeductDTO deduction : deductions) {
            Long skuId = deduction.getSkuId();
            if (skuId == null || skuId <= 0) {
                skuId = skuIdByProduct.computeIfAbsent(deduction.getProductId(), this::defaultSkuId);
            }
            bySku.computeIfAbsent(new SkuKey(skuId, deduction.getProductId()), k -> new ArrayList<>())
                    .add(deduction);
        }
        return bySku;
    }

    private Long defaultSkuId(Long productId) {
        Long skuId = skuStockService.resolveSkuId(productId);
        return skuId == null ? 0L : skuId;
    }

    private Map<Long, List<InvBucket>> groupBuckets(List<Long> productIds) {
        Map<Long, List<InvBucket>> byProduct = new LinkedHashMap<>();
        for (InvBucket bucket : bucketMapper.selectByProductIds(productIds)) {
            byProduct
                    .computeIfAbsent(bucket.getProductId(), k -> new ArrayList<>())
                    .add(bucket);
        }
        return byProduct;
    }

    private Set<String> existedOutbounds(List<BucketDeductDTO> deductions) {
        List<String> orderNos = deductions.stream()
                .map(BucketDeductDTO::getOrderNo)
                .filter(no -> no != null && !no.isBlank())
                .distinct()
                .toList();
        if (orderNos.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(logMapper.selectOutboundKeys(orderNos));
    }

    private String outboundKey(String orderNo, Long skuId, Long productId) {
        return orderNo + ":" + skuId + ":" + productId;
    }

    /** 汇总层扣减：SKU 与商品两层（{@code sku_id = 0} 的历史商品退化为商品级）。 */
    private boolean deductAggregate(SkuKey key, int total) {
        return key.skuId() > 0
                ? skuStockService.deductSkuStock(key.skuId(), total)
                : productStockService.deductStock(key.productId(), total);
    }

    private int sumQty(List<BucketDeductDTO> deductions) {
        return deductions.stream()
                .mapToInt(d -> d.getQty() == null ? 0 : d.getQty())
                .sum();
    }

    /** 归并键：出库 / 幂等按 (SKU, 商品) 各自独立。 */
    private record SkuKey(Long skuId, Long productId) {}

    /** 一批分配中的一条：某单从某桶扣了多少。 */
    private record Allocation(String orderNo, Long bucketId, String bucketKey, int qty) {}
}
