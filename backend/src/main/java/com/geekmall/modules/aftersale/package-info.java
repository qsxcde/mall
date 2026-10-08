/**
 * 售后域：申请售后、售后列表、售后详情、取消申请。
 *
 * <p>已实现：</p>
 * <ul>
 *     <li>{@code entity.AfterSale} + {@code mapper.AfterSaleMapper}：oms_aftersale，逻辑删除</li>
 *     <li>{@code service.AfterSaleService}：
 *         <ul>
 *             <li>apply：复用交易域 {@code OrderVO.canAfterSale} 判断可申请状态，
 *                 并拦截「同一订单已有处理中售后」；退款/退货按订单实付金额算，换货/维修金额为 0</li>
 *             <li>list：用 {@code OrderService.firstItemTitles} 一次性取回商品名，避免 N+1</li>
 *             <li>detail：按状态生成处理进度时间轴</li>
 *             <li>cancel：仅「处理中」可取消</li>
 *         </ul>
 *     </li>
 *     <li>{@code controller.AfterSaleController} → /api/v1/aftersales/**</li>
 * </ul>
 *
 * <p>设计要点：订单归属与状态判断都交给交易域，售后域不重复实现订单状态机。</p>
 *
 * <p>待实现：真实退款调用、售后凭证图片（依赖 content 域上传接口）、
 * 售后退回商品后回滚库存。（商家审核/驳回已由商家域实现，见
 * {@code com.geekmall.modules.merchant.service.impl.MerchantAftersaleServiceImpl}。）</p>
 *
 * <p>对应前端：aftersale/ApplyView、aftersale/DetailView、UserCenterView 售后面板。</p>
 */
package com.geekmall.modules.aftersale;
