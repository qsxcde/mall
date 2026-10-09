package com.geekmall.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.constant.RedisKeys;
import com.geekmall.common.enums.OrderStatus;
import com.geekmall.modules.payment.channel.LocalSandboxChannel;
import com.geekmall.modules.payment.entity.PaymentRecord;
import com.geekmall.modules.payment.entity.PaymentRefundRecord;
import com.geekmall.modules.payment.mapper.PaymentRecordMapper;
import com.geekmall.modules.payment.mapper.PaymentRefundRecordMapper;
import com.geekmall.modules.payment.service.PaymentService;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
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

/**
 * 支付沙箱闭环集成测试。
 *
 * <p>测试环境固定走<b>本地沙箱渠道</b>：进程内自生成密钥对，用与支付宝相同的 RSA2 算法
 * 签名/验签，因此<b>「验签 → 幂等 → 金额与状态校验 → 关单转退款」这条链路是被真实执行的</b>，
 * 只是不产生网络请求与真实资金。</p>
 */
class PaymentSandboxIntegrationTest extends AbstractIntegrationTest {

    private static final String SUCCESS = "success";
    private static final String FAILURE = "failure";

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private PaymentRecordMapper paymentRecordMapper;

    @Autowired
    private PaymentRefundRecordMapper refundRecordMapper;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private LocalSandboxChannel localChannel;

    /* ============================ 下单 / 预支付 ============================ */

    @Test
    @DisplayName("创建支付单返回渠道二维码；同一订单重复创建复用同一张单")
    void createReturnsQrAndReusesPendingPayment() {
        Scenario s = newOrder("199.00");

        var status = assertSuccess(get("/api/v1/pay/" + s.tradeNo() + "/status", s.token()));
        assertThat(status.get("qrCode").asText()).as("本地渠道也应返回可渲染的二维码内容").isNotBlank();
        assertThat(status.get("channel").asText()).isEqualTo("local");

        // 前端切换支付方式会重新 create：必须复用，否则同一订单会产生多张可付的渠道订单
        String again = assertSuccess(
                        post("/api/v1/pay/create", Map.of("orderNo", s.orderNo(), "payMethod", "alipay"), s.token()))
                .get("tradeNo")
                .asText();
        assertThat(again).isEqualTo(s.tradeNo());
    }

    /* ============================ 回调：验签与分支 ============================ */

    @Test
    @DisplayName("验签通过的通知落账：订单转待发货，渠道交易号入库")
    void signedNotifyAppliesPayment() {
        Scenario s = newOrder("159.00");

        assertThat(postFormRaw("/api/v1/pay/callback", signed(s, "TRADE_SUCCESS", "159.00")))
                .isEqualTo(SUCCESS);

        assertThat(reloadOrder(s.orderNo()).getStatus()).isEqualTo(OrderStatus.PENDING_SHIP.getCode());
        PaymentRecord record = reloadPayment(s.tradeNo());
        assertThat(record.getStatus()).isEqualTo(1);
        assertThat(record.getChannelTradeNo()).as("渠道交易号必须落库，供对账").isNotBlank();
    }

    @Test
    @DisplayName("签名被篡改的通知被拒绝：应答 failure 且订单不动")
    void tamperedSignatureIsRejected() {
        Scenario s = newOrder("159.00");

        Map<String, String> notify = signed(s, "TRADE_SUCCESS", "159.00");
        // 签名之后改动 trade_no → 验签必失败（其余字段仍然合法，以隔离「验签」这一层）
        notify.put("trade_no", "2026100999999999999999");

        assertThat(postFormRaw("/api/v1/pay/callback", notify)).isEqualTo(FAILURE);
        assertThat(reloadOrder(s.orderNo()).getStatus()).isEqualTo(OrderStatus.PENDING_PAY.getCode());
        assertThat(reloadPayment(s.tradeNo()).getStatus()).isEqualTo(0);
    }

    @Test
    @DisplayName("WAIT_BUYER_PAY 中间态：不改状态但必须应答 success")
    void waitBuyerPayDoesNotChangeStatus() {
        Scenario s = newOrder("159.00");

        // 若应答 failure，渠道会不断重推；把「已下单未付款」当失败是常见的坑
        assertThat(postFormRaw("/api/v1/pay/callback", signed(s, "WAIT_BUYER_PAY", "159.00")))
                .isEqualTo(SUCCESS);

        assertThat(reloadOrder(s.orderNo()).getStatus()).isEqualTo(OrderStatus.PENDING_PAY.getCode());
        assertThat(reloadPayment(s.tradeNo()).getStatus()).isEqualTo(0);
    }

    @Test
    @DisplayName("TRADE_CLOSED：支付单置为已关闭")
    void tradeClosedClosesPayment() {
        Scenario s = newOrder("159.00");

        assertThat(postFormRaw("/api/v1/pay/callback", signed(s, "TRADE_CLOSED", "159.00")))
                .isEqualTo(SUCCESS);

        assertThat(reloadPayment(s.tradeNo()).getStatus()).isEqualTo(3);
    }

    @Test
    @DisplayName("未知 out_trade_no：应答 success，避免渠道无意义重试")
    void unknownOutTradeNoAnswersSuccess() {
        Map<String, String> notify =
                localChannel.signNotify(notifyParams("PAY_NOT_EXIST_123", "159.00", "TRADE_SUCCESS"));

        assertThat(postFormRaw("/api/v1/pay/callback", notify)).isEqualTo(SUCCESS);
    }

    /* ============================ 关单转退款 ============================ */

    @Test
    @DisplayName("订单先被取消、支付通知后到：自动转退款且只退一次")
    void closedOrderShouldBeRefunded() {
        Scenario s = newOrder("88.00");

        // 订单先被取消（模拟「超时关闭 / 用户取消」与「支付到账」的竞态）
        assertSuccess(post("/api/v1/trade/orders/" + s.orderNo() + "/cancel", Map.of(), s.token()));
        assertThat(reloadOrder(s.orderNo()).getStatus()).isEqualTo(OrderStatus.CANCELED.getCode());

        // 支付通知此刻到达：钱收到了，但订单已关闭
        assertThat(postFormRaw("/api/v1/pay/callback", signed(s, "TRADE_SUCCESS", "88.00")))
                .isEqualTo(SUCCESS);

        PaymentRecord record = reloadPayment(s.tradeNo());
        assertThat(record.getStatus()).as("钱到了，支付单仍是成功").isEqualTo(1);
        assertThat(record.getRefundStatus()).as("必须自动退款，资金不能没有出路").isEqualTo(2);
        assertThat(reloadRefund(s.tradeNo()).getStatus()).isEqualTo(1);

        // 渠道重复通知：不得产生第二张退款单
        assertThat(postFormRaw("/api/v1/pay/callback", signed(s, "TRADE_SUCCESS", "88.00")))
                .isEqualTo(SUCCESS);
        assertThat(refundCount(s.tradeNo())).isEqualTo(1);
    }

    @Test
    @DisplayName("已支付订单被用户取消：触发退款")
    void cancelPaidOrderRefunds() {
        Scenario s = newOrder("66.00");
        assertThat(postFormRaw("/api/v1/pay/callback", signed(s, "TRADE_SUCCESS", "66.00")))
                .isEqualTo(SUCCESS);
        assertThat(reloadOrder(s.orderNo()).getStatus()).isEqualTo(OrderStatus.PENDING_SHIP.getCode());

        assertSuccess(post("/api/v1/trade/orders/" + s.orderNo() + "/cancel", Map.of(), s.token()));

        assertThat(reloadPayment(s.tradeNo()).getRefundStatus()).isEqualTo(2);
        assertThat(reloadRefund(s.tradeNo()).getStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("已支付订单被商家关闭：触发退款")
    void merchantClosePaidOrderRefunds() {
        Scenario s = newOrder("77.00");
        assertThat(postFormRaw("/api/v1/pay/callback", signed(s, "TRADE_SUCCESS", "77.00")))
                .isEqualTo(SUCCESS);

        String merchantToken = loginMerchant(DEMO_MERCHANT, "123456");
        Map<String, Object> body = new HashMap<>();
        body.put("ids", List.of(s.orderNo()));
        body.put("reason", "缺货，商家关闭");
        assertSuccess(post("/api/v1/merchant/order/close", body, merchantToken));

        assertThat(reloadOrder(s.orderNo()).getStatus()).isEqualTo(OrderStatus.CANCELED.getCode());
        assertThat(reloadPayment(s.tradeNo()).getRefundStatus()).isEqualTo(2);
        assertThat(reloadRefund(s.tradeNo()).getStatus()).isEqualTo(1);
    }

    /* ============================ 主动查单补偿 ============================ */

    @Test
    @DisplayName("回调丢失时主动查单补偿落账；重复执行只落账一次")
    void compensationAppliesOnceEvenIfRunTwice() {
        Scenario s = newOrder("55.00");
        // 模拟「用户已付款但回调没到」：渠道侧已支付，本地仍是待支付。
        // 把创建时间回拨，贴近真实场景（卡住的支付单通常已经放了一会儿）
        backdatePayment(s.tradeNo(), LocalDateTime.now().minusMinutes(5));
        localChannel.markPaid(s.tradeNo(), "CH" + s.tradeNo(), new BigDecimal("55.00"));

        assertThat(paymentService.compensatePending(200)).as("应有 1 笔被修正").isEqualTo(1);
        assertThat(reloadOrder(s.orderNo()).getStatus()).isEqualTo(OrderStatus.PENDING_SHIP.getCode());
        assertThat(reloadPayment(s.tradeNo()).getStatus()).isEqualTo(1);

        // 再跑一遍：已不再是待支付，不应重复落账
        assertThat(paymentService.compensatePending(200)).as("幂等：第二次为 0").isZero();
    }

    /* ============================ 无回调通道：状态轮询驱动查单 ============================ */

    @Test
    @DisplayName("没有公网回调通道时：查状态会驱动主动查单，并按节流窗口防重")
    void statusQueryDrivesPaymentWhenCallbackLost() {
        Scenario s = newOrder("44.00");
        localChannel.markPaid(s.tradeNo(), "CH" + s.tradeNo(), new BigDecimal("44.00"));
        stringRedisTemplate.delete(RedisKeys.payQueryThrottle(s.tradeNo()));

        // 用户付款后只轮询状态（回调打不进来），应被主动查单捞回来
        var status = assertSuccess(get("/api/v1/pay/" + s.tradeNo() + "/status", s.token()));
        assertThat(status.get("status").asInt()).isEqualTo(1);
        assertThat(reloadOrder(s.orderNo()).getStatus()).isEqualTo(OrderStatus.PENDING_SHIP.getCode());

        // 节流闸门已生效：窗口内同一支付单不会再次查网关（否则多页面轮询会打爆网关）
        assertThat(stringRedisTemplate.hasKey(RedisKeys.payQueryThrottle(s.tradeNo())))
                .as("节流 key 存在，说明本次确实发起了查单并设置了窗口")
                .isTrue();
    }

    @Test
    @DisplayName("回调与主动查单并发到达：只落账一次，不产生退款")
    void notifyAndQueryConcurrentlyApplyOnce() throws Exception {
        Scenario s = newOrder("123.00");
        localChannel.markPaid(s.tradeNo(), "CH" + s.tradeNo(), new BigDecimal("123.00"));
        Map<String, String> notify = signed(s, "TRADE_SUCCESS", "123.00");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<String> byNotify = pool.submit(() -> {
                start.await();
                return paymentService.handleNotify(notify);
            });
            Future<Integer> byQuery = pool.submit(() -> {
                start.await();
                return paymentService.compensatePending(200);
            });
            start.countDown();

            assertThat(byNotify.get(15, TimeUnit.SECONDS)).isEqualTo(SUCCESS);
            byQuery.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 两条入口共用 `status != 1` 的条件更新，只有一个能落账；不应出现二次流转或误退款
        assertThat(reloadOrder(s.orderNo()).getStatus()).isEqualTo(OrderStatus.PENDING_SHIP.getCode());
        assertThat(reloadPayment(s.tradeNo()).getStatus()).isEqualTo(1);
        assertThat(refundCount(s.tradeNo())).isZero();
    }

    /* ============================ 夹具 ============================ */

    /** 一次完整下单：新建商品 → 加购 → 下单 → 创建支付单。 */
    private Scenario newOrder(String price) {
        Product product = createOnShelfProduct("支付闭环测试商品", 5, price);
        String token = registerAndLogin(randomPhone());
        long addressId = createAddress(token);
        long cartItemId = addToCart(token, product.getId(), 1);
        String orderNo =
                submitOrder(token, addressId, cartItemId, UUID.randomUUID().toString());
        String tradeNo = assertSuccess(
                        post("/api/v1/pay/create", Map.of("orderNo", orderNo, "payMethod", "alipay"), token))
                .get("tradeNo")
                .asText();
        return new Scenario(token, orderNo, tradeNo);
    }

    private Map<String, String> notifyParams(String outTradeNo, String amount, String tradeStatus) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("out_trade_no", outTradeNo);
        params.put("trade_no", "2026100922001" + UUID.randomUUID().toString().substring(0, 8));
        params.put("total_amount", amount);
        params.put("trade_status", tradeStatus);
        params.put("app_id", "2021000000000000");
        params.put("seller_id", "2088000000000000");
        return params;
    }

    /** 构造并签名一份通知（用本地渠道私钥，等价于渠道服务器发来的报文）。 */
    private Map<String, String> signed(Scenario s, String tradeStatus, String amount) {
        return localChannel.signNotify(notifyParams(s.tradeNo(), amount, tradeStatus));
    }

    private Product createOnShelfProduct(String title, int stock, String price) {
        Product product = new Product();
        product.setShopId(DEMO_SHOP_ID);
        product.setTitle(title + " " + UUID.randomUUID().toString().substring(0, 8));
        product.setCategoryId(1L);
        product.setCategoryKey("integration-test");
        product.setParentKey("integration");
        product.setCover("https://img/test/pay.png");
        product.setSpec("标准版");
        product.setPrice(new BigDecimal(price));
        product.setOldPrice(new BigDecimal(price).add(new BigDecimal("10.00")));
        product.setCost(new BigDecimal("1.00"));
        product.setStock(stock);
        product.setSafeStock(1);
        product.setSales(0);
        product.setViews(0);
        product.setRating(new BigDecimal("5.0"));
        product.setStatus(1);
        product.setMerchantStatus("on");
        product.setCategoryName("集成测试分类");
        product.setBrandName("极客严选");
        product.setTags("测试");
        product.setIsHot(0);
        product.setIsNew(1);
        productMapper.insert(product);
        return product;
    }

    private long createAddress(String token) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "支付测试收货人");
        body.put("phone", "13800000002");
        body.put("province", "广东省");
        body.put("city", "深圳市");
        body.put("district", "南山区");
        body.put("detail", "科技园路 2 号");
        body.put("isDefault", true);
        return assertSuccess(post("/api/v1/user/addresses", body, token)).asLong();
    }

    private long addToCart(String token, long productId, int qty) {
        return assertSuccess(post("/api/v1/cart/items", Map.of("productId", productId, "qty", qty), token))
                .asLong();
    }

    private String submitOrder(String token, long addressId, long cartItemId, String requestId) {
        Map<String, Object> body = new HashMap<>();
        body.put("addressId", addressId);
        body.put("cartItemIds", List.of(cartItemId));
        body.put("shippingType", "standard");
        body.put("payMethod", "alipay");
        body.put("remark", "支付闭环测试订单");
        body.put("requestId", requestId);
        return assertSuccess(post("/api/v1/trade/orders", body, token)).asText();
    }

    private Order reloadOrder(String orderNo) {
        return orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo).last("limit 1"));
    }

    private PaymentRecord reloadPayment(String tradeNo) {
        return paymentRecordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getTradeNo, tradeNo)
                .last("limit 1"));
    }

    private PaymentRefundRecord reloadRefund(String tradeNo) {
        return refundRecordMapper.selectOne(new LambdaQueryWrapper<PaymentRefundRecord>()
                .eq(PaymentRefundRecord::getTradeNo, tradeNo)
                .last("limit 1"));
    }

    private long refundCount(String tradeNo) {
        return refundRecordMapper.selectCount(
                new LambdaQueryWrapper<PaymentRefundRecord>().eq(PaymentRefundRecord::getTradeNo, tradeNo));
    }

    /** 回拨支付单创建时间，用于把支付单造成「放了一会儿还没回调」的样子。 */
    private void backdatePayment(String tradeNo, LocalDateTime createTime) {
        PaymentRecord record = reloadPayment(tradeNo);
        PaymentRecord update = new PaymentRecord();
        update.setId(record.getId());
        update.setCreateTime(createTime);
        paymentRecordMapper.updateById(update);
    }

    /** 一次下单场景的上下文。 */
    private record Scenario(String token, String orderNo, String tradeNo) {}
}
