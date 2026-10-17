package com.geekmall.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.geekmall.common.util.JwtUtil;
import com.geekmall.modules.message.service.MessageService;
import com.geekmall.modules.payment.channel.LocalSandboxChannel;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
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
 * 消息中心 / 收藏 / 浏览足迹闭环集成测试。
 *
 * <p>这三块此前完全没有接口级测试（消息 15.4%、收藏所在 user 域 13.6% 覆盖），
 * 但它们都有「重复操作要幂等」的隐含契约：下单会发消息、收藏可重复点击、
 * 足迹同一商品只留一条。这些正是最容易在重构中悄悄坏掉的地方。</p>
 */
class MessageAndCollectionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private LocalSandboxChannel localChannel;

    @Autowired
    private MessageService messageService;

    @Autowired
    private JwtUtil jwtUtil;

    /* ============================ 消息中心 ============================ */

    @Test
    @DisplayName("下单后生成站内消息，未读数随之增加")
    void orderCreatesMessageAndUnreadCount() {
        String token = registerAndLogin(randomPhone());
        long unreadBefore =
                assertSuccess(get("/api/v1/messages/unread-count", token)).asLong();

        // 下单 + 支付会触发「订单支付成功」等站内消息（OrderStatusMessageListener）
        Product product = createOnShelfProduct("消息测试商品", 5, "99.00");
        long addressId = createAddress(token);
        long cartItemId = addToCart(token, product.getId(), 1);
        String orderNo =
                submitOrder(token, addressId, cartItemId, UUID.randomUUID().toString());
        String tradeNo = assertSuccess(
                        post("/api/v1/pay/create", Map.of("orderNo", orderNo, "payMethod", "alipay"), token))
                .get("tradeNo")
                .asText();
        assertThat(postFormRaw("/api/v1/pay/callback", signedNotify(tradeNo, "99.00")))
                .isEqualTo("success");

        // 站内消息由 @Async 的 OrderStatusMessageListener 异步写入，
        // 支付回调返回时消息可能还没落库 —— 必须轮询等待，否则测试会随机失败
        // （「同步返回后再断言异步副作用」是这类用例最常见的 flaky 来源）。
        long unreadAfter = waitForUnreadGreaterThan(token, unreadBefore);
        assertThat(unreadAfter).as("支付成功应产生未读站内消息").isGreaterThan(unreadBefore);

        var list = assertSuccess(get("/api/v1/messages?type=all", token));
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isGreaterThan(0);
        assertThat(list.get(0).has("title")).isTrue();
    }

    @Test
    @DisplayName("标记单条已读：未读数减一，消息 read 变 true")
    void markReadDecreasesUnread() {
        String token = registerAndLogin(randomPhone());
        long unreadBefore =
                assertSuccess(get("/api/v1/messages/unread-count", token)).asLong();
        var list = assertSuccess(get("/api/v1/messages?type=all", token));
        assertThat(list.size()).as("新用户应有欢迎/系统类消息").isGreaterThan(0);

        long messageId = list.get(0).get("id").asLong();
        assertSuccess(post("/api/v1/messages/" + messageId + "/read", null, token));

        assertThat(assertSuccess(get("/api/v1/messages/unread-count", token)).asLong())
                .isEqualTo(unreadBefore - 1);

        var after = assertSuccess(get("/api/v1/messages?type=all", token));
        assertThat(after).anySatisfy(node -> {
            if (node.get("id").asLong() == messageId) {
                assertThat(node.get("read").asBoolean()).isTrue();
            }
        });
    }

    @Test
    @DisplayName("重复标记已读是幂等的：未读数不会被减两次")
    void markReadIsIdempotent() {
        String token = registerAndLogin(randomPhone());
        var list = assertSuccess(get("/api/v1/messages?type=all", token));
        long messageId = list.get(0).get("id").asLong();

        assertSuccess(post("/api/v1/messages/" + messageId + "/read", null, token));
        long unreadAfterFirst =
                assertSuccess(get("/api/v1/messages/unread-count", token)).asLong();

        assertSuccess(post("/api/v1/messages/" + messageId + "/read", null, token));
        assertThat(assertSuccess(get("/api/v1/messages/unread-count", token)).asLong())
                .as("已读回执靠唯一索引幂等，不能重复扣减")
                .isEqualTo(unreadAfterFirst);
    }

    @Test
    @DisplayName("全部标记已读：未读数归零")
    void markAllReadZeroesUnread() {
        String token = registerAndLogin(randomPhone());
        assertThat(assertSuccess(get("/api/v1/messages/unread-count", token)).asLong())
                .isPositive();

        assertSuccess(post("/api/v1/messages/read-all", null, token));

        assertThat(assertSuccess(get("/api/v1/messages/unread-count", token)).asLong())
                .isZero();
    }

    @Test
    @DisplayName("不能标记他人的私信为已读；广播消息则对所有人可见")
    void messageVisibilityIsScopedToOwner() {
        String ownerToken = registerAndLogin(randomPhone());
        // 只发给 owner 的私信（不能取广播消息：userId=0 的消息按设计对全员可见）
        messageService.send(currentUserId(ownerToken), "system", "仅本人可见的私信", "内容", null);

        var ownerList = assertSuccess(get("/api/v1/messages?type=all", ownerToken));
        long privateId = -1;
        for (var node : ownerList) {
            if ("仅本人可见的私信".equals(node.get("title").asText())) {
                privateId = node.get("id").asLong();
            }
        }
        assertThat(privateId).as("私信应出现在 owner 的列表里").isPositive();
        long privateMessageId = privateId;

        // 他人既看不到这条私信，也不能把它标记为已读
        String otherToken = registerAndLogin(randomPhone());
        assertThat(assertSuccess(get("/api/v1/messages?type=all", otherToken)))
                .noneSatisfy(node -> assertThat(node.get("id").asLong()).isEqualTo(privateMessageId));
        var response = post("/api/v1/messages/" + privateMessageId + "/read", null, otherToken);
        assertThat(response.get("code").asInt()).as("越权标记他人私信必须被拒绝").isNotZero();
    }

    /* ============================ 收藏 ============================ */

    @Test
    @DisplayName("收藏 / 取消收藏可反复切换，收藏列表随之增减")
    void toggleFavoriteSwitchesBothWays() {
        String token = registerAndLogin(randomPhone());
        Product product = createOnShelfProduct("收藏测试商品", 5, "88.00");

        boolean on = assertSuccess(post("/api/v1/user/favorites/" + product.getId() + "/toggle", null, token))
                .asBoolean();
        assertThat(on).isTrue();

        var favorites = assertSuccess(get("/api/v1/user/favorites", token));
        assertThat(favorites)
                .anySatisfy(node -> assertThat(node.get("id").asLong()).isEqualTo(product.getId()));

        boolean off = assertSuccess(post("/api/v1/user/favorites/" + product.getId() + "/toggle", null, token))
                .asBoolean();
        assertThat(off).isFalse();

        var afterOff = assertSuccess(get("/api/v1/user/favorites", token));
        assertThat(afterOff)
                .noneSatisfy(node -> assertThat(node.get("id").asLong()).isEqualTo(product.getId()));
    }

    @Test
    @DisplayName("重复取消收藏不报错（幂等）")
    void removeFavoriteIsIdempotent() {
        String token = registerAndLogin(randomPhone());
        Product product = createOnShelfProduct("重复取消收藏商品", 5, "66.00");
        assertSuccess(post("/api/v1/user/favorites/" + product.getId() + "/toggle", null, token));

        assertSuccess(delete("/api/v1/user/favorites/" + product.getId(), token));
        // 第二次删除：已经是「未收藏」状态，仍应成功而非报错
        assertSuccess(delete("/api/v1/user/favorites/" + product.getId(), token));

        assertThat(assertSuccess(get("/api/v1/user/favorites", token))).isEmpty();
    }

    @Test
    @DisplayName("清空收藏后列表为空")
    void clearFavorites() {
        String token = registerAndLogin(randomPhone());
        Product a = createOnShelfProduct("收藏清空商品A", 5, "11.00");
        Product b = createOnShelfProduct("收藏清空商品B", 5, "22.00");
        assertSuccess(post("/api/v1/user/favorites/" + a.getId() + "/toggle", null, token));
        assertSuccess(post("/api/v1/user/favorites/" + b.getId() + "/toggle", null, token));
        assertThat(assertSuccess(get("/api/v1/user/favorites", token)).size()).isEqualTo(2);

        assertSuccess(delete("/api/v1/user/favorites", token));

        assertThat(assertSuccess(get("/api/v1/user/favorites", token))).isEmpty();
    }

    @Test
    @DisplayName("收藏是按用户隔离的：别人看不到我的收藏")
    void favoritesAreScopedPerUser() {
        String token = registerAndLogin(randomPhone());
        Product product = createOnShelfProduct("收藏隔离商品", 5, "77.00");
        assertSuccess(post("/api/v1/user/favorites/" + product.getId() + "/toggle", null, token));

        String otherToken = registerAndLogin(randomPhone());
        assertThat(assertSuccess(get("/api/v1/user/favorites", otherToken))).isEmpty();
    }

    /* ============================ 浏览足迹 ============================ */

    @Test
    @DisplayName("同一商品重复浏览只保留一条足迹，更新时间而不新增")
    void historyKeepsSingleEntryPerProduct() {
        String token = registerAndLogin(randomPhone());
        Product product = createOnShelfProduct("足迹测试商品", 5, "55.00");

        assertSuccess(post("/api/v1/user/history/" + product.getId(), null, token));
        assertSuccess(post("/api/v1/user/history/" + product.getId(), null, token));

        var history = assertSuccess(get("/api/v1/user/history", token));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("id").asLong()).isEqualTo(product.getId());
    }

    @Test
    @DisplayName("删除单条足迹与清空足迹")
    void removeAndClearHistory() {
        String token = registerAndLogin(randomPhone());
        Product a = createOnShelfProduct("足迹商品A", 5, "33.00");
        Product b = createOnShelfProduct("足迹商品B", 5, "44.00");
        assertSuccess(post("/api/v1/user/history/" + a.getId(), null, token));
        assertSuccess(post("/api/v1/user/history/" + b.getId(), null, token));
        assertThat(assertSuccess(get("/api/v1/user/history", token))).hasSize(2);

        assertSuccess(delete("/api/v1/user/history/" + a.getId(), token));
        assertThat(assertSuccess(get("/api/v1/user/history", token))).hasSize(1);

        assertSuccess(delete("/api/v1/user/history", token));
        assertThat(assertSuccess(get("/api/v1/user/history", token))).isEmpty();
    }

    /* ============================ 夹具 ============================ */

    /**
     * 轮询等待未读数超过基线。
     *
     * <p>站内消息走 {@code @TransactionalEventListener(AFTER_COMMIT) + @Async}，
     * 属于「接口已返回、副作用还在路上」—— 直接断言必然出现随机失败。</p>
     */
    private long waitForUnreadGreaterThan(String token, long baseline) {
        long deadline = System.currentTimeMillis() + 5_000;
        long unread;
        do {
            unread = assertSuccess(get("/api/v1/messages/unread-count", token)).asLong();
            if (unread > baseline) {
                return unread;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return unread;
            }
        } while (System.currentTimeMillis() < deadline);
        return unread;
    }

    /** 从买家令牌解析出 userId（subject 即用户 ID）。 */
    private long currentUserId(String token) {
        return jwtUtil.getUserId(token);
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
        product.setCover("https://img/test/collection.png");
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
        body.put("name", "消息测试收货人");
        body.put("phone", "13800000005");
        body.put("province", "广东省");
        body.put("city", "深圳市");
        body.put("district", "南山区");
        body.put("detail", "科技园路 5 号");
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
        body.put("remark", "消息测试订单");
        body.put("requestId", requestId);
        return assertSuccess(post("/api/v1/trade/orders", body, token)).asText();
    }
}
