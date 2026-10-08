package com.geekmall.modules.marketing.bucket;

import com.geekmall.common.constant.RedisKeys;
import com.geekmall.modules.marketing.entity.SeckillBucket;
import com.geekmall.modules.marketing.entity.SeckillItem;
import com.geekmall.modules.marketing.mapper.SeckillBucketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 秒杀库存分桶的「建桶 / 预热 / 汇总」支撑。
 *
 * <p>本类只负责桶的<b>结构与缓存</b>，不参与抢购判定（判定在
 * {@code SeckillServiceImpl#reserveBucket} 的 Lua 内一次原子完成）。</p>
 *
 * <h3>为什么不把桶数存成「行数」而要显式配置</h3>
 * <p>抢购是超高 QPS 路径，Lua 的 {@code KEYS[1..N]} 必须在发指令<b>之前</b>就确定 N。
 * 因此把桶数放在 {@code mkt_seckill_item.bucket_count}（与商品元数据同一条 SQL 取出、
 * 同一个 3s 本地缓存命中），桶表只承载「状态」，不再额外查一次库。</p>
 *
 * <h3>守恒边界</h3>
 * <p>只在<b>完全没有桶行</b>时按 {@code mkt_seckill_item.stock} 均分建桶；只要桶行存在，
 * 一律以桶行为权威，绝不在运行时重新拆分（活动进行中 {@code item.stock} 已是过期快照，
 * 重拆会凭空造库存）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillBucketManager {

    private final SeckillBucketMapper bucketMapper;
    private final StringRedisTemplate redisTemplate;

    /** 桶数下界兜底：未配置 / 非法值一律按 1（不分桶）处理。 */
    public static int bucketCount(SeckillItem item) {
        Integer count = item == null ? null : item.getBucketCount();
        return count == null || count < 1 ? 1 : count;
    }

    /** 是否启用分桶：桶数 &gt; 1 才走分桶路径，= 1 完全沿用单行库存的旧链路。 */
    public boolean isBucketed(SeckillItem item) {
        return bucketCount(item) > 1;
    }

    /**
     * 保证桶行存在：缺失时按 {@code item.stock} 均分创建，已存在则原样返回。
     *
     * <p>并发初始化（多实例同时启动预热）由主键冲突兜底：一方插入成功后另一方重读即可，
     * 不会出现重复建桶或多出库存。</p>
     */
    public List<SeckillBucket> ensureBuckets(SeckillItem item) {
        int count = bucketCount(item);
        if (count <= 1) {
            return List.of();
        }
        List<SeckillBucket> existing = bucketMapper.selectByItem(item.getId());
        if (!existing.isEmpty()) {
            return existing;
        }
        int stock = item.getStock() == null ? 0 : item.getStock();
        List<SeckillBucket> created = split(item.getId(), count, stock);
        try {
            bucketMapper.insertBatch(created);
        } catch (DuplicateKeyException ex) {
            // 另一个实例先建好了：直接采用它建出的桶，保持守恒
            log.info("秒杀分桶并发初始化，改用已有桶：itemId={}", item.getId());
            return bucketMapper.selectByItem(item.getId());
        }
        log.info("秒杀库存分桶已初始化：itemId={}, 桶数={}, 总库存={}", item.getId(), count, stock);
        return created;
    }

    /**
     * 按桶把 DB 余量预热进 Redis。
     *
     * <p>用 {@code setIfAbsent} 而非 {@code set}：滚动发布时新实例不能把进行中的扣减复位
     * （与 {@code SeckillStockWarmUp} 的取舍一致）；因此本方法可以安全地重复调用，
     * 也就能作为「Redis 桶 key 丢失」时的自愈手段。</p>
     */
    public void warmUp(SeckillItem item) {
        if (!isBucketed(item)) {
            return;
        }
        for (SeckillBucket bucket : ensureBuckets(item)) {
            String key = RedisKeys.seckillBucketStock(item.getId(), bucket.getBucketNo());
            redisTemplate.opsForValue().setIfAbsent(key,
                    String.valueOf(bucket.getStock() == null ? 0 : bucket.getStock()));
        }
    }

    /**
     * 批量汇总若干活动商品的桶余量，供列表页展示。
     *
     * <p>分桶商品的权威余量是 {@code SUM(桶)} —— {@code mkt_seckill_item.stock}
     * 自启用分桶起就不再有逐单更新。</p>
     */
    public Map<Long, BucketAggregate> aggregate(Collection<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = new ArrayList<>(itemIds);
        Map<Long, long[]> acc = new LinkedHashMap<>();
        for (SeckillBucket bucket : bucketMapper.selectByItems(ids)) {
            long[] sum = acc.computeIfAbsent(bucket.getItemId(), k -> new long[2]);
            sum[0] += bucket.getStock() == null ? 0 : bucket.getStock();
            sum[1] += bucket.getTotal() == null ? 0 : bucket.getTotal();
        }
        Map<Long, BucketAggregate> result = new LinkedHashMap<>();
        acc.forEach((itemId, sum) -> result.put(itemId, new BucketAggregate(sum[0], sum[1])));
        return result;
    }

    /**
     * 把一份库存均分到 N 个桶：余数分给前若干个桶，保证 {@code SUM(stock) == stock}。
     */
    static List<SeckillBucket> split(Long itemId, int count, int stock) {
        if (count <= 0) {
            return Collections.emptyList();
        }
        int base = stock / count;
        int remainder = stock % count;
        List<SeckillBucket> buckets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int value = base + (i < remainder ? 1 : 0);
            SeckillBucket bucket = new SeckillBucket();
            bucket.setItemId(itemId);
            bucket.setBucketNo(i);
            bucket.setStock(value);
            bucket.setTotal(value);
            buckets.add(bucket);
        }
        return buckets;
    }

    /** 某活动商品全部桶的余量合计与初始量合计。 */
    public record BucketAggregate(long stock, long total) {

        /** 桶口径的已售量。 */
        public long sold() {
            return total - stock;
        }
    }
}
