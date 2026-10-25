package com.geekmall.modules.inventory.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.inventory.dto.BucketDeductDTO;
import com.geekmall.modules.inventory.entity.InvBucket;
import com.geekmall.modules.inventory.entity.InvBucketLog;
import com.geekmall.modules.inventory.entity.InvBucketRule;
import com.geekmall.modules.inventory.enums.BucketBizType;
import com.geekmall.modules.inventory.enums.DeductPolicy;
import com.geekmall.modules.inventory.mapper.InvBucketLogMapper;
import com.geekmall.modules.inventory.mapper.InvBucketMapper;
import com.geekmall.modules.inventory.mapper.InvBucketRuleMapper;
import com.geekmall.modules.inventory.vo.BucketDeductResultVO;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.service.ProductStockService;
import com.geekmall.modules.product.service.SkuStockService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 出库 / 回滚执行器：桶级 CAS 扣减、按策略选桶、按单号幂等回滚。
 *
 * <p>核心不变量：</p>
 * <ul>
 *   <li><b>桶是并发单元</b>：余量变更全部走 {@code UPDATE ... WHERE stock >= qty} 的 CAS，
 *       不做「先查再改」，避免读改写竞态；</li>
 *   <li><b>出库分流</b>：按优先级挑<b>本 SKU</b> 有货的桶逐个扣减，「桶空了」才换下一个桶；
 *       扣不足整体抛异常回滚（含已扣的桶）；</li>
 *   <li><b>三层守恒</b>：出库 = 目标 SKU 若干桶各一次 CAS + {@code pms_sku.stock} 一次 CAS
 *       + {@code pms_product.stock} 一次 CAS（后两层同句完成），由调用方事务统一收口，
 *       任一失败整体回滚；回滚按 {@code inv_bucket_log} 还回<b>原桶</b>并补回两层汇总；</li>
 *   <li><b>幂等</b>：出库 / 回滚以 <b>{@code order_no + sku_id}</b> 为幂等键（先查后写 +
 *       流水唯一索引兜底）。同一订单买两个 SKU 必须各自扣减 / 回补一次 —— 这是本次下沉
 *       SKU 后最容易做错的一处，按商品幂等会把第二个 SKU 吞掉。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class BucketStockDeductor {

    private final InvBucketMapper bucketMapper;
    private final InvBucketLogMapper logMapper;
    private final InvBucketRuleMapper ruleMapper;
    private final ProductStockService productStockService;
    private final SkuStockService skuStockService;
    private final BucketStore store;

    public boolean isBucketed(Long productId, Long skuId) {
        if (productId == null) {
            return false;
        }
        Long effectiveSkuId = store.resolveSkuId(productId, skuId);
        return bucketMapper.selectCount(new LambdaQueryWrapper<InvBucket>()
                        .eq(InvBucket::getProductId, productId)
                        .eq(InvBucket::getSkuId, effectiveSkuId))
                > 0;
    }

    public boolean hasOutbound(String orderNo, Long skuId, Long productId) {
        if (!StringUtils.hasText(orderNo) || productId == null) {
            return false;
        }
        Long effectiveSkuId = store.resolveSkuId(productId, skuId);
        return logMapper.countByOrderSku(BucketBizType.OUTBOUND.name(), orderNo, effectiveSkuId, productId) > 0;
    }

    public BucketDeductResultVO deduct(BucketDeductDTO dto) {
        Long shopId = store.shopIdOrNull();
        Product product = store.requireProduct(dto.getProductId(), shopId);
        // 系统调用（交易域）没有商家上下文，审计流水仍应落在商品所属店铺，否则商家端查不到
        Long effectiveShopId = shopId != null ? shopId : product.getShopId();
        Long skuId = store.resolveSkuId(dto.getProductId(), dto.getSkuId());
        int qty = dto.getQty();

        // 幂等：同一单号 + 同一 SKU 重复调用，直接返回既有结果，不重复扣减
        if (StringUtils.hasText(dto.getOrderNo())
                && logMapper.countByOrderSku(BucketBizType.OUTBOUND.name(), dto.getOrderNo(), skuId, dto.getProductId())
                        > 0) {
            return resultFromLogs(dto.getOrderNo(), skuId, dto.getProductId());
        }

        long bucketCount = bucketMapper.selectCount(new LambdaQueryWrapper<InvBucket>()
                .eq(InvBucket::getProductId, dto.getProductId())
                .eq(InvBucket::getSkuId, skuId));

        // 交互边界：该 SKU 从未分桶时退化为「仅 SKU 级扣减」，保持与旧链路兼容
        if (bucketCount == 0) {
            if (!deductAggregate(skuId, dto.getProductId(), qty)) {
                throw new BizException(ResultCode.OUT_OF_STOCK);
            }
            BucketDeductResultVO result = new BucketDeductResultVO();
            result.setOrderNo(dto.getOrderNo());
            result.setProductId(dto.getProductId());
            result.setRequestedQty(qty);
            result.setDeductedQty(qty);
            result.setDetails(List.of());
            return result;
        }

        DeductPolicy policy = resolvePolicy(dto.getProductId(), effectiveShopId, dto.getPolicy());

        // 按优先级逐桶扣减：累加每个桶的扣减量（同一桶多轮命中时合并记账）
        Map<Long, BucketDeductResultVO.Detail> hit = new LinkedHashMap<>();
        int remaining = qty;
        for (int round = 0; round < BucketStore.MAX_DEDUCT_ROUNDS && remaining > 0; round++) {
            List<InvBucket> candidates = bucketMapper.selectDeductCandidates(dto.getProductId(), skuId);
            if (candidates.isEmpty()) {
                break;
            }
            sortCandidates(candidates, policy);
            boolean progressed = false;
            for (InvBucket candidate : candidates) {
                if (remaining <= 0) {
                    break;
                }
                int take = Math.min(remaining, candidate.getStock() == null ? 0 : candidate.getStock());
                if (take <= 0) {
                    continue;
                }
                if (store.deductStock(candidate.getId(), take)) {
                    remaining -= take;
                    progressed = true;
                    accumulate(hit, candidate, take);
                }
            }
            if (!progressed) {
                break;
            }
        }
        if (remaining > 0) {
            // 桶余量不足：整个事务回滚，桶上的扣减一并撤销
            throw new BizException(ResultCode.OUT_OF_STOCK);
        }

        // SKU 库存与商品总库存同步扣减（保持 SUM(桶) == SKU 库存、SUM(SKU) == 商品总库存）
        if (!deductAggregate(skuId, dto.getProductId(), qty)) {
            throw new BizException(ResultCode.OUT_OF_STOCK);
        }

        // 写审计流水（幂等键 = 单号 + SKU）
        for (BucketDeductResultVO.Detail detail : hit.values()) {
            int after = detail.getStockAfter();
            store.log(
                    detail.getBucketId(),
                    dto.getProductId(),
                    skuId,
                    effectiveShopId,
                    BucketBizType.OUTBOUND,
                    -detail.getQty(),
                    after + detail.getQty(),
                    after,
                    dto.getOrderNo(),
                    dto.getOrderNo(),
                    detail.getDimensionValue(),
                    dto.getOperator(),
                    dto.getRemark());
        }

        BucketDeductResultVO result = new BucketDeductResultVO();
        result.setOrderNo(dto.getOrderNo());
        result.setProductId(dto.getProductId());
        result.setRequestedQty(qty);
        result.setDeductedQty(qty);
        result.setDetails(new ArrayList<>(hit.values()));
        return result;
    }

    public BucketDeductResultVO rollback(String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            throw new BizException(ResultCode.PARAM_ERROR, "单号不能为空");
        }
        List<InvBucketLog> outbound = logMapper.selectOutboundByOrder(orderNo);
        if (outbound.isEmpty()) {
            return resultFromLogs(orderNo, null, null);
        }
        // 一张订单可能跨多个商品 / SKU：逐个 (SKU, 商品) 回滚，各自的幂等互相独立
        Map<SkuKey, Long> keys = new LinkedHashMap<>();
        for (InvBucketLog item : outbound) {
            keys.putIfAbsent(new SkuKey(item.getSkuId(), item.getProductId()), item.getShopId());
        }
        List<BucketDeductResultVO.Detail> details = new ArrayList<>();
        Long firstProductId = null;
        for (SkuKey key : keys.keySet()) {
            if (firstProductId == null) {
                firstProductId = key.productId();
            }
            details.addAll(doRollbackSku(orderNo, key.skuId(), key.productId()));
        }
        int total =
                details.stream().mapToInt(BucketDeductResultVO.Detail::getQty).sum();

        BucketDeductResultVO result = new BucketDeductResultVO();
        result.setOrderNo(orderNo);
        result.setProductId(firstProductId);
        result.setRequestedQty(total);
        result.setDeductedQty(total);
        result.setDetails(details);
        return result;
    }

    /** 把某出库单在<b>单个 SKU</b> 上的量还回原桶（供交易域逐明细回滚调用，幂等）。 */
    public int rollbackSku(String orderNo, Long skuId, Long productId) {
        if (!StringUtils.hasText(orderNo) || productId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "单号与商品 ID 不能为空");
        }
        Long effectiveSkuId = store.resolveSkuId(productId, skuId);
        return doRollbackSku(orderNo, effectiveSkuId, productId).stream()
                .mapToInt(BucketDeductResultVO.Detail::getQty)
                .sum();
    }

    /**
     * 把某单号在某 SKU 上的出库量还回**原来那些桶**，并回补 SKU 与商品两层库存。
     *
     * <p>幂等：已存在该 (单号, SKU) 的 ROLLBACK 流水时直接跳过，
     * 因此交易域的状态机重试、任务重跑都不会重复回补。</p>
     */
    private List<BucketDeductResultVO.Detail> doRollbackSku(String orderNo, Long skuId, Long productId) {
        List<InvBucketLog> outbound = logMapper.selectOutboundByOrderSku(orderNo, skuId, productId);
        if (outbound.isEmpty()
                || logMapper.countByOrderSku(BucketBizType.ROLLBACK.name(), orderNo, skuId, productId) > 0) {
            return List.of();
        }
        Long shopId = outbound.get(0).getShopId();
        int total = 0;
        List<BucketDeductResultVO.Detail> details = new ArrayList<>();
        for (InvBucketLog item : outbound) {
            int qty = Math.abs(item.getChangeQty());
            store.addStock(item.getBucketId(), qty);
            total += qty;
            int after = store.stockOf(item.getBucketId());
            store.log(
                    item.getBucketId(),
                    productId,
                    skuId,
                    shopId,
                    BucketBizType.ROLLBACK,
                    qty,
                    after - qty,
                    after,
                    orderNo,
                    orderNo,
                    item.getBucketKey(),
                    "system",
                    "出库回滚");
            BucketDeductResultVO.Detail detail = new BucketDeductResultVO.Detail();
            detail.setBucketId(item.getBucketId());
            detail.setDimensionValue(item.getBucketKey());
            detail.setQty(qty);
            detail.setStockAfter(after);
            details.add(detail);
        }
        if (total > 0) {
            restoreAggregate(skuId, productId, total);
        }
        return details;
    }

    /** 从既有出库流水还原结果；skuId / productId 为 null 时取该单号的全部出库流水。 */
    private BucketDeductResultVO resultFromLogs(String orderNo, Long skuId, Long productId) {
        BucketDeductResultVO result = new BucketDeductResultVO();
        result.setOrderNo(orderNo);
        result.setProductId(productId);
        if (!StringUtils.hasText(orderNo)) {
            result.setDeductedQty(0);
            result.setDetails(List.of());
            return result;
        }
        List<InvBucketLog> outbound = skuId == null
                ? logMapper.selectOutboundByOrder(orderNo)
                : logMapper.selectOutboundByOrderSku(orderNo, skuId, productId);
        List<BucketDeductResultVO.Detail> details = new ArrayList<>();
        int total = 0;
        for (InvBucketLog item : outbound) {
            BucketDeductResultVO.Detail detail = new BucketDeductResultVO.Detail();
            detail.setBucketId(item.getBucketId());
            detail.setDimensionValue(item.getBucketKey());
            detail.setQty(Math.abs(item.getChangeQty()));
            detail.setStockAfter(item.getAfterStock());
            details.add(detail);
            total += Math.abs(item.getChangeQty());
        }
        if (productId == null && !outbound.isEmpty()) {
            result.setProductId(outbound.get(0).getProductId());
        }
        result.setRequestedQty(total);
        result.setDeductedQty(total);
        result.setDetails(details);
        return result;
    }

    /**
     * 汇总层扣减：{@code sku_id > 0} 走 {@link SkuStockService}（SKU + 商品两层同句），
     * {@code sku_id = 0} 的历史 / 无规格商品保持商品级单行扣减。
     */
    private boolean deductAggregate(Long skuId, Long productId, int qty) {
        return skuId != null && skuId > 0
                ? skuStockService.deductSkuStock(skuId, qty)
                : productStockService.deductStock(productId, qty);
    }

    /** 汇总层回补，与 {@link #deductAggregate} 对称。 */
    private void restoreAggregate(Long skuId, Long productId, int total) {
        if (skuId != null && skuId > 0) {
            skuStockService.restoreSkuStock(skuId, total);
        } else {
            productStockService.restoreStock(productId, total);
        }
    }

    static void sortCandidates(List<InvBucket> candidates, DeductPolicy policy) {
        Comparator<InvBucket> comparator =
                switch (policy) {
                    case EXPIRY_FIRST -> Comparator.comparing(
                                    (InvBucket b) -> b.getExpireDate(), Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(InvBucket::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(InvBucket::getId);
                    case MANUAL -> Comparator.comparingInt(
                                    (InvBucket b) -> b.getPriority() == null ? 0 : b.getPriority())
                            .thenComparing(InvBucket::getId);
                    case FIFO -> Comparator.comparing(
                                    InvBucket::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(InvBucket::getId);
                };
        candidates.sort(comparator);
    }

    private void accumulate(Map<Long, BucketDeductResultVO.Detail> hit, InvBucket bucket, int qty) {
        BucketDeductResultVO.Detail detail = hit.get(bucket.getId());
        if (detail == null) {
            detail = new BucketDeductResultVO.Detail();
            detail.setBucketId(bucket.getId());
            detail.setDimension(bucket.getDimension());
            detail.setDimensionValue(bucket.getDimensionValue());
            detail.setQty(qty);
            detail.setStockAfter(store.stockOf(bucket.getId()));
            hit.put(bucket.getId(), detail);
        } else {
            detail.setQty(detail.getQty() + qty);
            detail.setStockAfter(store.stockOf(bucket.getId()));
        }
    }

    DeductPolicy resolvePolicy(Long productId, Long shopId, String explicit) {
        if (StringUtils.hasText(explicit)) {
            return store.parsePolicy(explicit);
        }
        InvBucketRule rule = ruleMapper.selectOne(new LambdaQueryWrapper<InvBucketRule>()
                .eq(InvBucketRule::getEnabled, 1)
                .eq(shopId != null, InvBucketRule::getShopId, shopId)
                .and(w -> w.eq(InvBucketRule::getProductId, productId).or().isNull(InvBucketRule::getProductId))
                .orderByDesc(InvBucketRule::getProductId)
                .last("limit 1"));
        return rule == null ? DeductPolicy.FIFO : store.parsePolicy(rule.getDeductPolicy());
    }

    /** 幂等分组键：出库 / 回滚按 (SKU, 商品) 各自独立。 */
    private record SkuKey(Long skuId, Long productId) {}
}
