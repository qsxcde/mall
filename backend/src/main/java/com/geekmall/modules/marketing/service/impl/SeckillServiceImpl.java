package com.geekmall.modules.marketing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.constant.RedisKeys;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.log.TraceContext;
import com.geekmall.common.resilience.ResilienceGuard;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.marketing.bucket.SeckillBucketManager;
import com.geekmall.modules.marketing.entity.SeckillItem;
import com.geekmall.modules.marketing.entity.SeckillSession;
import com.geekmall.modules.marketing.mapper.SeckillBucketMapper;
import com.geekmall.modules.marketing.mapper.SeckillItemMapper;
import com.geekmall.modules.marketing.mapper.SeckillSessionMapper;
import com.geekmall.modules.marketing.queue.SeckillGrabResult;
import com.geekmall.modules.marketing.queue.SeckillOrderMessage;
import com.geekmall.modules.marketing.queue.SeckillOrderQueue;
import com.geekmall.modules.marketing.queue.SeckillResultStore;
import com.geekmall.modules.marketing.service.SeckillService;
import com.geekmall.modules.marketing.vo.SeckillItemVO;
import com.geekmall.modules.marketing.vo.SeckillSessionVO;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.trade.service.TradeService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 秒杀服务实现。
 *
 * <p>按方案 6.4 的 L2 档实现：<b>不引入 MQ</b>，用 Redis Lua 原子预扣把绝大多数请求拦在内存层，
 * 只有预扣成功的少量请求才落库并建单。</p>
 *
 * <h3>库存分桶（P2-5）</h3>
 * <p>单热点 SKU 下所有订单都争抢同一行 {@code mkt_seckill_item}，落库并行度恒为 1
 * （见 {@code docs/benchmark/库存分桶实现分析.md} §6.3）。当商品的
 * {@code mkt_seckill_item.bucket_count > 1} 时改走分桶路径：</p>
 * <ol>
 *   <li>库存被均分到 N 个桶行，Redis 对应 N 个桶 key；</li>
 *   <li>Lua 在<b>一次原子调用</b>内「挑余量 &gt; 0 的桶 + DECR」，并返回选中的桶号
 *       （随机起点 + 顺序探测，避免固定路由造成的<b>假售罄</b>）；</li>
 *   <li>DB 只对选中那一行做 CAS 兜底；回补与异步消息都携带 {@code bucketNo}，
 *       保证 +1 还回<b>原来那一桶</b>。</li>
 * </ol>
 * <p>{@code bucket_count = 1} 的商品完全沿用原单行路径，行为零变化。</p>
 *
 * <pre>
 * 单键路径返回值： 1 抢购成功 / -1 库存未预热 / -2 已抢光 / -3 重复抢购
 * 分桶路径返回值： {1, idx} 选中第 idx 个桶 / {-1,0} 未预热 / {-2,0} 已抢光 / {-3,0} 重复
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

    /**
     * 分桶原子预扣脚本：判一人一单 → 随机起点顺序探测「有余量的桶」→ 扣减 → 返回选中桶号。
     *
     * <pre>
     * KEYS[1..N] 各桶库存；KEYS[N+1] 一人一单集合
     * ARGV[1]=userId  ARGV[2]=一人一单 TTL  ARGV[3]=随机起点
     * 返回 {1, idx} 选中第 idx 个桶（1 基） / {-1,0} 未预热 / {-2,0} 全桶售罄 / {-3,0} 重复
     * </pre>
     *
     * <h3>为什么必须一次原子调用内选桶</h3>
     * <p>选桶放在 Lua 里是「O(N) 内存循环」，不产生 N 次 DB 往返，也<b>永远不会撞空桶</b>
     * （按「余量 &gt; 0」挑），因此不需要「试着扣、失败再换一行」的重试。</p>
     *
     * <h3>为什么用随机起点而不是 {@code userId % N}</h3>
     * <p>哈希分布天然波动：桶容量固定时，负载偏高的桶会先空、偏低的桶会剩货，
     * 于是「明明有货却买到假售罄」（文档实测固定路由 183/200）。
     * 随机起点 + 顺序探测是「看着余量挑」，挑到的一定有货，因此<b>不会假售罄</b>。</p>
     *
     * <h3>为什么先判一人一单再判库存</h3>
     * <p>一人一单集合必须<b>每活动一个全局 key</b>（不按桶拆），否则用户可借不同桶重复抢；
     * 顺序上把它放前面，重复请求直接短路，省掉一次全桶扫描。</p>
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> GRAB_BUCKET_SCRIPT = RedisScript.of("""
            local n = #KEYS - 1
            for i = 1, n do
                if redis.call('EXISTS', KEYS[i]) == 0 then
                    return {-1, 0}
                end
            end
            local bought = KEYS[n + 1]
            if redis.call('SISMEMBER', bought, ARGV[1]) == 1 then
                return {-3, 0}
            end
            local start = tonumber(ARGV[3]) % n + 1
            for i = 0, n - 1 do
                local idx = (start + i - 1) % n + 1
                local v = tonumber(redis.call('GET', KEYS[idx]) or '0')
                if v > 0 then
                    redis.call('DECR', KEYS[idx])
                    redis.call('SADD', bought, ARGV[1])
                    redis.call('EXPIRE', bought, ARGV[2])
                    return {1, idx}
                end
            end
            return {-2, 0}
            """, List.class);

    /** 一人一单标记的保留时长，按「每日一场」的运营节奏设置 */
    private static final Duration BOUGHT_TTL = Duration.ofHours(24);

    /** 队列在熔断体系中的资源名（对应 mall.resilience.resources.seckill-queue）。 */
    static final String QUEUE_RESOURCE = "seckill-queue";

    private final SeckillSessionMapper seckillSessionMapper;
    private final SeckillItemMapper seckillItemMapper;
    /** 分桶商品的落库 CAS 目标（未分桶商品仍走 seckillItemMapper）。 */
    private final SeckillBucketMapper seckillBucketMapper;
    /** 桶的建桶 / 预热 / 汇总。 */
    private final SeckillBucketManager bucketManager;
    private final ProductMapper productMapper;
    private final TradeService tradeService;
    private final StringRedisTemplate redisTemplate;
    /** 自引用代理：让 grabInTx 真正开启独立事务，Redis 预扣才不会被包进事务 */
    private final ObjectProvider<SeckillServiceImpl> selfProvider;
    /** 削峰队列（异步模式使用）。 */
    private final SeckillOrderQueue seckillOrderQueue;
    /** 抢购结果存取（异步模式使用）。 */
    private final SeckillResultStore seckillResultStore;
    /** 队列是新增的下游依赖，用熔断 + 并发上限保护它的写入。 */
    private final ResilienceGuard resilienceGuard;
    /** 分桶场景下的「预扣 / 记账不一致」告警指标。 */
    private final MeterRegistry meterRegistry;

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
        // 分桶商品的权威余量是 SUM(桶)：mkt_seckill_item.stock 自启用分桶起不再逐单更新
        Map<Long, SeckillBucketManager.BucketAggregate> bucketMap = bucketManager.aggregate(
                items.stream().filter(bucketManager::isBucketed).map(SeckillItem::getId).toList());
        return items.stream()
                .map(item -> toItemVO(item, productMap.get(item.getProductId()), bucketMap.get(item.getId())))
                .toList();
    }

    /**
     * 同步抢购（默认模式，非事务）。
     *
     * <p>P2-6：Redis 预扣在本方法（事务外）完成，落库交给 {@link #grabInTx} 的独立事务。
     * 这样 DB 事务里不再夹带 Redis 往返，行锁持有时间显著缩短；失败时在事务<b>之外</b>回补预扣，
     * 避免「DB 已回滚但 Redis 在事务内回补也被回滚」的错位。</p>
     */
    @Override
    public String grab(Long userId, Long itemId, Long addressId) {
        // 分桶商品：预扣时已选定桶号，落库与回补都必须带着它，否则 +1 会还错桶
        Integer bucketNo = reserve(userId, itemId);
        try {
            String orderNo = selfProvider.getObject().grabInTx(userId, itemId, addressId, null, bucketNo);
            log.info("用户 {} 秒杀成功：itemId={}, 桶={}, 订单号={}", userId, itemId, bucketNo, orderNo);
            return orderNo;
        } catch (RuntimeException e) {
            // DB 事务已回滚：在事务之外回补 Redis 预扣，否则库存被永久占用
            releaseReservation(itemId, userId, bucketNo);
            throw e;
        }
    }

    /**
     * 异步抢购（削峰模式，由 {@code mall.seckill.async.enabled} 决定是否启用）。
     *
     * <p>与同步路径共享同一段「资格判定」（{@link #reserve}），区别只在拿到资格之后：
     * 同步路径当场落库，这里改为入队后立即返回。用户延迟因此从「DB 事务耗时」
     * 压缩到「预扣 + 入队耗时」。</p>
     *
     * <p><b>注意削峰不提升吞吐</b>：数据库的落库能力没有任何变化，这里只是把脉冲摊平、
     * 并把「行锁争抢」转化为「队列排队」。要真正提高吞吐得靠批量落库或库存分桶。</p>
     *
     * @return 抢购请求号，前端凭它轮询 {@link #grabResult}
     */
    @Override
    public String grabAsync(Long userId, Long itemId, Long addressId) {
        Integer bucketNo = reserve(userId, itemId);
        String requestId = UUID.randomUUID().toString().replace("-", "");
        // 先落「排队中」：用户入队后立刻能查到状态，不会出现「查不到结果」的空窗期
        seckillResultStore.save(SeckillGrabResult.queued(requestId, userId));
        try {
            // 队列是新增的下游依赖：用熔断 + 并发上限保护，避免队列卡住时把调用线程拖满
            resilienceGuard.execute(QUEUE_RESOURCE, () -> {
                // 顺带把当前请求的链路 ID 写入消息体：MDC 跨不过队列，消费端只能靠它还原上下文
                // bucketNo 也<b>必须</b>进消息体：选桶是「随机起点 + 顺序探测」，不可复现，
                // 消费端拿不到它就只能瞎猜该扣哪一桶
                seckillOrderQueue.enqueue(new SeckillOrderMessage(requestId, userId, itemId, addressId,
                        TraceContext.currentTraceId(), bucketNo));
                return requestId;
            });
        } catch (RuntimeException ex) {
            // 入队失败：资格已经预扣了，必须立刻回补，否则库存被「黑洞」吞掉
            releaseReservation(itemId, userId, bucketNo);
            seckillResultStore.save(SeckillGrabResult.failed(requestId, userId, "系统繁忙，请稍后再试"));
            log.error("秒杀请求入队失败，已回补预扣：userId={}, itemId={}, 桶={}", userId, itemId, bucketNo, ex);
            throw new BizException(ResultCode.SYSTEM_ERROR, "系统繁忙，请稍后再试");
        }
        log.info("用户 {} 秒杀请求已入队：itemId={}, 桶={}, requestId={}", userId, itemId, bucketNo, requestId);
        return requestId;
    }

    @Override
    public SeckillGrabResult grabResult(Long userId, String requestId) {
        return seckillResultStore.find(requestId)
                // 校验归属：越权查询一律按「不存在」返回，不泄露该 requestId 是否存在
                .filter(result -> Objects.equals(result.userId(), userId))
                .orElseThrow(() -> new BizException(ResultCode.NOT_FOUND, "抢购记录不存在或已过期"));
    }

    /**
     * 资格判定：Redis Lua 原子预扣（判库存 + 判一人一单 + 扣减 + 续期）。
     *
     * <p>同步与异步两条路径共用，保证「谁能买到」的口径只有一处定义。</p>
     *
     * @return 分桶商品返回<b>选中桶号</b>（落库与回补都要用）；不分桶商品返回 {@code null}
     */
    private Integer reserve(Long userId, Long itemId) {
        SeckillItem item = loadItem(itemId);
        if (item == null) {
            throw new BizException(ResultCode.NOT_FOUND, "秒杀商品不存在");
        }
        if (item.getNotStart() != null && item.getNotStart() == 1) {
            throw new BizException(ResultCode.BIZ_ERROR, "本场秒杀尚未开始，请先预约提醒");
        }
        return bucketManager.isBucketed(item)
                ? reserveFromBuckets(item, userId)
                : reserveFromSingleRow(item, userId);
    }

    /**
     * 不分桶路径：单 key 判库存 + 一人一单（原有链路，行为完全不变）。
     */
    private Integer reserveFromSingleRow(SeckillItem item, Long userId) {
        ensureStockLoaded(item);

        String stockKey = RedisKeys.SECKILL_STOCK + item.getId();
        String boughtKey = RedisKeys.SECKILL_BOUGHT + item.getId();
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
        return null;
    }

    /**
     * 分桶路径：一次 Lua 原子调用内「挑有货的桶 + 扣减 + 记一人一单」。
     *
     * <p>随机起点由应用传入（脚本内取随机数会破坏确定性），桶数 N 决定
     * {@code KEYS[1..N]}；{@code KEYS[N+1]} 仍是<b>全局</b>的一人一单集合。</p>
     *
     * <p>若脚本返回「未预热」（桶 key 缺失），先按 DB 桶余量补一次预热再重试一次：
     * 稳态下零额外开销，Redis 被清空时又能自愈。</p>
     */
    private Integer reserveFromBuckets(SeckillItem item, Long userId) {
        int count = SeckillBucketManager.bucketCount(item);
        List<String> keys = new ArrayList<>(count + 1);
        for (int i = 0; i < count; i++) {
            keys.add(RedisKeys.seckillBucketStock(item.getId(), i));
        }
        keys.add(RedisKeys.SECKILL_BOUGHT + item.getId());

        String[] args = {String.valueOf(userId), String.valueOf(BOUGHT_TTL.toSeconds()),
                String.valueOf(ThreadLocalRandom.current().nextInt(count))};
        BucketPick pick = pickBucket(redisTemplate.execute(GRAB_BUCKET_SCRIPT, keys, (Object[]) args));
        if (pick.code == -1) {
            bucketManager.warmUp(item);
            pick = pickBucket(redisTemplate.execute(GRAB_BUCKET_SCRIPT, keys, (Object[]) args));
        }
        switch (pick.code) {
            case 1 -> {
                return pick.bucketNo;
            }
            case -2 -> throw new BizException(ResultCode.OUT_OF_STOCK, "手慢了，本场已抢光");
            case -3 -> throw new BizException(ResultCode.BIZ_ERROR, "每人限购 1 件，您已经抢到过啦");
            default -> throw new BizException(ResultCode.SYSTEM_ERROR, "秒杀库存未就绪，请稍后再试");
        }
    }

    /** 解析分桶脚本返回的 {@code {code, idx}}（idx 为 1 基桶序号，转换为 0 基桶号）。 */
    private BucketPick pickBucket(List<?> result) {
        if (result == null || result.isEmpty() || !(result.get(0) instanceof Number code)) {
            return new BucketPick(-1, -1);
        }
        int idx = result.size() > 1 && result.get(1) instanceof Number num ? num.intValue() : 0;
        return new BucketPick(code.intValue(), idx - 1);
    }

    /** 分桶脚本的返回：{@code code} 见 {@link #GRAB_BUCKET_SCRIPT}，{@code bucketNo} 为 0 基桶号。 */
    private record BucketPick(int code, int bucketNo) {
    }

    /**
     * 秒杀落库事务：DB 层再扣一次活动库存 + 建单。
     *
     * <p>作为 Redis 预扣之后的第二道防线，即便 Redis 计数与 DB 不一致也不会超卖。</p>
     *
     * @param requestId 幂等键。异步落库时传入队列消息里的 requestId，重复消费会被
     *                  {@code uk_user_request} 唯一索引拦下并返回已有订单号；
     *                  同步路径传 {@code null}（由 Redis「一人一单」保证不重复）。
     * @param bucketNo  分桶商品在预扣阶段选中的桶号；不分桶商品传 {@code null}
     *                  （此时扣减对象仍是 {@code mkt_seckill_item} 单行）。
     */
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public String grabInTx(Long userId, Long itemId, Long addressId, String requestId, Integer bucketNo) {
        // 幂等前置检查必须在扣活动库存「之前」：唯一键冲突会在交易域内部被消化成「返回已有订单号」，
        // 事务因此照常提交 —— 若先扣库存再被拦下，活动库存就被多扣了一次，且不会有任何异常提示
        if (requestId != null) {
            String settled = tradeService.findOrderNoByRequestId(userId, requestId);
            if (settled != null) {
                log.info("[秒杀幂等] 该 requestId 已建单 {}，跳过扣减与建单：requestId={}", settled, requestId);
                return settled;
            }
        }
        SeckillItem item = loadItem(itemId);
        if (item == null) {
            throw new BizException(ResultCode.NOT_FOUND, "秒杀商品不存在");
        }
        if (deductStock(itemId, bucketNo) == 0) {
            throw new BizException(ResultCode.OUT_OF_STOCK, "手慢了，本场已抢光");
        }
        return tradeService.createOrderWithFixedPrice(userId, addressId, item.getProductId(),
                item.getSeckillPrice(), 1, "限时秒杀成交", requestId);
    }

    /**
     * 落库阶段的 CAS 扣减：分桶商品只扣<b>预扣时选中的那一桶</b>，不分桶商品扣单行。
     *
     * <p><b>桶空时不换桶</b>（文档 D.14）：预扣是按「Redis 余量 &gt; 0」挑的，落库却发现桶空，
     * 说明预扣与记账短暂不一致。此时若换一桶重试，就会变成「预扣扣了 A、DB 扣了 B」——
     * 两个桶的余量都不对，回补也找不回原桶。因此这里只回补预扣 + 业务失败 + 打指标。</p>
     */
    private int deductStock(Long itemId, Integer bucketNo) {
        if (bucketNo == null) {
            return seckillItemMapper.deductStock(itemId);
        }
        int affected = seckillBucketMapper.deductStock(itemId, bucketNo);
        if (affected == 0) {
            meterRegistry.counter("mall_seckill_bucket_mismatch_total").increment();
            log.error("[秒杀分桶] Redis 预扣成功但桶已空，判定为预扣/记账不一致（不换桶，仅回补）：itemId={}, bucketNo={}",
                    itemId, bucketNo);
        }
        return affected;
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

    public void releaseReservation(Long itemId, Long userId, Integer bucketNo) {
        try {
            redisTemplate.opsForSet().remove(RedisKeys.SECKILL_BOUGHT + itemId, String.valueOf(userId));
            // 分桶商品必须 +1 还回<b>原桶</b>：选桶是随机起点探测，不可复现，
            // 还到别的桶会让两个桶的余量同时失真，逐桶对账也就失去意义
            String stockKey = bucketNo == null
                    ? RedisKeys.SECKILL_STOCK + itemId
                    : RedisKeys.seckillBucketStock(itemId, bucketNo);
            redisTemplate.opsForValue().increment(stockKey);
            log.warn("秒杀预扣已回补：itemId={}, userId={}, 桶={}", itemId, userId, bucketNo);
        } catch (Exception ex) {
            log.error("秒杀预扣补偿失败，需人工核对 Redis 库存：itemId={}, userId={}, 桶={}",
                    itemId, userId, bucketNo, ex);
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

    /**
     * 组装商品 VO。
     *
     * <p>分桶商品传入 {@code aggregate}（{@code SUM(桶)}）：{@code mkt_seckill_item.stock}
     * 自启用分桶起不再逐单更新，只有桶口径才是真实余量与销量。</p>
     */
    private SeckillItemVO toItemVO(SeckillItem item, Product product,
                                   SeckillBucketManager.BucketAggregate aggregate) {
        int stock = aggregate != null
                ? (int) aggregate.stock()
                : (item.getStock() == null ? 0 : item.getStock());
        int total = aggregate != null
                ? (int) aggregate.total()
                : (item.getTotal() == null ? 0 : item.getTotal());
        int sold = aggregate != null
                ? (int) aggregate.sold()
                : (item.getSold() == null ? 0 : item.getSold());

        SeckillItemVO vo = new SeckillItemVO();
        vo.setId(item.getId());
        vo.setSessionId(item.getSessionId());
        vo.setProductId(item.getProductId());
        vo.setSeckillPrice(item.getSeckillPrice());
        vo.setOldPrice(item.getOldPrice());
        vo.setStock(stock);
        vo.setTotal(total);
        vo.setSold(sold);
        vo.setTip(item.getTip());
        vo.setNotStart(item.getNotStart() != null && item.getNotStart() == 1);
        vo.setSoldout(stock <= 0);
        vo.setPercent(total <= 0 ? 0 : Math.min(100, sold * 100 / total));
        if (product != null) {
            vo.setTitle(product.getTitle());
            vo.setCover(product.getCover());
            vo.setSpec(product.getSpec());
        }
        return vo;
    }
}
