/**
 * 交易域：结算试算、下单、订单查询、订单状态机、物流、超时关闭。
 *
 * <p>已实现：</p>
 * <ul>
 *     <li>{@code entity}：Order / OrderItem / OrderStatusLog</li>
 *     <li>{@code service.OrderStateMachine}：唯一的状态流转入口，校验
 *         {@link com.geekmall.common.enums.OrderStatus#canTransferTo} 并写状态日志、补时间戳</li>
 *     <li>{@code service.TradeService}：preOrder 试算、submit 幂等下单（扣库存 + 核销券 + 清购物车同事务）、
 *         cancel / confirmReceipt / remindDelivery、applyPayment（供支付域回调与主动查单落账，
 *         订单已关闭时返回 {@code ORDER_CLOSED} 而不是抛异常，交由支付域转退款）、closeExpiredOrders</li>
 *     <li>{@code service.OrderService}：订单分页（状态 + 关键词）、状态计数、详情（含时间轴与物流摘要）、物流跟踪</li>
 *     <li>{@code job.OrderTimeoutJob}：定时关闭超时未支付订单，ShedLock 保证多实例只跑一次</li>
 *     <li>{@code controller}：TradeController(/api/v1/trade)、OrderController(/api/v1/orders)</li>
 * </ul>
 *
 * <p>关键设计：</p>
 * <ol>
 *     <li>下单幂等：客户端传 requestId，Redis 占位 + 结果缓存，失败释放占位锁。</li>
 *     <li>防超卖：{@code UPDATE ... WHERE stock >= qty} 由数据库保证，受影响行数为 0 即失败回滚。</li>
 *     <li>金额一律 BigDecimal，且以下单瞬间的商品价格重新计价，不信任购物车快照与客户端。</li>
 *     <li>按钮可用性（canCancel/canPay/...）由后端按状态机算出，前端不重复实现规则。</li>
 * </ol>
 *
 * <p>待补充：真实物流轨迹接入、订单超时改用延迟队列。（发货后台已由商家域实现，见
 * {@code com.geekmall.modules.merchant.service.impl.MerchantShipmentServiceImpl}。）</p>
 *
 * <p>对应前端：CheckoutView、OrdersView、OrderDetailView、LogisticsView。</p>
 */
package com.geekmall.modules.trade;
