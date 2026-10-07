package com.geekmall.modules.trade.service;

import com.geekmall.modules.trade.dto.PreOrderDTO;
import com.geekmall.modules.trade.dto.SubmitOrderDTO;
import com.geekmall.modules.trade.vo.PreOrderVO;

/**
 * 交易服务：结算试算、下单、订单操作、超时关闭。
 */
public interface TradeService {

    /** 结算页试算：商品、金额、地址、配送方式、可用券、支付方式。 */
    PreOrderVO preOrder(Long userId, PreOrderDTO dto);

    /**
     * 提交订单。
     *
     * <p>幂等下单：写订单 + 明细 + 扣库存 + 核销券 + 清购物车，全部在同一事务内。</p>
     *
     * @return 订单号
     */
    String submit(Long userId, SubmitOrderDTO dto);

    /** 用户取消订单（待付款 / 待发货），同时回滚库存与优惠券。 */
    void cancel(Long userId, String orderNo, String reason);

    /** 确认收货：待收货 → 待评价。 */
    void confirmReceipt(Long userId, String orderNo);

    /** 提醒发货（不改变状态，仅记录日志）。 */
    void remindDelivery(Long userId, String orderNo);

    /**
     * 发货：待发货 → 待收货。
     *
     * <p>真实项目中这是<b>商家后台</b>的操作（需 ROLE_ADMIN），骨架阶段不提供后台，
     * 仅由 dev 环境下的模拟接口调用，便于前端把「确认收货 → 评价」链路跑通。</p>
     */
    void ship(String orderNo);

    /** 支付成功回调：待付款 → 待发货。供支付域调用。 */
    void markPaid(String orderNo, String tradeNo, String payMethod);

    /** 发起支付前校验订单可支付，并返回应付金额；不可支付时抛业务异常。供支付域调用。 */
    java.math.BigDecimal requirePayableAmount(Long userId, String orderNo);

    /**
     * 按指定单价创建订单，供秒杀等特殊通道使用（单价由营销域校验后传入，不经购物车）。
     *
     * @return 订单号
     */
    String createOrderWithFixedPrice(Long userId, Long addressId, Long productId,
                                     java.math.BigDecimal unitPrice, int qty, String remark);

    /** 评价完成：待评价 → 已完成。供评价域调用。 */
    void markReviewed(Long userId, String orderNo);

    /** 关闭超时未支付订单，供定时任务调用。 */
    int closeExpiredOrders(int batchSize);
}
