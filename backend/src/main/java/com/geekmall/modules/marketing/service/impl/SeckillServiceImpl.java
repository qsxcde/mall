package com.geekmall.modules.marketing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.constant.RedisKeys;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.marketing.entity.SeckillItem;
import com.geekmall.modules.marketing.entity.SeckillSession;
import com.geekmall.modules.marketing.mapper.SeckillItemMapper;
import com.geekmall.modules.marketing.mapper.SeckillSessionMapper;
import com.geekmall.modules.marketing.service.SeckillService;
import com.geekmall.modules.marketing.vo.SeckillItemVO;
import com.geekmall.modules.marketing.vo.SeckillSessionVO;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.trade.service.TradeService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 秒杀服务实现。
 *
 * <p>按方案 6.4 的 L2 档实现：<b>不引入 MQ</b>，用 Redis Lua 原子预扣把绝大多数请求拦在内存层，
 * 只有预扣成功的少量请求才落库并建单。</p>
 *
 * <pre>
 * 返回值约定： 1 抢购成功 / -1 库存未预热 / -2 已抢光 / -3 重复抢购
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements SeckillService {

    /**
     * 原子预扣脚本：判库存 → 判一人一单 → 扣减 → 续期。
     *
     * <p>三步必须在同一个 Lua 脚本内完成；P2-6 在 {@code SADD} 之后<b>立即</b> {@code EXPIRE}，
     * 避免「先成功再设 TTL」的写法在异常分支漏设导致 bought 集合永久泄漏。</p>
     */
    private static final RedisScript<Long> GRAB_SCRIPT = RedisScript.of("""
            local stock = redis.call('GET', KEYS[1])
            if not stock then
                return -1
            end
            if tonumber(stock) <= 0 then
                return -2
            end
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
                return -3
            end
            redis.call('DECR', KEYS[1])
            redis.call('SADD', KEYS[2], ARGV[1])
            redis.call('EXPIRE', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    /** 一人一单标记的保留时长，按「每日一场」的运营节奏设置 */
    private static final Duration BOUGHT_TTL = Duration.ofHours(24);

    private final SeckillSessionMapper seckillSessionMapper;
    private final SeckillItemMapper seckillItemMapper;
    private final ProductMapper productMapper;
    private final TradeService tradeService;
    private final StringRedisTemplate redisTemplate;
    /** 自引用代理：让 grabInTx 真正开启独立事务，Redis 预扣才不会被包进事务 */
    private final ObjectProvider<SeckillServiceImpl> selfProvider;

    /**
     * 秒杀商品元数据本地缓存（3s）。
     *
     * <p>抢购是超高 QPS 接口，而活动商品的「秒杀价 / 商品 ID / 是否开始」在活动期内不变。
     * 若每次都 {@code selectById}，热点请求会被数据库连接池卡住；用本地缓存挡掉这条 SQL
     * 后，热点路径只剩 Redis 操作（库存判定始终以 Redis + DB 扣减为准，不依赖本缓存）。</p>
     */
    private final Cache<Long, SeckillItem> itemCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(3))
            .build();

    @Override
    public List<SeckillSessionVO> sessions() {
        return seckillSessionMapper.selectList(new LambdaQueryWrapper<SeckillSession>()
                        .orderByAsc(SeckillSession::getSort)
                        .orderByAsc(SeckillSession::getId))
                .stream().map(this::toSessionVO).toList();
    }

    @Override
    public List<SeckillItemVO> items(Long sessionId) {
        List<SeckillItem> items = seckillItemMapper.selectList(new LambdaQueryWrapper<SeckillItem>()
                .eq(sessionId != null, SeckillItem::getSessionId, sessionId)
                .orderByAsc(SeckillItem::getId));
        if (items.isEmpty()) {
            return List.of();
        }
        Map<Long, Product> productMap = productMapper.selectBatchIds(
                        items.stream().map(SeckillItem::getProductId).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity(), (a, b) -> a));
        return items.stream().map(item -> toItemVO(item, productMap.get(item.getProductId()))).toList();
    }

    /**
     * 抢购（非事务）。
     *
     * <p>P2-6：Redis 预扣在本方法（事务外）完成，落库交给 {@link #grabInTx} 的独立事务。
     * 这样 DB 事务里不再夹带 Redis 往返，行锁持有时间显著缩短；失败时在事务<b>之外</b>回补预扣，
     * 避免「DB 已回滚但 Redis 在事务内回补也被回滚」的错位。</p>
     */
    @Override
    public String grab(Long userId, Long itemId, Long addressId) {
        SeckillItem item = loadItem(itemId);
        if (item == null) {
            throw new BizException(ResultCode.NOT_FOUND, "秒杀商品不存在");
        }
        if (item.getNotStart() != null && item.getNotStart() == 1) {
            throw new BizException(ResultCode.BIZ_ERROR, "本场秒杀尚未开始，请先预约提醒");
        }
        ensureStockLoaded(item);

        String stockKey = RedisKeys.SECKILL_STOCK + itemId;
        String boughtKey = RedisKeys.SECKILL_BOUGHT + itemId;
        Long code = redisTemplate.execute(GRAB_SCRIPT, List.of(stockKey, boughtKey),
                String.valueOf(userId), String.valueOf(BOUGHT_TTL.toSeconds()));
        int result = code == null ? -1 : code.intValue();
        switch (result) {
            case 1 -> {
                // 预扣成功，继续落库
            }
            case -2 -> throw new BizException(ResultCode.OUT_OF_STOCK, "手慢了，本场已抢光");
            case -3 -> throw new BizException(ResultCode.BIZ_ERROR, "每人限购 1 件，您已经抢到过啦");
            default -> throw new BizException(ResultCode.BIZ_ERROR, "秒杀尚未开始，请稍后再试");
        }

        try {
            String orderNo = selfProvider.getObject().grabInTx(userId, itemId, addressId);
            log.info("用户 {} 秒杀成功：itemId={}, 订单号={}", userId, itemId, orderNo);
            return orderNo;
        } catch (RuntimeException e) {
            // DB 事务已回滚：在事务之外回补 Redis 预扣，否则库存被永久占用
            releaseReservation(itemId, userId);
            throw e;
        }
    }

    /**
     * 秒杀落库事务：DB 层再扣一次活动库存 + 建单。
     *
     * <p>作为 Redis 预扣之后的第二道防线，即便 Redis 计数与 DB 不一致也不会超卖。</p>
     */
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public String grabInTx(Long userId, Long itemId, Long addressId) {
        SeckillItem item = loadItem(itemId);
        if (item == null) {
            throw new BizException(ResultCode.NOT_FOUND, "秒杀商品不存在");
        }
        if (seckillItemMapper.deductStock(itemId) == 0) {
            throw new BizException(ResultCode.OUT_OF_STOCK, "手慢了，本场已抢光");
        }
        return tradeService.createOrderWithFixedPrice(userId, addressId, item.getProductId(),
                item.getSeckillPrice(), 1, "限时秒杀成交");
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    /** 读取秒杀商品元数据，3s 本地缓存挡掉热点 SQL。 */
    private SeckillItem loadItem(Long itemId) {
        return itemCache.get(itemId, seckillItemMapper::selectById);
    }

    /**
     * 从 Redis 读取活动库存，缺失时用 DB 值初始化。
     * 正式玩法应在活动开始前由定时任务统一预热（见 SeckillStockWarmUp）。
     */
    private void ensureStockLoaded(SeckillItem item) {
        String stockKey = RedisKeys.SECKILL_STOCK + item.getId();
        Boolean absent = redisTemplate.opsForValue()
                .setIfAbsent(stockKey, String.valueOf(item.getStock() == null ? 0 : item.getStock()));
        if (Boolean.TRUE.equals(absent)) {
            log.info("懒加载秒杀库存：itemId={}, stock={}", item.getId(), item.getStock());
        }
    }

    private void releaseReservation(Long itemId, Long userId) {
        try {
            redisTemplate.opsForSet().remove(RedisKeys.SECKILL_BOUGHT + itemId, String.valueOf(userId));
            redisTemplate.opsForValue().increment(RedisKeys.SECKILL_STOCK + itemId);
            log.warn("秒杀预扣已回补：itemId={}, userId={}", itemId, userId);
        } catch (Exception ex) {
            log.error("秒杀预扣补偿失败，需人工核对 Redis 库存：itemId={}, userId={}", itemId, userId, ex);
        }
    }

    private SeckillSessionVO toSessionVO(SeckillSession session) {
        SeckillSessionVO vo = new SeckillSessionVO();
        vo.setId(session.getId());
        vo.setTime(session.getSessionTime());
        vo.setLabel(session.getLabel());
        vo.setState(session.getState());
        return vo;
    }

    private SeckillItemVO toItemVO(SeckillItem item, Product product) {
        SeckillItemVO vo = new SeckillItemVO();
        vo.setId(item.getId());
        vo.setSessionId(item.getSessionId());
        vo.setProductId(item.getProductId());
        vo.setSeckillPrice(item.getSeckillPrice());
        vo.setOldPrice(item.getOldPrice());
        vo.setStock(item.getStock());
        vo.setTotal(item.getTotal());
        vo.setSold(item.getSold());
        vo.setTip(item.getTip());
        vo.setNotStart(item.getNotStart() != null && item.getNotStart() == 1);
        vo.setSoldout(item.getStock() == null || item.getStock() <= 0);
        int total = item.getTotal() == null ? 0 : item.getTotal();
        int sold = item.getSold() == null ? 0 : item.getSold();
        vo.setPercent(total <= 0 ? 0 : Math.min(100, sold * 100 / total));
        if (product != null) {
            vo.setTitle(product.getTitle());
            vo.setCover(product.getCover());
            vo.setSpec(product.getSpec());
        }
        return vo;
    }
}
