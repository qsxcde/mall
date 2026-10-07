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
 * 秒杀库存预热：启动时以 DB 库存为准覆盖 Redis 计数。
 *
 * <p>为什么需要它：Redis 中的库存是扣减后的值，应用重启后若 Redis 仍保留旧值，
 * 会出现「DB 有库存但 Redis 显示 0」的不一致。启动时以 DB 为准同步一次即可对齐。</p>
 *
 * <p>正式玩法：由定时任务在每场秒杀开始前预热，避免带着上一场的扣减值开抢。</p>
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
        items.forEach(item -> redisTemplate.opsForValue()
                .set(RedisKeys.SECKILL_STOCK + item.getId(), String.valueOf(item.getStock() == null ? 0 : item.getStock())));
        log.info("秒杀库存预热完成，共 {} 个商品", items.size());
    }
}
