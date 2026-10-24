package com.geekmall.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.constant.SecurityConstants;
import com.geekmall.modules.inventory.entity.InvBucket;
import com.geekmall.modules.inventory.enums.BucketBizType;
import com.geekmall.modules.inventory.mapper.InvBucketLogMapper;
import com.geekmall.modules.inventory.mapper.InvBucketMapper;
import com.geekmall.modules.marketing.dto.BatchOrderOutcome;
import com.geekmall.modules.marketing.dto.BatchPersistResult;
import com.geekmall.modules.marketing.entity.SeckillBucket;
import com.geekmall.modules.marketing.entity.SeckillItem;
import com.geekmall.modules.marketing.entity.SeckillSession;
import com.geekmall.modules.marketing.mapper.SeckillBucketMapper;
import com.geekmall.modules.marketing.mapper.SeckillItemMapper;
import com.geekmall.modules.marketing.mapper.SeckillSessionMapper;
import com.geekmall.modules.marketing.queue.SeckillBatchOrderHandler;
import com.geekmall.modules.marketing.queue.SeckillGrabResult;
import com.geekmall.modules.marketing.queue.SeckillOrderMessage;
import com.geekmall.modules.marketing.queue.SeckillOrderQueue;
import com.geekmall.modules.marketing.queue.SeckillResultStore;
import com.geekmall.modules.marketing.service.SeckillBatchPersistService;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/**
 * 秒杀批量落库集成测试。
 *
 * <p>这些用例回答的是「批量落库改坏了会不会超卖 / 会不会重单 / 脏消息会不会拖垮整批」，
 * 而不是「代码看起来对不对」。核心断言：</p>
 * <ol>
 *   <li><b>不超卖</b>：并发抢购下恰好库存数成单，一人一单；</li>
 *   <li><b>不重单</b>：同一 {@code requestId} 重复投递只建一单、库存只扣一次；</li>
 *   <li><b>脏消息隔离</b>：一条商品已下架的消息不会让同批的正常请求退回逐条重试；</li>
 *   <li><b>守恒</b>：秒杀桶与商品分桶两侧的合计与权威值对得上。</li>
 * </ol>
 */
@SpringBootTest(
        properties = {
            "mall.seckill.async.enabled=true",
            "mall.seckill.async.batch-enabled=true",
            "mall.seckill.async.consumer-threads=2",
            "mall.seckill.async.batch-size=16",
            "mall.seckill.async.block=200ms",
            "mall.seckill.async.reclaim-min-idle=1s",
            "mall.seckill.async.reclaim-interval=1s",
            "mall.seckill.async.result-ttl=10m"
        })
@DisplayName("秒杀批量落库集成测试")
class SeckillBatchPersistIntegrationTest extends AbstractIntegrationTest {

    private static final long RESULT_WAIT_MS = 20_000L;

    @Autowired
    private SeckillSessionMapper seckillSessionMapper;

    @Autowired
    private SeckillItemMapper seckillItemMapper;

    @Autowired
    private SeckillBucketMapper seckillBucketMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private InvBucketMapper invBucketMapper;

    @Autowired
    private InvBucketLogMapper invBucketLogMapper;

    @Autowired
    private SeckillBatchPersistService batchPersistService;

    @Autowired
    private SeckillBatchOrderHandler batchOrderHandler;

    @Autowired
    private SeckillResultStore resultStore;

    @Autowired
    private MeterRegistry meterRegistry;

    /* ------------------------------ 数据准备 ------------------------------ */

    private long createSession() {
        SeckillSession session = new SeckillSession();
        session.setSessionTime("21:00");
        session.setLabel("批量落库测试场次");
        session.setState("running");
        session.setSort(97);
        seckillSessionMapper.insert(session);
        return session.getId();
    }

    private Product createProduct(String title, int stock, String price) {
        Product product = new Product();
        product.setShopId(DEMO_SHOP_ID);
        product.setTitle(title);
        product.setCategoryId(1L);
        product.setCategoryKey("integration-seckill-batch");
        product.setParentKey("integration");
        product.setCover("https://img/seckill/" + title + ".png");
        product.setSpec("批量版");
        product.setPrice(new BigDecimal(price));
        product.setCost(new BigDecimal("100.00"));
        product.setStock(stock);
        product.setSafeStock(1);
        product.setSales(0);
        product.setViews(0);
        product.setRating(new BigDecimal("5.0"));
        product.setStatus(1);
        product.setMerchantStatus("on");
        product.setCategoryName("批量测试分类");
        product.setBrandName("极客严选");
        product.setIsHot(1);
        product.setIsNew(0);
        productMapper.insert(product);
        return product;
    }

    private SeckillItem createSeckillItem(
            long sessionId, long productId, int activityStock, String price, int bucketCount) {
        SeckillItem item = new SeckillItem();
        item.setSessionId(sessionId);
        item.setProductId(productId);
        item.setSeckillPrice(new BigDecimal(price));
        item.setOldPrice(new BigDecimal("999.00"));
        item.setStock(activityStock);
        item.setTotal(activityStock);
        item.setSold(0);
        item.setTip("批量落库集成测试");
        item.setNotStart(0);
        item.setBucketCount(bucketCount);
        seckillItemMapper.insert(item);
        return item;
    }

    private long createAddress(String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "批量收货人");
        body.put("phone", "13800000004");
        body.put("province", "广东省");
        body.put("city", "深圳市");
        body.put("district", "福田区");
        body.put("detail", "批量大道 2 号");
        body.put("isDefault", true);
        return assertSuccess(post("/api/v1/user/addresses", body, token)).asLong();
    }

    /**
     * 发起抢购并返回业务响应。
     *
     * <p>抢购接口返回 {@code DeferredResult}，MockMvc 必须在异步开始后显式
     * {@code asyncDispatch} 才能拿到响应体。</p>
     */
    private JsonNode grabOrder(long itemId, long addressId, String token) throws Exception {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.post("/api/v1/seckill/" + itemId + "/order")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("addressId", addressId)));
        builder.header(SecurityConstants.TOKEN_HEADER, SecurityConstants.TOKEN_PREFIX + token);

        MvcResult result = mockMvc.perform(builder).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isOk())
                    .andReturn();
        }
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private SeckillGrabResult awaitResult(String token, String requestId) throws Exception {
        long deadline = System.currentTimeMillis() + RESULT_WAIT_MS;
        SeckillGrabResult latest = null;
        while (System.currentTimeMillis() < deadline) {
            JsonNode data = assertSuccess(get("/api/v1/seckill/result/" + requestId, token));
            latest = objectMapper.treeToValue(data, SeckillGrabResult.class);
            if (latest.isFinished()) {
                return latest;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待抢购结果超时，最后状态=" + latest);
    }

    private String randomRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private long userIdOf(String token) {
        return Long.parseLong(
                assertSuccess(get("/api/v1/user/profile", token)).get("id").asText());
    }

    /* ------------------------------ 用例 ------------------------------ */

    @Test
    @DisplayName("批量落库：并发抢购不超卖，恰好库存数成单，且秒杀桶守恒")
    void shouldNotOversellUnderConcurrentGrab() throws Exception {
        long sessionId = createSession();
        Product product = createProduct("批量商品A", 10, "999.00");
        // 分桶商品：活动库存 3 拆 2 个桶，走 mkt_seckill_bucket 路径
        SeckillItem item = createSeckillItem(sessionId, product.getId(), 3, "199.00", 2);

        int users = 6;
        List<String> tokens = new ArrayList<>();
        List<Long> addresses = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            String token = registerAndLogin(randomPhone());
            tokens.add(token);
            addresses.add(createAddress(token));
        }

        // 六个用户同时抢：Redis Lua 预扣拦住多数，剩下进队列由消费端批量落库
        ExecutorService pool = Executors.newFixedThreadPool(users);
        CountDownLatch ready = new CountDownLatch(users);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<JsonNode>> futures = new ArrayList<>();
        int succeeded = 0;
        try {
            for (int i = 0; i < users; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    return grabOrder(item.getId(), addresses.get(idx), tokens.get(idx));
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            List<String> requestIds = new ArrayList<>();
            List<String> requestTokens = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                JsonNode response = futures.get(i).get(30, TimeUnit.SECONDS);
                if (response.get("code").asInt() == 0) {
                    requestIds.add(response.get("data").asText());
                    requestTokens.add(tokens.get(i));
                }
            }

            for (int i = 0; i < requestIds.size(); i++) {
                // 结果接口校验归属，必须用发起该请求的用户令牌查
                SeckillGrabResult result = awaitResult(requestTokens.get(i), requestIds.get(i));
                if (result.status() == SeckillGrabResult.Status.SUCCESS) {
                    succeeded++;
                }
            }
            assertThat(succeeded).as("活动库存 3，六个并发抢购只应成 3 单 —— 不能超卖").isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }

        // 一人一单：每个账号都是全新注册的，秒杀订单最多一单
        for (String token : tokens) {
            long count = orderMapper.selectCount(new LambdaQueryWrapper<Order>().eq(Order::getUserId, userIdOf(token)));
            assertThat(count).as("一人一单，同一用户最多一单秒杀订单").isLessThanOrEqualTo(1);
        }

        // 秒杀桶守恒：SUM(桶余量) == total - 成交单数
        List<SeckillBucket> buckets = seckillBucketMapper.selectByItem(item.getId());
        int sumStock = buckets.stream()
                .mapToInt(b -> b.getStock() == null ? 0 : b.getStock())
                .sum();
        int total = buckets.stream()
                .mapToInt(b -> b.getTotal() == null ? 0 : b.getTotal())
                .sum();
        assertThat(sumStock + succeeded)
                .as("秒杀桶守恒：SUM(桶余量) + 成交单数 == 初始总量（{}）", total)
                .isEqualTo(total);
    }

    @Test
    @DisplayName("消费端批量路由：多条消息一次事务落库，不退回逐条路径")
    void shouldBatchMultipleDeliveriesInOneTransaction() {
        long sessionId = createSession();
        Product product = createProduct("批量商品E", 20, "999.00");
        SeckillItem item = createSeckillItem(sessionId, product.getId(), 20, "199.00", 1);

        String token = registerAndLogin(randomPhone());
        long userId = userIdOf(token);
        long addressId = createAddress(token);

        List<SeckillOrderQueue.Delivery> deliveries = new ArrayList<>();
        List<String> requestIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String requestId = randomRequestId();
            requestIds.add(requestId);
            // handle 用 Redis Stream 的 record id 格式（ms-seq），ackAll 才能真正落到 XACK
            deliveries.add(new SeckillOrderQueue.Delivery(
                    "1761000000000-" + i,
                    new SeckillOrderMessage(requestId, userId, item.getId(), addressId, "trace-" + i, null),
                    1L));
        }

        double before = meterRegistry
                .counter("mall_seckill_batch_commit_total", "result", "success")
                .count();
        SeckillBatchOrderHandler.BatchHandleResult result = batchOrderHandler.handle(deliveries);

        assertThat(result.fallback()).as("四条消息应一次性批量落库，不该退回逐条").isEmpty();
        assertThat(result.deadLetters()).isEmpty();
        for (String requestId : requestIds) {
            assertThat(resultStore.find(requestId)).as("每条消息都要有终态，用户才能拿到结论").isPresent();
            assertThat(resultStore.find(requestId).get().status()).isEqualTo(SeckillGrabResult.Status.SUCCESS);
            assertThat(orderMapper.selectCount(new LambdaQueryWrapper<Order>().eq(Order::getRequestId, requestId)))
                    .as("requestId={} 应建且只建一单", requestId)
                    .isEqualTo(1);
        }
        assertThat(meterRegistry
                        .counter("mall_seckill_batch_commit_total", "result", "success")
                        .count())
                .as("批量落库计数应增加 —— 证明走的是批量路径")
                .isGreaterThan(before);

        // 4 单各 1 件，商品库存 20 → 16
        assertThat(productMapper.selectById(product.getId()).getStock())
                .as("四单各一件，商品库存应减 4")
                .isEqualTo(16);
    }

    @Test
    @DisplayName("脏消息隔离：一条商品已下架不影响同批其它订单落库")
    void shouldIsolatePoisonMessageFromBatch() {
        long sessionId = createSession();
        Product good = createProduct("批量商品B-正常", 20, "999.00");
        Product offShelf = createProduct("批量商品B-已下架", 20, "999.00");
        productMapper.deleteById(offShelf.getId());
        Product deleted = productMapper.selectById(offShelf.getId());
        assertThat(deleted).as("前置：商品应已逻辑删除").isNull();

        SeckillItem goodItem = createSeckillItem(sessionId, good.getId(), 20, "199.00", 1);
        SeckillItem badItem = createSeckillItem(sessionId, offShelf.getId(), 20, "199.00", 1);

        // 用真实用户：地址归属校验要过
        String token = registerAndLogin(randomPhone());
        long userId = userIdOf(token);
        long addressId = createAddress(token);

        String goodRequestId = randomRequestId();
        String badRequestId = randomRequestId();
        BatchPersistResult result = batchPersistService.persist(List.of(
                new SeckillOrderMessage(goodRequestId, userId, goodItem.getId(), addressId, "trace-good", null),
                new SeckillOrderMessage(badRequestId, userId, badItem.getId(), addressId, "trace-bad", null)));

        Map<String, BatchOrderOutcome> byRequest = new LinkedHashMap<>();
        result.outcomes().forEach(o -> byRequest.put(o.requestId(), o));

        assertThat(byRequest.get(goodRequestId).success())
                .as("正常商品应当落库成功 —— 不能被同批的脏消息拖垮")
                .isTrue();
        assertThat(byRequest.get(badRequestId).success())
                .as("商品已下架应判业务失败，而不是让整批回滚")
                .isFalse();
        assertThat(byRequest.get(badRequestId).message()).contains("下架");

        assertThat(orderMapper.selectCount(new LambdaQueryWrapper<Order>().eq(Order::getRequestId, goodRequestId)))
                .as("正常商品应建出订单")
                .isEqualTo(1);
        assertThat(orderMapper.selectCount(new LambdaQueryWrapper<Order>().eq(Order::getRequestId, badRequestId)))
                .as("已下架商品不应建出订单")
                .isZero();
    }

    @Test
    @DisplayName("重复投递：同一 requestId 批量落库两次只建一单、库存只扣一次")
    void shouldNotDuplicateOnRedeliveryInBatch() {
        long sessionId = createSession();
        Product product = createProduct("批量商品C", 20, "999.00");
        SeckillItem item = createSeckillItem(sessionId, product.getId(), 20, "199.00", 1);

        String token = registerAndLogin(randomPhone());
        long userId = userIdOf(token);
        long addressId = createAddress(token);
        String requestId = randomRequestId();
        SeckillOrderMessage message =
                new SeckillOrderMessage(requestId, userId, item.getId(), addressId, "trace-dup", null);

        BatchPersistResult first = batchPersistService.persist(List.of(message));
        assertThat(first.outcomes().get(0).success()).isTrue();
        String orderNo = first.outcomes().get(0).orderNo();

        int itemStockAfterFirst = seckillItemMapper.selectById(item.getId()).getStock();
        int productStockAfterFirst = productMapper.selectById(product.getId()).getStock();

        // 模拟崩溃后重投：结果没写成功，同一条消息再来一次
        BatchPersistResult second = batchPersistService.persist(List.of(message));
        assertThat(second.outcomes().get(0).success())
                .as("重投应幂等命中已有订单，返回同一个订单号")
                .isTrue();
        assertThat(second.outcomes().get(0).orderNo()).as("重投必须返回原订单号，而不是新建一单").isEqualTo(orderNo);

        assertThat(orderMapper.selectCount(new LambdaQueryWrapper<Order>().eq(Order::getRequestId, requestId)))
                .as("同一 requestId 只允许存在一单")
                .isEqualTo(1);
        assertThat(seckillItemMapper.selectById(item.getId()).getStock())
                .as("重投不得二次扣减活动库存")
                .isEqualTo(itemStockAfterFirst);
        assertThat(productMapper.selectById(product.getId()).getStock())
                .as("重投不得二次扣减商品库存")
                .isEqualTo(productStockAfterFirst);
    }

    @Test
    @DisplayName("商品分桶守恒：批量出库后 SUM(inv_bucket.stock) == pms_product.stock，且审计流水成对")
    void shouldKeepProductStockConsistentWithBuckets() {
        long sessionId = createSession();
        Product product = createProduct("批量商品D", 10, "999.00");
        SeckillItem item = createSeckillItem(sessionId, product.getId(), 10, "199.00", 1);

        // 造两个库存桶，合计与商品库存一致
        for (int i = 0; i < 2; i++) {
            InvBucket bucket = new InvBucket();
            bucket.setProductId(product.getId());
            bucket.setShopId(DEMO_SHOP_ID);
            bucket.setDimension("WAREHOUSE");
            bucket.setDimensionValue("仓-" + i);
            bucket.setWarehouse("仓-" + i);
            bucket.setStock(5);
            bucket.setTotal(5);
            bucket.setPriority(i);
            bucket.setStatus(1);
            invBucketMapper.insert(bucket);
        }

        String token = registerAndLogin(randomPhone());
        long userId = userIdOf(token);
        long addressId = createAddress(token);

        List<SeckillOrderMessage> messages = new ArrayList<>();
        List<String> requestIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String requestId = randomRequestId();
            requestIds.add(requestId);
            messages.add(
                    new SeckillOrderMessage(requestId, userId, item.getId(), addressId, "trace-bucket-" + i, null));
        }
        BatchPersistResult result = batchPersistService.persist(messages);
        assertThat(result.outcomes()).hasSize(3);
        assertThat(result.outcomes()).allMatch(BatchOrderOutcome::success);

        // 守恒不变量：桶合计 == 商品总库存
        int bucketSum = invBucketMapper.sumStockByProduct(product.getId());
        int productStock = productMapper.selectById(product.getId()).getStock();
        assertThat(bucketSum)
                .as("守恒不变量 SUM(inv_bucket.stock) == pms_product.stock")
                .isEqualTo(productStock);
        assertThat(productStock).as("3 单各 1 件，10 → 7").isEqualTo(7);

        // 审计流水：每个订单一条 OUTBOUND，幂等键为订单号
        for (String requestId : requestIds) {
            String orderNo = orderMapper
                    .selectOne(new LambdaQueryWrapper<Order>()
                            .eq(Order::getRequestId, requestId)
                            .last("limit 1"))
                    .getOrderNo();
            assertThat(invBucketLogMapper.countByOrderProduct(BucketBizType.OUTBOUND.name(), orderNo, product.getId()))
                    .as("订单 {} 应留下一条出库流水", orderNo)
                    .isEqualTo(1);
        }
    }
}
