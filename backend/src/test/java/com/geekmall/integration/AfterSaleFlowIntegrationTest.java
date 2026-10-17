package com.geekmall.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.enums.OrderStatus;
import com.geekmall.modules.aftersale.entity.AfterSale;
import com.geekmall.modules.aftersale.enums.BuyerAfterSaleStatus;
import com.geekmall.modules.aftersale.mapper.AfterSaleMapper;
import com.geekmall.modules.payment.channel.LocalSandboxChannel;
import com.geekmall.modules.payment.entity.PaymentRecord;
import com.geekmall.modules.payment.entity.PaymentRefundRecord;
import com.geekmall.modules.payment.mapper.PaymentRecordMapper;
import com.geekmall.modules.payment.mapper.PaymentRefundRecordMapper;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 售后闭环集成测试。
 *
 * <p>覆盖的是「界面显示已退款、钱实际没动」这类<b>不会报错、只会造成资损</b>的缺陷：
 * 审批通过是否真的发起退款、退款金额是否可部分调整、买家看到的到底是「商家拒绝」
 * 还是「自己取消」、退货退款的资金在哪个节点释放。</p>
 *
 * <p>链路：下单 → 支付 → 发货 → 收货 → 申请售后 → 商家审批 → 校验资金与状态。</p>
 */
class AfterSaleFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private AfterSaleMapper afterSaleMapper;

    @Autowired
    private PaymentRecordMapper paymentRecordMapper;

    @Autowired
    private PaymentRefundRecordMapper refundRecordMapper;

    @Autowired
    private LocalSandboxChannel localChannel;

    /* ============================ 仅退款：审批即退款 ============================ */

    @Nested
    @DisplayName("仅退款（refund）")
    class PureRefund {

        @Test
        @DisplayName("商家同意后必须真实发起退款：退款单落库、支付单累计退款金额正确")
        void approveTriggersRealRefund() {
            Scenario s = completedOrder("188.00");

            long afterSaleId = applyAfterSale(s.token(), s.orderNo(), "refund");
            assertThat(refundCount(s.tradeNo())).as("申请阶段不应产生退款").isZero();

            approve(s, afterSaleId);

            // 售后既然显示「已完成」，资金就必须真的有出路
            PaymentRefundRecord refund = reloadRefund(s.tradeNo());
            assertThat(refund).as("同意仅退款后必须产生退款单").isNotNull();
            assertThat(refund.getAmount()).isEqualByComparingTo("188.00");
            assertThat(refund.getStatus()).isEqualTo(1);
            assertThat(refund.getRefundNo()).as("幂等键是售后单维度").isEqualTo("RFAS" + afterSaleId);

            PaymentRecord payment = reloadPayment(s.tradeNo());
            assertThat(payment.getRefundStatus()).isEqualTo(2);
            assertThat(payment.getRefundAmount()).as("支付单累计退款金额").isEqualByComparingTo("188.00");

            AfterSale record = afterSaleMapper.selectById(afterSaleId);
            assertThat(record.getStatus()).isEqualTo(1);
            assertThat(record.getMerchantStatus()).isEqualTo("done");
        }

        @Test
        @DisplayName("商家可调整退款金额（部分退款）：渠道只退调整后的金额")
        void approveWithPartialAmountRefundsAdjustedAmount() {
            Scenario s = completedOrder("300.00");
            long afterSaleId = applyAfterSale(s.token(), s.orderNo(), "refund");

            approve(s, afterSaleId, new BigDecimal("120.00"));

            PaymentRefundRecord refund = reloadRefund(s.tradeNo());
            assertThat(refund.getAmount()).isEqualByComparingTo("120.00");
            assertThat(reloadPayment(s.tradeNo()).getRefundAmount()).isEqualByComparingTo("120.00");
        }

        @Test
        @DisplayName("重复审批是幂等的：不会退第二笔")
        void doubleApproveRefundsOnce() {
            Scenario s = completedOrder("99.00");
            long afterSaleId = applyAfterSale(s.token(), s.orderNo(), "refund");
            approve(s, afterSaleId);

            // 第二次点击：状态已不是 pending，CAS 会拦下（接口层面报业务错误）
            Map<String, Object> body = new HashMap<>();
            body.put("ids", List.of(merchantId(afterSaleId)));
            body.put("action", "approve");
            var response = post("/api/v1/merchant/aftersale/resolve", body, loginMerchant(DEMO_MERCHANT, "123456"));
            assertThat(response.get("code").asInt()).isNotZero();

            assertThat(refundCount(s.tradeNo())).isEqualTo(1);
            assertThat(reloadPayment(s.tradeNo()).getRefundAmount()).isEqualByComparingTo("99.00");
        }
    }

    /* ============================ 退货退款：确认收货才退 ============================ */

    @Nested
    @DisplayName("退货退款（return）")
    class ReturnRefund {

        @Test
        @DisplayName("同意退货只改状态不退钱；买家寄回 + 商家确认收货后才退款")
        void refundOnlyAfterMerchantReceives() {
            Scenario s = completedOrder("520.00");
            long afterSaleId = applyAfterSale(s.token(), s.orderNo(), "return");

            // 1) 同意退货：此时商品还没寄回，退钱就等于白送
            approve(s, afterSaleId);
            assertThat(refundCount(s.tradeNo())).as("买家还没寄回，不能退款").isZero();
            assertThat(afterSaleMapper.selectById(afterSaleId).getMerchantStatus())
                    .isEqualTo("wait_return");

            // 2) 买家登记已寄回 → wait_receive
            assertSuccess(post("/api/v1/aftersales/" + afterSaleId + "/returned", null, s.token()));
            assertThat(afterSaleMapper.selectById(afterSaleId).getMerchantStatus())
                    .isEqualTo("wait_receive");
            assertThat(refundCount(s.tradeNo())).as("还没确认收货，仍不退款").isZero();

            // 3) 商家确认收货 → 触发退款
            resolve(s.token(), afterSaleId, "receive", Map.of());

            assertThat(refundCount(s.tradeNo())).isEqualTo(1);
            assertThat(reloadRefund(s.tradeNo()).getAmount()).isEqualByComparingTo("520.00");
            assertThat(afterSaleMapper.selectById(afterSaleId).getMerchantStatus())
                    .isEqualTo("done");
        }

        @Test
        @DisplayName("买家未登记寄回时，商家不能直接确认收货（防白送商品）")
        void receiveRequiresWaitReceiveStatus() {
            Scenario s = completedOrder("66.00");
            long afterSaleId = applyAfterSale(s.token(), s.orderNo(), "return");
            approve(s, afterSaleId);
            assertThat(afterSaleMapper.selectById(afterSaleId).getMerchantStatus())
                    .isEqualTo("wait_return");

            // 还停在 wait_return 时直接点「确认收货」：应被状态机拒绝
            var response = resolveRaw(s.token(), afterSaleId, "receive");
            assertThat(response.get("code").asInt())
                    .as("wait_return 不是 receive 的合法前置状态")
                    .isNotZero();
            assertThat(refundCount(s.tradeNo())).isZero();
        }

        @Test
        @DisplayName("非退货类工单不能登记寄回")
        void onlyReturnCanBeMarkedReturned() {
            Scenario s = completedOrder("45.00");
            long afterSaleId = applyAfterSale(s.token(), s.orderNo(), "refund");

            var response = post("/api/v1/aftersales/" + afterSaleId + "/returned", null, s.token());
            assertThat(response.get("code").asInt()).isNotZero();
        }
    }

    /* ============================ 拒绝：状态语义必须区分开 ============================ */

    @Nested
    @DisplayName("商家拒绝")
    class Rejected {

        @Test
        @DisplayName("拒绝不退款，且买家侧必须看到「已拒绝」而不是「已取消」")
        void rejectIsNotCanceledForBuyer() {
            Scenario s = completedOrder("259.00");
            long afterSaleId = applyAfterSale(s.token(), s.orderNo(), "refund");

            Map<String, Object> body = new HashMap<>();
            body.put("ids", List.of(merchantId(afterSaleId)));
            body.put("action", "reject");
            body.put("rejectReason", "商品已激活，不支持七天无理由");
            assertSuccess(post("/api/v1/merchant/aftersale/resolve", body, loginMerchant(DEMO_MERCHANT, "123456")));

            assertThat(refundCount(s.tradeNo())).as("拒绝不产生资金流").isZero();

            var detail = assertSuccess(get("/api/v1/aftersales/" + afterSaleId, s.token()));
            assertThat(detail.get("statusText").asText()).isEqualTo("已拒绝");
            assertThat(detail.get("merchantStatus").asText()).isEqualTo("rejected");
            assertThat(detail.get("rejectReason").asText()).isEqualTo("商品已激活，不支持七天无理由");
            assertThat(detail.get("canCancel").asBoolean()).isFalse();

            // 关键回归：拒绝时买家侧数字状态不能写成 2（=已取消），
            // 否则买家看到的会是自己从没做过的「取消」
            AfterSale record = afterSaleMapper.selectById(afterSaleId);
            assertThat(record.getStatus()).isNotEqualTo(2);
            assertThat(BuyerAfterSaleStatus.resolve(record)).isEqualTo(BuyerAfterSaleStatus.REJECTED);
        }

        @Test
        @DisplayName("被拒绝后买家不能再撤销（撤销 ≠ 拒绝）")
        void rejectedOrderCannotBeCanceled() {
            Scenario s = completedOrder("88.00");
            long afterSaleId = applyAfterSale(s.token(), s.orderNo(), "refund");
            Map<String, Object> body = new HashMap<>();
            body.put("ids", List.of(merchantId(afterSaleId)));
            body.put("action", "reject");
            body.put("rejectReason", "不符合售后条件");
            assertSuccess(post("/api/v1/merchant/aftersale/resolve", body, loginMerchant(DEMO_MERCHANT, "123456")));

            var response = post("/api/v1/aftersales/" + afterSaleId + "/cancel", Map.of(), s.token());
            assertThat(response.get("code").asInt()).isNotZero();
            assertThat(response.get("msg").asText()).contains("已拒绝");
        }
    }

    /* ============================ 买家撤销 vs 商家拒绝 ============================ */

    @Test
    @DisplayName("买家撤销后商家侧应显示 canceled（而非 wait_return），且不可再操作")
    void buyerCancelWinsOverMerchantStatus() {
        Scenario s = completedOrder("120.00");
        long afterSaleId = applyAfterSale(s.token(), s.orderNo(), "return");
        approve(s, afterSaleId);
        assertThat(afterSaleMapper.selectById(afterSaleId).getMerchantStatus()).isEqualTo("wait_return");

        assertSuccess(post("/api/v1/aftersales/" + afterSaleId + "/cancel", Map.of(), s.token()));

        var detail = assertSuccess(get("/api/v1/aftersales/" + afterSaleId, s.token()));
        assertThat(detail.get("statusText").asText()).isEqualTo("已取消");

        // 商家侧：买家已撤销的工单不能再被推进
        var merchantDetail = assertSuccess(get(
                "/api/v1/merchant/aftersale/detail?id=" + merchantId(afterSaleId),
                loginMerchant(DEMO_MERCHANT, "123456")));
        assertThat(merchantDetail.get("status").asText()).isEqualTo("canceled");
    }

    /* ============================ 不涉及退款的类型 ============================ */

    @Test
    @DisplayName("换货/维修不产生退款，金额为 0")
    void exchangeDoesNotRefund() {
        Scenario s = completedOrder("199.00");
        long afterSaleId = applyAfterSale(s.token(), s.orderNo(), "exchange");

        assertThat(afterSaleMapper.selectById(afterSaleId).getAmount()).isEqualByComparingTo("0");
        approve(s, afterSaleId);

        assertThat(refundCount(s.tradeNo())).as("换货不涉及资金流").isZero();
    }

    /* ============================ 申请侧的前置校验 ============================ */

    @Test
    @DisplayName("同一订单已有处理中售后时不允许重复申请")
    void duplicateApplyIsRejected() {
        Scenario s = completedOrder("150.00");
        applyAfterSale(s.token(), s.orderNo(), "refund");

        Map<String, Object> body = new HashMap<>();
        body.put("orderNo", s.orderNo());
        body.put("type", "refund");
        body.put("reason", "再次申请");
        var response = post("/api/v1/aftersales", body, s.token());

        assertThat(response.get("code").asInt()).isNotZero();
        assertThat(response.get("msg").asText()).contains("处理中");
    }

    @Test
    @DisplayName("未支付订单不可申请售后")
    void unpaidOrderCannotApply() {
        Product product = createOnShelfProduct("未支付售后商品", 5, "88.00");
        String token = registerAndLogin(randomPhone());
        long addressId = createAddress(token);
        long cartItemId = addToCart(token, product.getId(), 1);
        String orderNo =
                submitOrder(token, addressId, cartItemId, UUID.randomUUID().toString());

        Map<String, Object> body = new HashMap<>();
        body.put("orderNo", orderNo);
        body.put("type", "refund");
        body.put("reason", "还没付款就申请");
        var response = post("/api/v1/aftersales", body, token);

        assertThat(response.get("code").asInt()).isNotZero();
    }

    @Test
    @DisplayName("可以查看自己的售后列表；他人售后详情不可见")
    void listIsScopedToOwner() {
        Scenario s = completedOrder("77.00");
        long afterSaleId = applyAfterSale(s.token(), s.orderNo(), "refund");

        var list = assertSuccess(get("/api/v1/aftersales", s.token()));
        assertThat(list.isArray()).isTrue();
        assertThat(list).anySatisfy(node -> assertThat(node.get("id").asLong()).isEqualTo(afterSaleId));

        String otherToken = registerAndLogin(randomPhone());
        var response = get("/api/v1/aftersales/" + afterSaleId, otherToken);
        assertThat(response.get("code").asInt()).as("越权查看应报错").isNotZero();
    }

    /* ============================ 夹具 ============================ */

    /** 走完「下单 → 支付 → 发货 → 收货」，得到一个可申请售后的订单（待评价）。 */
    private Scenario completedOrder(String price) {
        Product product = createOnShelfProduct("售后闭环测试商品", 5, price);
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
        assertThat(reloadOrder(orderNo).getStatus()).isEqualTo(OrderStatus.PENDING_SHIP.getCode());

        assertSuccess(post("/api/v1/trade/mock/ship/" + orderNo, null, token));
        assertSuccess(post("/api/v1/trade/orders/" + orderNo + "/confirm", null, token));
        assertThat(reloadOrder(orderNo).getStatus()).isEqualTo(OrderStatus.PENDING_COMMENT.getCode());

        return new Scenario(token, orderNo, tradeNo);
    }

    private long applyAfterSale(String token, String orderNo, String type) {
        Map<String, Object> body = new HashMap<>();
        body.put("orderNo", orderNo);
        body.put("type", type);
        body.put("reason", "集成测试售后原因");
        body.put("content", "集成测试售后描述");
        return assertSuccess(post("/api/v1/aftersales", body, token)).asLong();
    }

    private void approve(Scenario s, long afterSaleId) {
        approve(s, afterSaleId, null);
    }

    private void approve(Scenario s, long afterSaleId, BigDecimal refundAmount) {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", List.of(merchantId(afterSaleId)));
        body.put("action", "approve");
        if (refundAmount != null) {
            body.put("refundAmount", refundAmount);
        }
        resolveRaw(s.token(), body);
    }

    private void resolve(String buyerToken, long afterSaleId, String action, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("ids", List.of(merchantId(afterSaleId)));
        body.put("action", action);
        resolveRaw(buyerToken, body);
    }

    private com.fasterxml.jackson.databind.JsonNode resolveRaw(String buyerToken, long afterSaleId, String action) {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", List.of(merchantId(afterSaleId)));
        body.put("action", action);
        return resolveRaw(buyerToken, body);
    }

    private com.fasterxml.jackson.databind.JsonNode resolveRaw(String buyerToken, Map<String, Object> body) {
        // 商家令牌与买家令牌是两套作用域，这里显式换取商家令牌
        String merchantToken = loginMerchant(DEMO_MERCHANT, "123456");
        return post("/api/v1/merchant/aftersale/resolve", body, merchantToken);
    }

    /** 主键 → 商家端对外工单号（与 MerchantAftersaleConverter 的规则一致）。 */
    private static String merchantId(long afterSaleId) {
        return "AS" + (2026100500L + afterSaleId * 3);
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
        product.setCover("https://img/test/aftersale.png");
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
        body.put("name", "售后测试收货人");
        body.put("phone", "13800000003");
        body.put("province", "广东省");
        body.put("city", "深圳市");
        body.put("district", "南山区");
        body.put("detail", "科技园路 3 号");
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
        body.put("remark", "售后闭环测试订单");
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
                .orderByDesc(PaymentRefundRecord::getId)
                .last("limit 1"));
    }

    private long refundCount(String tradeNo) {
        return refundRecordMapper.selectCount(
                new LambdaQueryWrapper<PaymentRefundRecord>().eq(PaymentRefundRecord::getTradeNo, tradeNo));
    }

    /** 一次完整下单场景的上下文。 */
    private record Scenario(String token, String orderNo, String tradeNo) {}
}
