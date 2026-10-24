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
 *   <li><b>出库分流</b>：按优先级挑有货的桶逐个扣减，「桶空了」才换下一个桶；
 *       扣不足整体抛异常回滚（含已扣的桶）；</li>
 *   <li><b>守恒</b>：出库同步扣减商品总库存，回滚按原桶回补并补回总库存；</li>
 *   <li><b>幂等</b>：出库 / 回滚以 {@code order_no} 为幂等键（唯一索引 + 先查后写）。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class BucketStockDeductor {

    private final InvBucketMapper bucketMapper;
    private final InvBucketLogMapper logMapper;
    private final InvBucketRuleMapper ruleMapper;
    private final ProductStockService productStockService;
    private final BucketStore store;

    public boolean isBucketed(Long productId) {
        if (productId == null) {
            return false;
        }
        return bucketMapper.selectCount(new LambdaQueryWrapper<InvBucket>().eq(InvBucket::getProductId, productId)) > 0;
    }

    public boolean hasOutbound(String orderNo, Long productId) {
        if (!StringUtils.hasText(orderNo) || productId == null) {
            return false;
        }
        return logMapper.countByOrderProduct(BucketBizType.OUTBOUND.name(), orderNo, productId) > 0;
    }

    public BucketDeductResultVO deduct(BucketDeductDTO dto) {
        Long shopId = store.shopIdOrNull();
        Product product = store.requireProduct(dto.getProductId(), shopId);
        // 系统调用（交易域）没有商家上下文，审计流水仍应落在商品所属店铺，否则商家端查不到
        Long effectiveShopId = shopId != null ? shopId : product.getShopId();
        int qty = dto.getQty();

        // 幂等：同一单号 + 同一商品重复调用，直接返回既有结果，不重复扣减
        if (StringUtils.hasText(dto.getOrderNo())
                && logMapper.countByOrderProduct(BucketBizType.OUTBOUND.name(), dto.getOrderNo(), dto.getProductId())
                        > 0) {
            return resultFromLogs(dto.getOrderNo(), dto.getProductId());
        }

        long bucketCount = bucketMapper.selectCount(
                new LambdaQueryWrapper<InvBucket>().eq(InvBucket::getProductId, dto.getProductId()));

        // 交互边界：商品从未分桶时退化为「仅商品级扣减」，保持与旧链路兼容
        if (bucketCount == 0) {
            if (!productStockService.deductStock(dto.getProductId(), qty)) {
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
            List<InvBucket> candidates = bucketMapper.selectDeductCandidates(dto.getProductId());
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

        // 商品总库存同步扣减（保持 SUM(桶) == 商品总库存）
        if (!productStockService.deductStock(dto.getProductId(), qty)) {
            throw new BizException(ResultCode.OUT_OF_STOCK);
        }

        // 写审计流水（幂等键 = 单号 + 商品）
        for (BucketDeductResultVO.Detail detail : hit.values()) {
            int after = detail.getStockAfter();
            store.log(
                    detail.getBucketId(),
                    dto.getProductId(),
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
            return resultFromLogs(orderNo, null);
        }
        // 一张订单可能跨多个商品：逐个商品回滚，各商品的幂等互相独立
        Map<Long, Long> products = new LinkedHashMap<>();
        for (InvBucketLog item : outbound) {
            products.putIfAbsent(item.getProductId(), item.getShopId());
        }
        List<BucketDeductResultVO.Detail> details = new ArrayList<>();
        for (Long productId : products.keySet()) {
            details.addAll(doRollbackProduct(orderNo, productId));
        }
        int total =
                details.stream().mapToInt(BucketDeductResultVO.Detail::getQty).sum();

        BucketDeductResultVO result = new BucketDeductResultVO();
        result.setOrderNo(orderNo);
        result.setProductId(products.keySet().stream().findFirst().orElse(null));
        result.setRequestedQty(total);
        result.setDeductedQty(total);
        result.setDetails(details);
        return result;
    }

    public int rollbackProduct(String orderNo, Long productId) {
        if (!StringUtils.hasText(orderNo) || productId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "单号与商品 ID 不能为空");
        }
        return doRollbackProduct(orderNo, productId).stream()
                .mapToInt(BucketDeductResultVO.Detail::getQty)
                .sum();
    }

    /**
     * 把某单号在某商品上的出库量还回**原来那些桶**，并回补商品总库存。
     *
     * <p>幂等：已存在该 (单号, 商品) 的 ROLLBACK 流水时直接跳过，
     * 因此交易域的状态机重试、任务重跑都不会重复回补。</p>
     */
    private List<BucketDeductResultVO.Detail> doRollbackProduct(String orderNo, Long productId) {
        List<InvBucketLog> outbound = logMapper.selectOutboundByOrderProduct(orderNo, productId);
        if (outbound.isEmpty()
                || logMapper.countByOrderProduct(BucketBizType.ROLLBACK.name(), orderNo, productId) > 0) {
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
            productStockService.restoreStock(productId, total);
        }
        return details;
    }

    /** 从既有出库流水还原结果；productId 为 null 时取该单号的全部商品。 */
    private BucketDeductResultVO resultFromLogs(String orderNo, Long productId) {
        BucketDeductResultVO result = new BucketDeductResultVO();
        result.setOrderNo(orderNo);
        result.setProductId(productId);
        if (!StringUtils.hasText(orderNo)) {
            result.setDeductedQty(0);
            result.setDetails(List.of());
            return result;
        }
        List<InvBucketLog> outbound = productId == null
                ? logMapper.selectOutboundByOrder(orderNo)
                : logMapper.selectOutboundByOrderProduct(orderNo, productId);
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

    private void sortCandidates(List<InvBucket> candidates, DeductPolicy policy) {
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

    private DeductPolicy resolvePolicy(Long productId, Long shopId, String explicit) {
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
}
