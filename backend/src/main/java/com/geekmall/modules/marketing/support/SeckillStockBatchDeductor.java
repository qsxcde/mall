package com.geekmall.modules.marketing.support;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.marketing.mapper.SeckillBucketMapper;
import com.geekmall.modules.marketing.mapper.SeckillItemMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 秒杀活动库存的批量扣减器。
 *
 * <p>把同一批次内落到同一活动商品 / 同一桶的 N 单合并成一次 CAS：</p>
 * <ul>
 *   <li>分桶商品：扣 {@code mkt_seckill_bucket}，同一桶只发一次 UPDATE；</li>
 *   <li>不分桶商品：扣 {@code mkt_seckill_item}，同一活动商品只发一次 UPDATE。</li>
 * </ul>
 *
 * <p>按 {@code (itemId, bucketNo)} 排序执行，保证所有批次加锁顺序一致，避免跨批次死锁。</p>
 */
@Component
@RequiredArgsConstructor
public class SeckillStockBatchDeductor {

    private final SeckillItemMapper itemMapper;
    private final SeckillBucketMapper bucketMapper;
    private final MeterRegistry meterRegistry;

    /** 一条待扣减：requestId 用于批量结果映射，bucketNo 为 null 表示不分桶。 */
    public record Deduction(String requestId, Long itemId, Integer bucketNo, int qty) {}

    public void deduct(List<Deduction> deductions) {
        if (deductions == null || deductions.isEmpty()) {
            return;
        }
        Map<ItemBucket, Integer> grouped = new LinkedHashMap<>();
        for (Deduction deduction : deductions) {
            if (deduction.qty() <= 0) {
                continue;
            }
            grouped.merge(new ItemBucket(deduction.itemId(), deduction.bucketNo()), deduction.qty(), Integer::sum);
        }
        List<ItemBucket> keys = new ArrayList<>(grouped.keySet());
        keys.sort(Comparator.comparing(ItemBucket::itemId)
                .thenComparing(key -> key.bucketNo() == null ? -1 : key.bucketNo()));

        for (ItemBucket key : keys) {
            int qty = grouped.get(key);
            int affected = key.bucketNo() == null
                    ? itemMapper.deductStockByQty(key.itemId(), qty)
                    : bucketMapper.deductStockByQty(key.itemId(), key.bucketNo(), qty);
            if (affected == 0) {
                meterRegistry
                        .counter("mall_seckill_batch_stock_cas_total", "result", "fail")
                        .increment();
                throw new BizException(ResultCode.OUT_OF_STOCK, "手慢了，本场已抢光");
            }
            meterRegistry
                    .counter("mall_seckill_batch_stock_cas_total", "result", "success")
                    .increment();
        }
    }

    private record ItemBucket(Long itemId, Integer bucketNo) {}
}
