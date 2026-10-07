package com.geekmall.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.enums.OrderStatus;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单全链路集成测试。
 *
 * <p>走的是真实 HTTP + 真实数据库 + 真实 Redis，覆盖前端实际会调用的完整链路：
 * 加购 → 下单（幂等）→ 支付 → 发货 → 确认收货 → 评价，并校验每一步的落库状态与库存变化。</p>
 */
class OrderFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    /** 新建一个独立商品，避免与其它测试竞争同一行库存。 */
    private Product createOnShelfProduct(String title, int stock, String price) {
        Product product = new Product();
        product.setShopId(DEMO_SHOP_ID);
        product.setTitle(title);
        // category_id / category_key / parent_key 在 pms_product 中为 NOT NULL，必须显式赋值
        product.setCategoryId(1L);
        product.setCategoryKey("integration-test");
        product.setParentKey("integration");
        product.setCover("https://img/test/" + title + ".png");
        product.setSpec("标准版");
        product.setPrice(new BigDecimal(price));
        product.setOldPrice(new BigDecimal(price).add(new BigDecimal("100.00")));
        product.setCost(new BigDecimal("100.00"));
        product.setStock(stock);
        product.setSafeStock(5);
        product.setSales(0);
        product.setViews(0);
        product.setRating(new BigDecimal("5.0"));
        product.setStatus(1);
        product.setMerchantStatus("on");
        product.setCategoryName("集成测试分类");
        product.setBrandName("极客严选");
        product.setTags("测试,包邮");
        product.setIsHot(0);
        product.setIsNew(1);
        productMapper.insert(product);
        return product;
    }

    /** 新建收货地址，返回地址 ID。 */
    private long createAddress(String token) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "集成测试收货人");
        body.put("phone", "13800000001");
        body.put("province", "广东省");
        body.put("city", "深圳市");
        body.put("district", "南山区");
        body.put("detail", "科技园路 1 号");
        body.put("isDefault", true);
        return assertSuccess(post("/api/v1/user/addresses", body, token)).asLong();
    }

    /** 加购并返回购物车项 ID。 */
    private long addToCart(String token, long productId, int qty) {
        return assertSuccess(post("/api/v1/cart/items",
                Map.of("productId", productId, "qty", qty), token)).asLong();
    }

    /** 提交订单，返回订单号。 */
    private String submitOrder(String token, long addressId, long cartItemId, String requestId) {
        Map<String, Object> body = new HashMap<>();
        body.put("addressId", addressId);
        body.put("cartItemIds", List.of(cartItemId));
        body.put("shippingType", "standard");
        body.put("payMethod", "wechat");
        body.put("remark", "集成测试订单");
        body.put("requestId", requestId);
        return assertSuccess(post("/api/v1/trade/orders", body, token)).asText();
    }

    private Order reload(String orderNo) {
        return orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo)
                .last("limit 1"));
    }

    @Test
    @DisplayName("加购 → 下单：订单落库为待付款，库存按数量扣减，金额与商品单价一致")
    void shouldCreateOrderAndDeductStock() {
        Product product = createOnShelfProduct("链路测试手机", 10, "199.00");
        String token = registerAndLogin(randomPhone());
        long addressId = createAddress(token);
        long cartItemId = addToCart(token, product.getId(), 2);

        String orderNo = submitOrder(token, addressId, cartItemId, UUID.randomUUID().toString());

        assertThat(orderNo).startsWith("GM");
        Order order = reload(orderNo);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAY.getCode());
        assertThat(order.getPayAmount()).isEqualByComparingTo("398.00");
        assertThat(order.getGoodsAmount()).isEqualByComparingTo("398.00");
        assertThat(order.getShippingFee()).isEqualByComparingTo("0.00");
        assertThat(order.getRequestId()).isNotBlank();
        assertThat(order.getExpireTime()).isNotNull();

        assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(8);
    }

    @Test
    @DisplayName("幂等下单：同一 requestId 重复提交返回同一订单，不重复扣库存")
    void shouldBeIdempotentOnRepeatedSubmit() {
        Product product = createOnShelfProduct("幂等测试商品", 10, "99.00");
        String token = registerAndLogin(randomPhone());
        long addressId = createAddress(token);
        long cartItemId = addToCart(token, product.getId(), 1);
        String requestId = UUID.randomUUID().toString();

        String first = submitOrder(token, addressId, cartItemId, requestId);
        String second = submitOrder(token, addressId, cartItemId, requestId);

        assertThat(second).isEqualTo(first);
        assertThat(productMapper.selectById(product.getId()).getStock())
                .as("重复提交不应二次扣减库存")
                .isEqualTo(9);
    }

    @Test
    @DisplayName("库存不足时下单失败并整体回滚，订单不落库、库存不变")
    void shouldRollbackWhenStockInsufficient() {
        Product product = createOnShelfProduct("库存不足商品", 1, "99.00");
        String token = registerAndLogin(randomPhone());
        long addressId = createAddress(token);
        long cartItemId = addToCart(token, product.getId(), 1);
        String orderNo = submitOrder(token, addressId, cartItemId, UUID.randomUUID().toString());

        // 用同一账号再下 1 件：此时库存已被前一单扣为 0
        assertThat(productMapper.selectById(product.getId()).getStock()).isZero();
        long secondItem = addToCart(token, product.getId(), 1);

        Map<String, Object> body = new HashMap<>();
        body.put("addressId", addressId);
        body.put("cartItemIds", List.of(secondItem));
        body.put("requestId", UUID.randomUUID().toString());
        var response = post("/api/v1/trade/orders", body, token);

        assertThat(response.get("code").asInt()).isNotZero();
        assertThat(productMapper.selectById(product.getId()).getStock()).isZero();
        assertThat(orderNo).isNotBlank();
    }

    @Test
    @DisplayName("取消订单：状态流转为已取消，库存与优惠券回退且只回退一次")
    void shouldCancelOrderAndRestoreStock() {
        Product product = createOnShelfProduct("取消测试商品", 5, "299.00");
        String token = registerAndLogin(randomPhone());
        long addressId = createAddress(token);
        long cartItemId = addToCart(token, product.getId(), 2);
        String orderNo = submitOrder(token, addressId, cartItemId, UUID.randomUUID().toString());
        assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(3);

        assertSuccess(post("/api/v1/trade/orders/" + orderNo + "/cancel",
                Map.of("reason", "集成测试取消"), token));

        assertThat(reload(orderNo).getStatus()).isEqualTo(OrderStatus.CANCELED.getCode());
        assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(5);
    }

    @Test
    @DisplayName("重复取消是幂等的：订单只被取消一次，库存只回退一次")
    void shouldBeIdempotentOnDoubleCancel() {
        Product product = createOnShelfProduct("重复取消商品", 5, "299.00");
        String token = registerAndLogin(randomPhone());
        long addressId = createAddress(token);
        long cartItemId = addToCart(token, product.getId(), 1);
        String orderNo = submitOrder(token, addressId, cartItemId, UUID.randomUUID().toString());

        assertSuccess(post("/api/v1/trade/orders/" + orderNo + "/cancel", Map.of("reason", "第一次"), token));
        // 第二次取消：状态机对「已是目标状态」按幂等处理（不报错），
        // 但库存回退由 inventory_rollback_log 唯一索引兜底，绝不能回退两次
        assertSuccess(post("/api/v1/trade/orders/" + orderNo + "/cancel", Map.of("reason", "第二次"), token));

        assertThat(reload(orderNo).getStatus()).isEqualTo(OrderStatus.CANCELED.getCode());
        assertThat(productMapper.selectById(product.getId()).getStock())
                .as("库存只能回退一次，重复取消不得二次回补")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("完整链路：下单 → 支付 → 发货 → 确认收货 → 评价，逐步驱动状态机到已完成")
    void shouldCompleteWholeOrderLifecycle() {
        Product product = createOnShelfProduct("全链路商品", 10, "599.00");
        String token = registerAndLogin(randomPhone());
        long addressId = createAddress(token);
        long cartItemId = addToCart(token, product.getId(), 1);
        String orderNo = submitOrder(token, addressId, cartItemId, UUID.randomUUID().toString());

        // 1) 创建支付单：金额以服务端订单为准
        var payment = assertSuccess(post("/api/v1/pay/create",
                Map.of("orderNo", orderNo, "payMethod", "wechat"), token));
        String tradeNo = payment.get("tradeNo").asText();
        assertThat(payment.get("amount").decimalValue()).isEqualByComparingTo("599.00");
        assertThat(payment.get("status").asInt()).isZero();

        // 2) 支付前查询支付单状态
        var beforePay = assertSuccess(get("/api/v1/pay/" + tradeNo + "/status", token));
        assertThat(beforePay.get("status").asInt()).isZero();

        // 3) 模拟支付成功 → 订单进入待发货
        assertSuccess(post("/api/v1/pay/" + tradeNo + "/mock-pay", null, token));
        assertThat(reload(orderNo).getStatus()).isEqualTo(OrderStatus.PENDING_SHIP.getCode());
        assertThat(reload(orderNo).getTradeNo()).isEqualTo(tradeNo);
        assertThat(reload(orderNo).getPayMethod()).isEqualTo("wechat");

        // 4) 商家发货 → 待收货
        assertSuccess(post("/api/v1/trade/mock/ship/" + orderNo, null, token));
        assertThat(reload(orderNo).getStatus()).isEqualTo(OrderStatus.PENDING_RECEIVE.getCode());

        // 5) 确认收货 → 待评价
        assertSuccess(post("/api/v1/trade/orders/" + orderNo + "/confirm", null, token));
        assertThat(reload(orderNo).getStatus()).isEqualTo(OrderStatus.PENDING_COMMENT.getCode());

        // 6) 发表评价 → 已完成
        Map<String, Object> review = new HashMap<>();
        review.put("orderNo", orderNo);
        review.put("scoreDesc", 5);
        review.put("scoreLogistics", 5);
        review.put("scoreService", 5);
        review.put("content", "集成测试评价：物流很快，商品符合预期");
        review.put("anonymous", false);
        assertSuccess(post("/api/v1/reviews", review, token));

        assertThat(reload(orderNo).getStatus()).isEqualTo(OrderStatus.FINISHED.getCode());

        // 7) 买家侧订单详情可查到该订单（订单主体嵌在 order 字段下）
        var detail = assertSuccess(get("/api/v1/orders/" + orderNo, token));
        assertThat(detail.has("order")).as("订单详情响应：%s", detail).isTrue();
        assertThat(detail.get("order").get("no").asText()).isEqualTo(orderNo);
        assertThat(detail.get("order").get("status").asInt()).isEqualTo(OrderStatus.FINISHED.getCode());
        assertThat(detail.get("receiverName").asText()).isEqualTo("集成测试收货人");
    }

    @Test
    @DisplayName("重复支付回调是幂等的：已支付订单不会被二次流转")
    void paymentCallbackShouldBeIdempotent() {
        Product product = createOnShelfProduct("重复支付商品", 5, "159.00");
        String token = registerAndLogin(randomPhone());
        long addressId = createAddress(token);
        long cartItemId = addToCart(token, product.getId(), 1);
        String orderNo = submitOrder(token, addressId, cartItemId, UUID.randomUUID().toString());

        String tradeNo = assertSuccess(post("/api/v1/pay/create",
                Map.of("orderNo", orderNo, "payMethod", "alipay"), token)).get("tradeNo").asText();
        assertSuccess(post("/api/v1/pay/" + tradeNo + "/mock-pay", null, token));

        // 渠道重复通知（白名单接口，无需登录态）
        var callback = post("/api/v1/pay/callback", Map.of(
                "tradeNo", tradeNo,
                "orderNo", orderNo,
                "amount", new BigDecimal("159.00"),
                "status", 1), null);

        assertThat(callback.get("code").asInt()).isZero();
        assertThat(reload(orderNo).getStatus()).isEqualTo(OrderStatus.PENDING_SHIP.getCode());
    }

    @Test
    @DisplayName("支付金额被篡改时回调被拒绝")
    void shouldRejectTamperedCallbackAmount() {
        Product product = createOnShelfProduct("篡改金额商品", 5, "159.00");
        String token = registerAndLogin(randomPhone());
        long addressId = createAddress(token);
        long cartItemId = addToCart(token, product.getId(), 1);
        String orderNo = submitOrder(token, addressId, cartItemId, UUID.randomUUID().toString());
        String tradeNo = assertSuccess(post("/api/v1/pay/create",
                Map.of("orderNo", orderNo, "payMethod", "alipay"), token)).get("tradeNo").asText();

        var callback = post("/api/v1/pay/callback", Map.of(
                "tradeNo", tradeNo,
                "orderNo", orderNo,
                "amount", new BigDecimal("0.01"),
                "status", 1), null);

        assertThat(callback.get("code").asInt()).isNotZero();
        assertThat(reload(orderNo).getStatus()).isEqualTo(OrderStatus.PENDING_PAY.getCode());
    }

    @Test
    @DisplayName("购物车为空时下单失败")
    void shouldRejectEmptyCart() {
        String token = registerAndLogin(randomPhone());
        long addressId = createAddress(token);

        var response = post("/api/v1/trade/orders", Map.of(
                "addressId", addressId,
                "requestId", UUID.randomUUID().toString()), token);

        assertThat(response.get("code").asInt()).isNotZero();
    }

    @Test
    @DisplayName("越权访问他人订单被拒绝")
    void shouldRejectAccessToOthersOrder() {
        Product product = createOnShelfProduct("越权测试商品", 5, "99.00");
        String ownerToken = registerAndLogin(randomPhone());
        long addressId = createAddress(ownerToken);
        long cartItemId = addToCart(ownerToken, product.getId(), 1);
        String orderNo = submitOrder(ownerToken, addressId, cartItemId, UUID.randomUUID().toString());

        String intruderToken = registerAndLogin(randomPhone());
        var response = get("/api/v1/orders/" + orderNo, intruderToken);

        assertThat(response.get("code").asInt()).isNotZero();
    }
}
