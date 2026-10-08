package com.geekmall.modules.marketing.init;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.constant.RedisKeys;
import com.geekmall.modules.marketing.entity.SeckillItem;
import com.geekmall.modules.marketing.mapper.SeckillItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

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
 *   <li>key 不存在（首次部署 / Redis 被清空）→ 写入，完成对齐。</li>
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

    @Override
    public void run(ApplicationArguments args) {
        List<SeckillItem> items = seckillItemMapper.selectList(new LambdaQueryWrapper<>());
        if (items.isEmpty()) {
            return;
        }
        int seeded = 0;
        for (SeckillItem item : items) {
            String key = RedisKeys.SECKILL_STOCK + item.getId();
            String stock = String.valueOf(item.getStock() == null ? 0 : item.getStock());
            if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, stock))) {
                seeded++;
            }
        }
        log.info("秒杀库存预热完成：共 {} 个商品，本次写入 {} 个（已存在的不覆盖，避免滚动发布把进行中的扣减复位）",
                items.size(), seeded);
    }
}
