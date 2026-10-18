package com.geekmall.modules.marketing.service;

import com.geekmall.modules.marketing.vo.CouponTemplateVO;
import com.geekmall.modules.marketing.vo.UserCouponVO;
import java.math.BigDecimal;
import java.util.List;

/**
 * 优惠券服务：领券中心、我的优惠券、结算抵扣与核销。
 */
public interface CouponService {

    /** 领券中心券模板列表，type 为 null 时返回全部；userId 可为空（匿名浏览）。 */
    List<CouponTemplateVO> listTemplates(String type, Long userId);

    /** 领取优惠券，重复领取或已抢光会抛业务异常。 */
    void claim(Long userId, Long templateId);

    /** 我的优惠券，status 为 null 时返回全部。 */
    List<UserCouponVO> myCoupons(Long userId, Integer status);

    /**
     * 结算页可用券列表：未使用、未过期且满足门槛的券。
     * 不满足门槛的券也会返回，但 usable=false 并给出原因，便于前端置灰展示。
     */
    List<UserCouponVO> listForCheckout(Long userId, BigDecimal goodsAmount);

    /** 校验券可用并返回，不可用直接抛业务异常。 */
    UserCouponVO requireUsable(Long userId, Long userCouponId, BigDecimal goodsAmount);

    /** 计算商品抵扣金额（免运费券返回 0，运费由调用方置零）。 */
    BigDecimal calcGoodsDiscount(UserCouponVO coupon, BigDecimal goodsAmount);

    /** 核销：标记为已使用并绑定订单号。 */
    void markUsed(Long userId, Long userCouponId, String orderNo);

    /** 订单取消时回退券到未使用状态。 */
    void releaseByOrderNo(String orderNo);

    /** 为演示账号发放几张券，便于结算页有券可选。 */
    void grantDemoCoupons(Long userId);

    /**
     * 全部优惠券模板（商家端营销页展示用）。
     *
     * <p>返回实体而非 VO：商家侧有自己的展示模型（{@code MerchantCouponVO}），
     * 由营销域提供数据、商家域负责转换，避免营销域为了商家展示而绑死一个 VO。</p>
     */
    java.util.List<com.geekmall.modules.marketing.entity.CouponTemplate> allTemplates();
}
