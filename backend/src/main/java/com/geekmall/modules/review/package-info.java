/**
 * 评价域：发表评价、我的评价、删除评价、商品评价列表。
 *
 * <p>已实现：</p>
 * <ul>
 *     <li>{@code entity.Review} + {@code mapper.ReviewMapper}：pms_review，支持逻辑删除</li>
 *     <li>{@code service.ReviewService}：
 *         <ul>
 *             <li>submit：先借用交易域订单详情完成「归属 + 状态」校验（仅待评价可评），
 *                 再按订单内商品逐个生成评价，最后驱动订单流转到「已完成」——
 *                 这补上了订单状态机的最后一段</li>
 *             <li>myReviews / delete：我的评价与逻辑删除，删除前校验归属防越权</li>
 *             <li>productReviews：商品评价分页，匿名可访问</li>
 *         </ul>
 *     </li>
 *     <li>{@code controller.ReviewController}：
 *         POST /api/v1/reviews、GET/DELETE /api/v1/user/reviews、
 *         GET /api/v1/products/{id}/reviews</li>
 * </ul>
 *
 * <p>设计要点：</p>
 * <ol>
 *     <li>同一订单重复提交评价会被 {@code orderNo} 计数拦截。</li>
 *     <li>匿名评价不查询真实昵称，避免逐个泄露用户信息。</li>
 *     <li>昵称通过 {@code UserService.nicknames} 批量获取，不让评价域直接读用户表。</li>
 * </ol>
 *
 * <p>待实现：追评、图片上传（依赖 MinIO，见 content 域）、商家回复、评分统计与商品评分回写。</p>
 *
 * <p>对应前端：ReviewView、MyReviewsView、ProductView 的评价 Tab。</p>
 */
package com.geekmall.modules.review;
