package com.geekmall.modules.inventory.support;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.inventory.dto.BucketMergeDTO;
import com.geekmall.modules.inventory.dto.BucketTransferDTO;
import com.geekmall.modules.inventory.entity.InvBucket;
import com.geekmall.modules.inventory.enums.BucketBizType;
import com.geekmall.modules.inventory.enums.BucketOpType;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.security.MerchantSecurityUtils;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 桶间搬运执行器：调拨（桶 → 桶）与合并（多桶 → 一桶）。
 *
 * <p>两者都只在桶之间移动余量，不改变商品总库存 —— 守恒口径
 * {@code SUM(桶) == 商品总库存} 由审计流水逐笔留痕。</p>
 */
@Component
@RequiredArgsConstructor
public class BucketStockMover {

    private final BucketStore store;

    public String transfer(BucketTransferDTO dto) {
        Long shopId = MerchantSecurityUtils.getShopId();
        InvBucket from = store.requireBucket(dto.getFromBucketId(), shopId);
        InvBucket to = store.requireBucket(dto.getToBucketId(), shopId);
        if (!from.getProductId().equals(to.getProductId())) {
            throw new BizException(ResultCode.PARAM_ERROR, "来源桶与目标桶必须属于同一商品");
        }
        int qty = dto.getQty();
        if (!store.deductStock(from.getId(), qty)) {
            throw new BizException(ResultCode.OUT_OF_STOCK, "来源桶余量不足");
        }
        store.addStock(to.getId(), qty);

        String opNo = store.newOpNo("TRF");
        int fromAfter = store.stockOf(from.getId());
        store.log(
                from.getId(),
                from.getProductId(),
                shopId,
                BucketBizType.TRANSFER_OUT,
                -qty,
                fromAfter + qty,
                fromAfter,
                opNo,
                opNo,
                from.getDimensionValue(),
                dto.getOperator(),
                dto.getRemark());
        int toAfter = store.stockOf(to.getId());
        store.log(
                to.getId(),
                to.getProductId(),
                shopId,
                BucketBizType.TRANSFER_IN,
                qty,
                toAfter - qty,
                toAfter,
                opNo,
                opNo,
                to.getDimensionValue(),
                dto.getOperator(),
                dto.getRemark());

        store.saveOperation(
                BucketOpType.TRANSFER,
                from.getProductId(),
                shopId,
                from.getId(),
                to.getId(),
                qty,
                dto.getOperator(),
                dto.getRemark(),
                opNo);
        return opNo;
    }

    public String merge(BucketMergeDTO dto) {
        Long shopId = MerchantSecurityUtils.getShopId();
        Product product = store.requireProduct(dto.getProductId(), shopId);
        List<InvBucket> sources = new ArrayList<>();
        for (Long id : dto.getSourceBucketIds()) {
            InvBucket bucket = store.requireBucket(id, shopId);
            if (!bucket.getProductId().equals(dto.getProductId())) {
                throw new BizException(ResultCode.PARAM_ERROR, "来源桶与目标商品不一致");
            }
            sources.add(bucket);
        }
        InvBucket first = sources.get(0);
        String dimension = first.getDimension();
        String targetValue = StringUtils.hasText(dto.getTargetDimensionValue())
                ? dto.getTargetDimensionValue()
                : first.getDimensionValue();

        InvBucket target = dto.getTargetBucketId() != null
                ? store.requireBucket(dto.getTargetBucketId(), shopId)
                : store.findOrCreateBucket(product.getId(), shopId, dimension, targetValue, first);

        String opNo = store.newOpNo("MRG");
        int moved = 0;
        // 目标桶在合并前的余量：MERGE_IN 只记一条汇总流水，所以起点要提前取
        int targetBefore = store.stockOf(target.getId());
        for (InvBucket source : sources) {
            if (source.getId().equals(target.getId())) {
                continue;
            }
            int amount = store.moveAll(source.getId());
            if (amount <= 0) {
                continue;
            }
            store.addStockAndTotal(target.getId(), amount);
            moved += amount;
            store.log(
                    source.getId(),
                    product.getId(),
                    shopId,
                    BucketBizType.MERGE_OUT,
                    -amount,
                    amount,
                    0,
                    opNo,
                    opNo,
                    source.getDimensionValue(),
                    dto.getOperator(),
                    dto.getRemark());
        }
        // MERGE_IN 按「目标桶 × 本次合并单」汇总成一条，不能每个来源桶各写一条：
        // 流水表有 uk_biz_idempotent(biz_type, biz_id, bucket_id)，同一目标桶在同一
        // bizId 下只允许一条记录 —— 逐来源写会让「把 2 个桶合并进新桶」稳定抛
        // Duplicate entry，也就是合并功能只对「目标是其中一个来源桶」这一种情况可用。
        if (moved > 0) {
            store.log(
                    target.getId(),
                    product.getId(),
                    shopId,
                    BucketBizType.MERGE_IN,
                    moved,
                    targetBefore,
                    targetBefore + moved,
                    opNo,
                    opNo,
                    target.getDimensionValue(),
                    dto.getOperator(),
                    dto.getRemark());
        }
        store.saveOperation(
                BucketOpType.MERGE,
                product.getId(),
                shopId,
                null,
                target.getId(),
                moved,
                dto.getOperator(),
                dto.getRemark(),
                opNo);
        return opNo;
    }
}
