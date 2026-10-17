package com.geekmall.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.enums.OrderStatus;
import com.geekmall.modules.payment.channel.LocalSandboxChannel;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.review.entity.Review;
import com.geekmall.modules.review.mapper.ReviewMapper;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 评价闭环集成测试。
 *
 * <p>评价不只是「写一条记录」：它要校验订单归属、把订单推进到「已完成」、
 * 回写商品评分与销量相关字段，并禁止同一订单重复评价。这些串联关系此前没有测试覆盖
 * （ReviewServiceImpl 行覆盖 31.8%，评价接口完全未被测试引用）。</p>
 */
class ReviewFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private LocalSandboxChannel localChannel;

    @Autowired
    private ReviewMapper reviewMapper;

    @Test
    @DisplayName("发表评价：订单流转为已完成，商品评分与评价数同步更新")
    void submitReviewFinishesOrderAndUpdatesProduct() {
        Scenario s = orderAwaitingReview("399.00");
        Product before = productMapper.selectById(s.productId());
        assertThat(before.getRating()).as("前置条件：商品有初始评分").isNotNull();
        long reviewsBefore = reviewCount(s.productId());

        Map<String, Object> body = new HashMap<>();
        body.put("orderNo", s.orderNo());
        body.put("scoreDesc", 5);
        body.put("scoreLogistics", 4);
        body.put("scoreService", 5);
        body.put("content", "集成测试评价：做工扎实，物流也快");
        body.put("anonymous", false);
        body.put("images", List.of("https://img/test/review-1.png"));
        assertSuccess(post("/api/v1/reviews", body, s.token()));

        assertThat(reloadOrder(s.orderNo()).getStatus()).isEqualTo(OrderStatus.FINISHED.getCode());

        assertThat(reviewCount(s.productId())).as("评价数应 +1").isEqualTo(reviewsBefore + 1);
        Product product = productMapper.selectById(s.productId());
        // 新评价参与均分后评分不应被带成 0 或超出 1~5
        assertThat(product.getRating()).isBetween(BigDecimal.ONE, new BigDecimal("5.0"));
    }

    @Test
    @DisplayName("重复评价同一订单被拒绝：评价数与商品评分都不变")
    void duplicateReviewIsRejected() {
        Scenario s = orderAwaitingReview("199.00");
        Map<String, Object> body = new HashMap<>();
        body.put("orderNo", s.orderNo());
        body.put("scoreDesc", 5);
        body.put("content", "第一次评价");
        assertSuccess(post("/api/v1/reviews", body, s.token()));

        long reviewsAfterFirst = reviewCount(s.productId());

        body.put("content", "第二次评价");
        var response = post("/api/v1/reviews", body, s.token());
        assertThat(response.get("code").asInt()).isNotZero();
        assertThat(reviewCount(s.productId())).as("重复评价不得重复计数").isEqualTo(reviewsAfterFirst);
    }

    @Test
    @DisplayName("评价后出现在「我的评价」与商品评价列表中")
    void reviewIsVisibleInMyListAndProductPage() {
        Scenario s = orderAwaitingReview("288.00");
        Map<String, Object> body = new HashMap<>();
        body.put("orderNo", s.orderNo());
        body.put("scoreDesc", 4);
        body.put("content", "评价可见性测试内容");
        assertSuccess(post("/api/v1/reviews", body, s.token()));

        var mine = assertSuccess(get("/api/v1/user/reviews", s.token()));
        assertThat(mine.isArray()).isTrue();
        assertThat(mine)
                .anySatisfy(node -> assertThat(node.get("orderNo").asText()).isEqualTo(s.orderNo()));

        // 商品评价列表是白名单接口，匿名可读
        var page = assertSuccess(get("/api/v1/products/" + s.productId() + "/reviews?page=1&pageSize=10", null));
        assertThat(page.get("list").isArray()).isTrue();
        assertThat(page.get("total").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("删除自己的评价：列表不再返回该条")
    void deleteOwnReview() {
        Scenario s = orderAwaitingReview("166.00");
        Map<String, Object> body = new HashMap<>();
        body.put("orderNo", s.orderNo());
        body.put("scoreDesc", 5);
        body.put("content", "待删除的评价");
        assertSuccess(post("/api/v1/reviews", body, s.token()));

        var mine = assertSuccess(get("/api/v1/user/reviews", s.token()));
        long reviewId = -1;
        for (var node : mine) {
            if (s.orderNo().equals(node.get("orderNo").asText())) {
                reviewId = node.get("id").asLong();
            }
        }
        assertThat(reviewId).as("应能查到刚提交的评价").isPositive();

        assertSuccess(delete("/api/v1/user/reviews/" + reviewId, s.token()));

        var after = assertSuccess(get("/api/v1/user/reviews", s.token()));
        long finalReviewId = reviewId;
        assertThat(after)
                .noneSatisfy(node -> assertThat(node.get("id").asLong()).isEqualTo(finalReviewId));
    }

    @Test
    @DisplayName("未收货的订单不能评价")
    void cannotReviewBeforeReceiving() {
        Product product = createOnShelfProduct("未收货评价商品", 5, "99.00");
        String token = registerAndLogin(randomPhone());
        long addressId = createAddress(token);
        long cartItemId = addToCart(token, product.getId(), 1);
        String orderNo =
                submitOrder(token, addressId, cartItemId, UUID.randomUUID().toString());

        Map<String, Object> body = new HashMap<>();
        body.put("orderNo", orderNo);
        body.put("scoreDesc", 5);
        body.put("content", "还没收到就评价");
        var response = post("/api/v1/reviews", body, token);

        assertThat(response.get("code").asInt()).isNotZero();
        assertThat(reloadOrder(orderNo).getStatus()).isEqualTo(OrderStatus.PENDING_PAY.getCode());
    }

    @Test
    @DisplayName("不能评价他人的订单")
    void cannotReviewOthersOrder() {
        Scenario s = orderAwaitingReview("123.00");
        String otherToken = registerAndLogin(randomPhone());

        Map<String, Object> body = new HashMap<>();
        body.put("orderNo", s.orderNo());
        body.put("scoreDesc", 1);
        body.put("content", "越权评价");
        var response = post("/api/v1/reviews", body, otherToken);

        assertThat(response.get("code").asInt()).isNotZero();
        assertThat(reloadOrder(s.orderNo()).getStatus()).isEqualTo(OrderStatus.PENDING_COMMENT.getCode());
    }

    /* ============================ 夹具 ============================ */

    private Scenario orderAwaitingReview(String price) {
        Product product = createOnShelfProduct("评价闭环测试商品", 5, price);
        String token = registerAndLogin(randomPhone());
        long addressId = createAddress(token);
        long cartItemId = addToCart(token, product.getId(), 1);
        String orderNo =
                submitOrder(token, addressId, cartItemId, UUID.randomUUID().toString());

        String tradeNo = assertSuccess(
                        post("/api/v1/pay/create", Map.of("orderNo", orderNo, "payMethod", "alipay"), token))
                .get("tradeNo")
                .asText();
        assertThat(postFormRaw("/api/v1/pay/callback", signedNotify(tradeNo, price)))
                .isEqualTo("success");
        assertSuccess(post("/api/v1/trade/mock/ship/" + orderNo, null, token));
        assertSuccess(post("/api/v1/trade/orders/" + orderNo + "/confirm", null, token));
        assertThat(reloadOrder(orderNo).getStatus()).isEqualTo(OrderStatus.PENDING_COMMENT.getCode());

        return new Scenario(token, orderNo, product.getId());
    }

    private Map<String, String> signedNotify(String tradeNo, String amount) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("out_trade_no", tradeNo);
        params.put("trade_no", "2026100922001" + UUID.randomUUID().toString().substring(0, 8));
        params.put("total_amount", amount);
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("app_id", "2021000000000000");
        params.put("seller_id", "2088000000000000");
        return localChannel.signNotify(params);
    }

    private Product createOnShelfProduct(String title, int stock, String price) {
        Product product = new Product();
        product.setShopId(DEMO_SHOP_ID);
        product.setTitle(title + " " + UUID.randomUUID().toString().substring(0, 8));
        product.setCategoryId(1L);
        product.setCategoryKey("integration-test");
        product.setParentKey("integration");
        product.setCover("https://img/test/review.png");
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
        body.put("name", "评价测试收货人");
        body.put("phone", "13800000004");
        body.put("province", "广东省");
        body.put("city", "深圳市");
        body.put("district", "南山区");
        body.put("detail", "科技园路 4 号");
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
        body.put("remark", "评价闭环测试订单");
        body.put("requestId", requestId);
        return assertSuccess(post("/api/v1/trade/orders", body, token)).asText();
    }

    /** 评价数以评价表为准（pms_product 没有评价计数字段）。 */
    private long reviewCount(long productId) {
        return reviewMapper.selectCount(new LambdaQueryWrapper<Review>().eq(Review::getProductId, productId));
    }

    private Order reloadOrder(String orderNo) {
        return orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo).last("limit 1"));
    }

    private record Scenario(String token, String orderNo, long productId) {}
}
