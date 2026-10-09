/**
 * 支付域：收银台、支付单、渠道回调、退款。
 *
 * <p>已实现：</p>
 * <ul>
 *     <li>{@code entity.PaymentRecord}：支付单，trade_no 唯一索引，另落渠道单号 / 二维码 / 退款摘要</li>
 *     <li>{@code entity.PaymentRefundRecord}：退款单（refund_no 唯一，作为渠道 out_request_no）</li>
 *     <li>{@code channel.PaymentChannelClient}：渠道抽象（预下单 / 查单 / 关单 / 退款 / 验签）。
 *         本地渠道 {@code LocalSandboxChannel}（默认，自签自发，零配置可启动）与
 *         支付宝沙箱 {@code AlipaySandboxChannel} 通过 {@code mall.payment.channel} 切换</li>
 *     <li>{@code support.AlipaySigner}：手写 RSA2 签名 / 验签（纯 JDK，支持 PKCS#8 与 PKCS#1 私钥）</li>
 *     <li>{@code service.PaymentService}：create 创建支付单（金额取自订单、复用未过期待支付单）、
 *         query 供前端轮询、mockPay 模拟付款 / 主动查单、handleNotify 处理渠道通知、
 *         compensatePending 主动查单补偿</li>
 *     <li>{@code service.PaymentRefundService}：退款编排（refund_status CAS + refund_no 唯一索引双闸门）</li>
 *     <li>{@code controller.PaymentController}：/api/v1/pay/**，其中 /callback 在白名单内</li>
 * </ul>
 *
 * <p>回调的四条铁律（代码中已体现）：</p>
 * <ol>
 *     <li><b>验签</b>：{@code PaymentChannelClient.verifyNotify} 校验渠道签名与应用归属
 *         （支付宝 app_id / seller_id），失败应答 {@code failure}。</li>
 *     <li><b>幂等</b>：{@code pay_payment_record} 的 {@code status != 1} 条件更新是「只落账一次」的
 *         唯一判定，回调与主动查单都收敛到它；重复通知应答 {@code success} 直接返回。</li>
 *     <li><b>校验</b>：金额必须与支付单一致；应答体必须是裸文本
 *         {@code success} / {@code failure}（返回 JSON 会被渠道判为失败并无限重试），
 *         且处理过程<b>永不向外抛异常</b>。</li>
 *     <li><b>驱动订单</b>：{@code TradeService.applyPayment} 落账；若订单已关闭
 *         （超时 / 用户取消 / 商家关闭），转 {@code PaymentRefundService} 退款 —— 资金必须有出路。</li>
 * </ol>
 *
 * <p>待补充：真实渠道的响应验签（支付宝响应签的是响应节点原始 JSON 子串，
 * 反序列化再序列化会破坏字节一致性）；部分退款（当前只做全额退款，refund_no 因此可确定化）。</p>
 *
 * <p>对应前端：PaymentView。</p>
 */
package com.geekmall.modules.payment;
