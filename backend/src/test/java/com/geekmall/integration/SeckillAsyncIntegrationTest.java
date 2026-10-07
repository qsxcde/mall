package com.geekmall.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.geekmall.common.constant.RedisKeys;
import com.geekmall.common.constant.SecurityConstants;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.marketing.entity.SeckillItem;
import com.geekmall.modules.marketing.entity.SeckillSession;
import com.geekmall.modules.marketing.mapper.SeckillItemMapper;
import com.geekmall.modules.marketing.mapper.SeckillSessionMapper;
import com.geekmall.modules.marketing.queue.SeckillGrabResult;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 秒杀削峰（Redis Stream 异步落库）集成测试。
 *
 * <p>这些断言要回答的是「削峰真的成立吗」这类问题，而不是「代码看起来对不对」：</p>
 * <ol>
 *   <li>抢购接口在削峰模式下返回的是<b>请求号</b>而非订单号（契约确实变了）；</li>
 *   <li>后台消费者真的把订单建出来了（结果能轮询到 SUCCESS + 订单号）；</li>
 *   <li><b>重复投递不重复建单</b> —— 这条最关键，模拟的是「订单已建、结果未写」时消费者崩溃，
 *       消息被回收重投的场景。此时结果里还是 QUEUED，消费端会重新走建单，
 *       必须由 {@code uk_user_request} 唯一索引拦下并返回原订单号。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "mall.seckill.async.enabled=true",
        "mall.seckill.async.consumer-threads=2",
        "mall.seckill.async.block=200ms",
        "mall.seckill.async.reclaim-min-idle=1s",
        "mall.seckill.async.reclaim-interval=1s",
        "mall.seckill.async.result-ttl=10m"
})
@DisplayName("秒杀削峰集成测试")
class SeckillAsyncIntegrationTest extends AbstractIntegrationTest {

    private static final long RESULT_WAIT_MS = 20_000L;

    @Autowired
    private SeckillSessionMapper seckillSessionMapper;
    @Autowired
    private SeckillItemMapper seckillItemMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private OrderMapper orderMapper;

    /* ------------------------------ 数据准备 ------------------------------ */

    private long createSession() {
        SeckillSession session = new SeckillSession();
        session.setSessionTime("21:00");
        session.setLabel("削峰测试场次");
        session.setState("running");
        session.setSort(98);
        seckillSessionMapper.insert(session);
        return session.getId();
    }

    private Product createProduct(String title, int stock, String price) {
        Product product = new Product();
        product.setShopId(DEMO_SHOP_ID);
        product.setTitle(title);
        product.setCategoryId(1L);
        product.setCategoryKey("integration-seckill-async");
        product.setParentKey("integration");
        product.setCover("https://img/seckill/" + title + ".png");
        product.setSpec("削峰版");
        product.setPrice(new BigDecimal(price));
        product.setCost(new BigDecimal("100.00"));
        product.setStock(stock);
        product.setSafeStock(1);
        product.setSales(0);
        product.setViews(0);
        product.setRating(new BigDecimal("5.0"));
        product.setStatus(1);
        product.setMerchantStatus("on");
        product.setCategoryName("削峰测试分类");
        product.setBrandName("极客严选");
        product.setIsHot(1);
        product.setIsNew(0);
        productMapper.insert(product);
        return product;
    }

    private SeckillItem createSeckillItem(long sessionId, long productId, int activityStock, String seckillPrice) {
        SeckillItem item = new SeckillItem();
        item.setSessionId(sessionId);
        item.setProductId(productId);
        item.setSeckillPrice(new BigDecimal(seckillPrice));
        item.setOldPrice(new BigDecimal("999.00"));
        item.setStock(activityStock);
        item.setTotal(activityStock);
        item.setSold(0);
        item.setTip("削峰集成测试");
        item.setNotStart(0);
        seckillItemMapper.insert(item);
        return item;
    }

    private long createAddress(String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "削峰收货人");
        body.put("phone", "13800000003");
        body.put("province", "广东省");
        body.put("city", "深圳市");
        body.put("district", "南山区");
        body.put("detail", "削峰大道 1 号");
        body.put("isDefault", true);
        return assertSuccess(post("/api/v1/user/addresses", body, token)).asLong();
    }

    /**
     * 发起抢购并返回业务响应。
     *
     * <p>抢购接口返回 {@link org.springframework.web.context.request.async.DeferredResult}，
     * MockMvc 必须在异步开始后显式 {@code asyncDispatch} 才能拿到响应体 ——
     * 否则读到的是一段空 body，解析出来自然没有 {@code code} 字段。</p>
     */
    private JsonNode grabOrder(long itemId, long addressId, String token) throws Exception {
        // 用全限定名而非 static import：父类已有 post(String, Object, String) 实例方法，会撞名
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders
                .post("/api/v1/seckill/" + itemId + "/order")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("addressId", addressId)));
        builder.header(SecurityConstants.TOKEN_HEADER, SecurityConstants.TOKEN_PREFIX + token);

        MvcResult result = mockMvc.perform(builder).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn();
        }
        String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(json);
    }

    /** 轮询抢购结果直到终态（成功或失败）。 */
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

    private void redeliver(SeckillGrabResult settled, long userId, SeckillItem item, long addressId) {
        // 手动再投一条 requestId 完全相同的消息，模拟消费者崩溃后消息被回收重投
        stringRedisTemplate.opsForStream().add(StreamRecords.mapBacked(Map.of(
                        "requestId", settled.requestId(),
                        "userId", String.valueOf(userId),
                        "itemId", String.valueOf(item.getId()),
                        "addressId", String.valueOf(addressId)))
                .withStreamKey(RedisKeys.SECKILL_ORDER_STREAM));
    }

    /* ------------------------------ 用例 ------------------------------ */

    @Test
    @DisplayName("削峰模式：抢购立即返回请求号，后台异步落库，轮询可拿到订单号")
    void shouldGrabAsynchronously() throws Exception {
        long sessionId = createSession();
        Product product = createProduct("削峰商品A", 10, "999.00");
        SeckillItem item = createSeckillItem(sessionId, product.getId(), 5, "199.00");
        String phone = randomPhone();
        String token = registerAndLogin(phone);
        long addressId = createAddress(token);

        String requestId = assertSuccess(grabOrder(item.getId(), addressId, token)).asText();

        assertThat(requestId)
                .as("削峰模式返回的是 32 位请求号，而不是订单号 —— 这正是接口契约的变化")
                .hasSize(32)
                .doesNotStartWith("GM");

        SeckillGrabResult result = awaitResult(token, requestId);

        assertThat(result.status()).isEqualTo(SeckillGrabResult.Status.SUCCESS);
        assertThat(result.orderNo()).startsWith("GM");

        // 库存由异步落库结果扣减：活动库存 5→4，商品库存 10→9
        assertThat(seckillItemMapper.selectById(item.getId()).getStock()).isEqualTo(4);
        assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(9);
    }

    @Test
    @DisplayName("崩溃恢复：结果未写入时重投消息，靠订单唯一索引兜底，不重复建单")
    void shouldNotDuplicateOrderOnRedelivery() throws Exception {
        long sessionId = createSession();
        Product product = createProduct("削峰商品B", 10, "999.00");
        SeckillItem item = createSeckillItem(sessionId, product.getId(), 5, "199.00");
        String phone = randomPhone();
        String token = registerAndLogin(phone);
        long addressId = createAddress(token);

        String requestId = assertSuccess(grabOrder(item.getId(), addressId, token)).asText();
        SeckillGrabResult first = awaitResult(token, requestId);
        assertThat(first.status()).isEqualTo(SeckillGrabResult.Status.SUCCESS);

        long userId = Long.parseLong(assertSuccess(get("/api/v1/user/profile", token))
                .get("id").asText());
        int activityStockBefore = seckillItemMapper.selectById(item.getId()).getStock();
        int productStockBefore = productMapper.selectById(product.getId()).getStock();

        // 抹掉结果，构造「订单已建、结果未写成功」的崩溃现场：
        // 此时消费端看到的是「无结果」，会重新走一次建单
        stringRedisTemplate.delete(RedisKeys.seckillResult(requestId));
        redeliver(first, userId, item, addressId);

        // 等消费端处理完（block 200ms + 处理耗时，给足余量）
        Thread.sleep(3_000);

        long orders = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getRequestId, requestId));
        assertThat(orders)
                .as("同一 requestId 只允许存在一单 —— 靠 uk_user_request 唯一索引兜底")
                .isEqualTo(1);

        // 重投那次建单被唯一索引拦下 → 事务回滚 → 库存不能被二次扣减
        assertThat(seckillItemMapper.selectById(item.getId()).getStock())
                .as("重投导致的建单失败必须整体回滚，活动库存不能多扣")
                .isEqualTo(activityStockBefore);
        assertThat(productMapper.selectById(product.getId()).getStock())
                .as("商品库存同理")
                .isEqualTo(productStockBefore);

        // 且消费端应当把结果补写回终态，让用户不再卡在「排队中」
        SeckillGrabResult recovered = awaitResult(token, requestId);
        assertThat(recovered.status())
                .as("重投后结果应被补写为成功，用户才能拿到订单号")
                .isEqualTo(SeckillGrabResult.Status.SUCCESS);
        assertThat(recovered.orderNo()).isEqualTo(first.orderNo());
    }

    @Test
    @DisplayName("越权防护：用他人的 requestId 查结果是资源不存在，不泄露订单号")
    void shouldRejectCrossUserResultQuery() throws Exception {
        long sessionId = createSession();
        Product product = createProduct("削峰商品C", 10, "999.00");
        SeckillItem item = createSeckillItem(sessionId, product.getId(), 5, "199.00");
        String ownerToken = registerAndLogin(randomPhone());
        long addressId = createAddress(ownerToken);

        String requestId = assertSuccess(grabOrder(item.getId(), addressId, ownerToken)).asText();
        awaitResult(ownerToken, requestId);

        String otherToken = registerAndLogin(randomPhone());
        JsonNode response = get("/api/v1/seckill/result/" + requestId, otherToken);

        assertThat(response.get("code").asInt())
                .as("不属于自己的抢购记录一律按不存在返回")
                .isEqualTo(ResultCode.NOT_FOUND.getCode());
    }
}
