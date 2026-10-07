/**
 * 支付域：收银台、支付单、渠道回调。
 *
 * <p>已实现：</p>
 * <ul>
 *     <li>{@code entity.PaymentRecord}：支付单，trade_no 唯一索引</li>
 *     <li>{@code service.PaymentService}：create 创建支付单（金额取自订单，禁止信任客户端）、
 *         query 供前端轮询、mockPay 演示「我已支付」、handleCallback 处理渠道回调</li>
 *     <li>{@code controller.PaymentController}：/api/v1/pay/**，其中 /callback 在白名单内</li>
 * </ul>
 *
 * <p>回调三条铁律（代码中已体现）：</p>
 * <ol>
 *     <li><b>幂等</b>：tradeNo 唯一索引 + 已成功直接返回，渠道重复通知不产生副作用。</li>
 *     <li><b>校验</b>：回调金额、订单号必须与支付单一致；接入真实渠道后再补签名校验。</li>
 *     <li><b>驱动订单</b>：成功后调用交易域 markPaid，把订单从「待付款」推进到「待发货」。</li>
 * </ol>
 *
 * <p>待补充：渠道 SDK 接入与验签、订单已关闭时转退款、支付超时自动关闭支付单。</p>
 *
 * <p>对应前端：PaymentView。</p>
 */
package com.geekmall.modules.payment;
