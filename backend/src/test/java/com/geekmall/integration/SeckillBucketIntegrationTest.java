package com.geekmall.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.constant.RedisKeys;
import com.geekmall.common.exception.BizException;
import com.geekmall.modules.marketing.bucket.SeckillBucketManager;
import com.geekmall.modules.marketing.entity.SeckillItem;
import com.geekmall.modules.marketing.entity.SeckillSession;
import com.geekmall.modules.marketing.mapper.SeckillBucketMapper;
import com.geekmall.modules.marketing.mapper.SeckillItemMapper;
import com.geekmall.modules.marketing.mapper.SeckillSessionMapper;
import com.geekmall.modules.marketing.queue.SeckillGrabResult;
import com.geekmall.modules.marketing.service.SeckillService;
import com.geekmall.modules.marketing.vo.SeckillItemVO;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.modules.user.dto.AddressDTO;
import com.geekmall.modules.user.entity.SysUser;
import com.geekmall.modules.user.mapper.SysUserMapper;
import com.geekmall.modules.user.service.UserService;
import com.geekmall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 秒杀库存分桶集成测试。
 *
 * <p>要回答的是「分桶真的成立吗」，而不是「代码看起来对不对」：</p>
 * <ol>
 *   <li><b>守恒</b>：一次成交 = 恰好一个桶少 1，{@code SUM(桶)} 与订单数对得上；</li>
 *   <li><b>不假售罄</b>：桶容量不均时仍能卖完（随机起点 + 顺序探测的作用）；</li>
 *   <li><b>不超卖</b>：并发抢购恰好卖出全部桶余量；</li>
 *   <li><b>回补还回原桶</b>：落库失败时 Redis 各桶余量回到抢购前的分布；</li>
 *   <li><b>异步路径带 bucketNo</b>：跨队列后仍能落到预扣选中的那一桶。</li>
 * </ol>
 *
 * <p>注意 {@code mkt_seckill_item.stock}/{@code sold} 在分桶商品上<b>不再逐单更新</b>
 * （否则又退回单行热点），因此断言一律以 {@code SUM(mkt_seckill_bucket.stock)} 为准。</p>
 */
@SpringBootTest(properties = {
        "mall.seckill.async.enabled=true",
        "mall.seckill.async.consumer-threads=2",
        "mall.seckill.async.block=200ms",
        "mall.seckill.async.reclaim-min-idle=1s",
        "mall.seckill.async.reclaim-interval=1s",
        "mall.seckill.async.result-ttl=10m"
})
@DisplayName("秒杀库存分桶集成测试")
class SeckillBucketIntegrationTest extends AbstractIntegrationTest {

    private static final long RESULT_WAIT_MS = 20_000L;

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private SeckillItemMapper seckillItemMapper;
    @Autowired
    private SeckillBucketMapper seckillBucketMapper;
    @Autowired
    private SeckillBucketManager bucketManager;
    @Autowired
    private SeckillSessionMapper seckillSessionMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private UserService userService;

    private record Buyer(long userId, long addressId) {
    }

    /* ------------------------------ 数据准备 ------------------------------ */

    private long createSession() {
        SeckillSession session = new SeckillSession();
        session.setSessionTime("22:00");
        session.setLabel("分桶测试场次");
        session.setState("running");
        session.setSort(97);
        seckillSessionMapper.insert(session);
        return session.getId();
    }

    private Product createProduct(String title, int stock) {
        Product product = new Product();
        product.setShopId(DEMO_SHOP_ID);
        product.setTitle(title);
        product.setCategoryId(1L);
        product.setCategoryKey("integration-seckill-bucket");
        product.setParentKey("integration");
        product.setCover("https://img/seckill/" + title + ".png");
        product.setSpec("分桶版");
        product.setPrice(new BigDecimal("999.00"));
        product.setCost(new BigDecimal("100.00"));
        product.setStock(stock);
        product.setSafeStock(1);
        product.setSales(0);
        product.setViews(0);
        product.setRating(new BigDecimal("5.0"));
        product.setStatus(1);
        product.setMerchantStatus("on");
        product.setCategoryName("分桶测试分类");
        product.setBrandName("极客严选");
        product.setIsHot(1);
        product.setIsNew(0);
        productMapper.insert(product);
        return product;
    }

    /** 构造一个<b>分桶</b>活动商品（bucketCount &gt; 1）。 */
    private SeckillItem createBucketedItem(long sessionId, long productId, int activityStock, int bucketCount) {
        SeckillItem item = new SeckillItem();
        item.setSessionId(sessionId);
        item.setProductId(productId);
        item.setSeckillPrice(new BigDecimal("199.00"));
        item.setOldPrice(new BigDecimal("999.00"));
        item.setStock(activityStock);
        item.setTotal(activityStock);
        item.setSold(0);
        item.setBucketCount(bucketCount);
        item.setTip("分桶集成测试");
        item.setNotStart(0);
        seckillItemMapper.insert(item);
        // 生产链路由 SeckillStockWarmUp 完成建桶 + 预热；测试里显式触发同样的入口
        bucketManager.warmUp(item);
        return item;
    }

    private Buyer createBuyer(String phone) {
        SysUser user = new SysUser();
        user.setUsername(phone);
        user.setPhone(phone);
        user.setPassword("$2a$10$integrationtestplaceholderintegrationtestplaceholder");
        user.setNickname("分桶买家");
        user.setStatus(1);
        user.setLevelId(1L);
        user.setPoints(0);
        user.setGrowth(0);
        sysUserMapper.insert(user);

        AddressDTO address = new AddressDTO();
        address.setName("分桶收货人");
        address.setPhone("13800000004");
        address.setProvince("广东省");
        address.setCity("深圳市");
        address.setDistrict("南山区");
        address.setDetail("分桶大道 1 号");
        address.setIsDefault(true);
        return new Buyer(user.getId(), userService.addAddress(user.getId(), address));
    }

    /** 各桶当前余量（Redis 视角），用于校验回补是否还回原桶。 */
    private Map<Integer, String> redisBuckets(Long itemId, int bucketCount) {
        Map<Integer, String> values = new LinkedHashMap<>();
        for (int i = 0; i < bucketCount; i++) {
            values.put(i, stringRedisTemplate.opsForValue().get(RedisKeys.seckillBucketStock(itemId, i)));
        }
        return values;
    }

    private Order reloadOrder(String orderNo) {
        return orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo)
                .last("limit 1"));
    }

    /* ------------------------------ 用例 ------------------------------ */

    @Test
    @DisplayName("分桶抢购：只扣中选中的那一桶，SUM(桶) 才是权威余量")
    void shouldDeductExactlyOneBucket() {
        long sessionId = createSession();
        Product product = createProduct("分桶商品A", 10);
        SeckillItem item = createBucketedItem(sessionId, product.getId(), 10, 4);
        Buyer buyer = createBuyer(randomPhone());

        String orderNo = seckillService.grab(buyer.userId(), item.getId(), buyer.addressId());

        assertThat(orderNo).startsWith("GM");
        assertThat(reloadOrder(orderNo)).isNotNull();
        // 桶口径：恰好少 1
        assertThat(seckillBucketMapper.sumStockByItem(item.getId())).isEqualTo(9);
        // 单行快照<b>故意</b>不变：分桶商品不再逐单更新它，否则又退回单行热点
        assertThat(seckillItemMapper.selectById(item.getId()).getStock()).isEqualTo(10);
        // 商品库存由交易域扣减，与活动库存是两套账
        assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(9);
    }

    @Test
    @DisplayName("桶容量不均时也能卖完：不会出现「明明有货却报售罄」的假售罄")
    void shouldNotFakeSellOutWithUnevenBuckets() {
        long sessionId = createSession();
        Product product = createProduct("分桶商品B", 100);
        // 5 件 / 3 桶 → 桶余量 2/2/1，分布天然不均，正是假售罄的高发形态
        SeckillItem item = createBucketedItem(sessionId, product.getId(), 5, 3);

        List<Buyer> buyers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            buyers.add(createBuyer(randomPhone()));
        }
        List<String> orderNos = new ArrayList<>();
        for (Buyer buyer : buyers) {
            orderNos.add(seckillService.grab(buyer.userId(), item.getId(), buyer.addressId()));
        }

        assertThat(orderNos)
                .as("5 件库存必须被 5 个用户全部买走；少卖一件就是假售罄")
                .hasSize(5);
        assertThat(seckillBucketMapper.sumStockByItem(item.getId()))
                .as("桶余量必须恰好归零")
                .isZero();
    }

    @Test
    @DisplayName("并发抢购：卖出量恰好等于桶余量之和，不超卖也不少卖")
    void concurrentGrabShouldNotOversell() throws InterruptedException {
        int activityStock = 8;
        int bucketCount = 4;
        int buyers = 16;

        long sessionId = createSession();
        Product product = createProduct("分桶并发商品", 100);
        SeckillItem item = createBucketedItem(sessionId, product.getId(), activityStock, bucketCount);

        List<Buyer> users = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            users.add(createBuyer(randomPhone()));
        }

        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(buyers);
        List<String> succeeded = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failed = Collections.synchronizedList(new ArrayList<>());

        try {
            for (Buyer buyer : users) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        succeeded.add(seckillService.grab(buyer.userId(), item.getId(), buyer.addressId()));
                    } catch (Throwable t) {
                        failed.add(t);
                    } finally {
                        finishGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(finishGate.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded)
                .as("成功人数必须恰好等于发放库存，既不能超卖也不能少卖")
                .hasSize(activityStock);
        assertThat(failed).hasSize(buyers - activityStock);
        assertThat(failed).allSatisfy(t -> assertThat(t).isInstanceOf(BizException.class));

        assertThat(seckillBucketMapper.sumStockByItem(item.getId())).isZero();
        assertThat(seckillBucketMapper.selectByItem(item.getId()))
                .as("桶结构不能被并发写坏")
                .hasSize(bucketCount);
    }

    @Test
    @DisplayName("落库失败回补：+1 还回原来那一桶，各桶余量回到抢购前的分布")
    void shouldReleaseToOriginalBucket() {
        long sessionId = createSession();
        Product product = createProduct("分桶商品C", 10);
        int bucketCount = 4;
        SeckillItem item = createBucketedItem(sessionId, product.getId(), 10, bucketCount);
        Buyer buyer = createBuyer(randomPhone());

        Map<Integer, String> before = redisBuckets(item.getId(), bucketCount);

        // 用不存在的收货地址让落库事务失败：预扣已成功 → 必须回补
        assertThatThrownBy(() -> seckillService.grab(buyer.userId(), item.getId(), -1L))
                .isInstanceOf(BizException.class);

        assertThat(redisBuckets(item.getId(), bucketCount))
                .as("回补必须还回原桶：任选一桶 +1，整体分布应恢复原样")
                .isEqualTo(before);
        assertThat(seckillBucketMapper.sumStockByItem(item.getId()))
                .as("DB 桶余量本来就没被扣（事务已回滚），应保持 10")
                .isEqualTo(10);

        // 回补同时也应清掉一人一单标记，否则用户被永久判定为「已抢过」
        assertThat(seckillService.grab(buyer.userId(), item.getId(), buyer.addressId()))
                .startsWith("GM");
        assertThat(seckillBucketMapper.sumStockByItem(item.getId())).isEqualTo(9);
    }

    @Test
    @DisplayName("异步落库：bucketNo 跨队列传递，最终扣的仍是预扣选中的那一桶")
    void shouldCarryBucketNoThroughQueue() throws Exception {
        long sessionId = createSession();
        Product product = createProduct("分桶商品D", 10);
        int bucketCount = 5;
        SeckillItem item = createBucketedItem(sessionId, product.getId(), 10, bucketCount);
        Buyer buyer = createBuyer(randomPhone());

        String requestId = seckillService.grabAsync(buyer.userId(), item.getId(), buyer.addressId());

        SeckillGrabResult result = awaitResult(buyer.userId(), requestId);
        assertThat(result.status()).isEqualTo(SeckillGrabResult.Status.SUCCESS);
        assertThat(result.orderNo()).startsWith("GM");

        assertThat(seckillBucketMapper.sumStockByItem(item.getId()))
                .as("异步落库也必须恰好扣 1，且扣在预扣选中的那一桶")
                .isEqualTo(9);
        assertThat(seckillBucketMapper.selectByItem(item.getId())).hasSize(bucketCount);
    }

    @Test
    @DisplayName("列表展示：分桶商品的余量/已售按 SUM(桶) 计算，不读失效的单行快照")
    void shouldReportBucketStockInItemList() {
        long sessionId = createSession();
        Product product = createProduct("分桶商品E", 10);
        SeckillItem item = createBucketedItem(sessionId, product.getId(), 10, 2);
        Buyer buyer = createBuyer(randomPhone());

        seckillService.grab(buyer.userId(), item.getId(), buyer.addressId());

        SeckillItemVO vo = seckillService.items(sessionId).stream()
                .filter(candidate -> candidate.getId().equals(item.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(vo.getStock()).isEqualTo(9);
        assertThat(vo.getSold()).isEqualTo(1);
        assertThat(vo.getTotal()).isEqualTo(10);
        assertThat(vo.getSoldout()).isFalse();
    }

    private SeckillGrabResult awaitResult(long userId, String requestId) throws Exception {
        long deadline = System.currentTimeMillis() + RESULT_WAIT_MS;
        SeckillGrabResult latest = null;
        while (System.currentTimeMillis() < deadline) {
            latest = seckillService.grabResult(userId, requestId);
            if (latest.isFinished()) {
                return latest;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待抢购结果超时，最后状态=" + latest);
    }
}
