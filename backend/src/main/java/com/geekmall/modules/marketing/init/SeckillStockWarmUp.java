package com.geekmall.modules.marketing.init;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.constant.RedisKeys;
import com.geekmall.modules.marketing.bucket.SeckillBucketManager;
import com.geekmall.modules.marketing.entity.SeckillItem;
import com.geekmall.modules.marketing.mapper.SeckillItemMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 秒杀库存预热：启动时把 DB 库存同步到 Redis 计数（<b>仅在 Redis 缺失该 key 时写入</b>）。
 *
 * <p>为什么需要它：Redis 中的库存是扣减后的值，应用重启后若 Redis 仍保留旧值，
 * 会出现「DB 有库存但 Redis 显示 0」的不一致。启动时以 DB 为准同步一次即可对齐。</p>
 *
 * <h3>为什么必须用 setIfAbsent 而不是 set（多实例安全）</h3>
 * <p>原实现每次启动都无条件 {@code set} 覆盖。单实例没问题，但<b>多实例滚动发布</b>时，
 * 新实例一起来就会把 Redis 库存重置回 DB 值 —— 正在进行的活动里已经扣掉的库存被「复活」，
 * 直接导致超卖。</p>
 *
 * <p>改为 {@code setIfAbsent} 后：</p>
 * <ul>
 *   <li>Redis 中已有该活动库存（活动进行中）→ 跳过，不覆盖进行中的扣减；</li>
 *   <li>key 不存在且活动尚未开始 → 写入，完成对齐；</li>
 *   <li>异步削峰模式下已发生过预扣（一人一单集合存在）但 key 缺失 → <b>拒绝按 DB 重建</b>，
 *       因为 DB 库存滞后于队列中的预扣，重建会把已预扣的库存重新放出来（fail-closed + 告警）；
 *       从未发生过预扣（如全新 Redis）则照常写入，否则新活动会永远无法抢购。</li>
 * </ul>
 *
 * <h3>需要「强制重新对齐」时怎么办</h3>
 * <p>新一场秒杀开始前若要重置库存，<b>必须显式 {@code SET}</b>（压测脚本
 * {@code loadtest/scripts/reset_env.sh} 就是这么做的），而不是依赖重启应用。
 * 正式玩法是把它做成由 ShedLock 保护的定时预热任务，只在该场次开始前执行一次。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillStockWarmUp implements ApplicationRunner {

    private final SeckillItemMapper seckillItemMapper;
    private final StringRedisTemplate redisTemplate;
    private final SeckillBucketManager bucketManager;
    private final MeterRegistry meterRegistry;

    /** 异步削峰模式下 DB 库存滞后于队列预扣，活动进行中禁止按 DB 重建 key。 */
    @Value("${mall.seckill.async.enabled:false}")
    private boolean asyncEnabled;

    @Override
    public void run(ApplicationArguments args) {
        List<SeckillItem> items = seckillItemMapper.selectList(new LambdaQueryWrapper<>());
        if (items.isEmpty()) {
            return;
        }
        int seeded = 0;
        int bucketed = 0;
        int skipped = 0;
        for (SeckillItem item : items) {
            boolean preStart = item.getNotStart() != null && item.getNotStart() == 1;
            // 异步模式下 DB 滞后于队列中的预扣，只有「曾发生过预扣」才禁止按 DB 重建；
            // 从未预热过（如全新 Redis）必须允许重建，否则该活动永远无法抢购
            boolean allowMissing = !asyncEnabled
                    || preStart
                    || !Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.SECKILL_BOUGHT + item.getId()));
            if (bucketManager.isBucketed(item)) {
                // 分桶商品：逐桶 setIfAbsent 预热；桶行缺失时会按 item.stock 均分补建
                bucketManager.warmUp(item, allowMissing);
                bucketed++;
                continue;
            }
            String key = RedisKeys.SECKILL_STOCK + item.getId();
            if (!allowMissing && !Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                skipped++;
                meterRegistry.counter("mall_seckill_stock_missing_total").increment();
                log.error("[秒杀预热] 活动已开始且 Redis 库存缺失，拒绝按 DB 重建：itemId={}", item.getId());
                continue;
            }
            String stock = String.valueOf(item.getStock() == null ? 0 : item.getStock());
            if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, stock))) {
                seeded++;
            }
        }
        log.info(
                "秒杀库存预热完成：共 {} 个商品（其中分桶 {} 个），单键本次写入 {} 个，" + "跳过缺失 {} 个（异步模式下活动进行中不允许按 DB 重建）",
                items.size(),
                bucketed,
                seeded,
                skipped);
    }
}
