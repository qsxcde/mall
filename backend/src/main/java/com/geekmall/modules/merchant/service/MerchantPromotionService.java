package com.geekmall.modules.merchant.service;

import com.geekmall.modules.merchant.dto.MerchantPromotionQueryDTO;
import com.geekmall.modules.merchant.vo.MerchantCouponVO;
import com.geekmall.modules.merchant.vo.MerchantPageVO;
import com.geekmall.modules.merchant.vo.MerchantPromotionVO;

import java.util.List;

/**
 * 商家端营销中心服务。
 */
public interface MerchantPromotionService {

    /** 营销活动分页。 */
    MerchantPageVO<MerchantPromotionVO> page(MerchantPromotionQueryDTO query);

    /** 推广渠道效果。 */
    List<java.util.Map<String, Object>> channels();

    /** 店铺优惠券列表。 */
    List<MerchantCouponVO> coupons();
}
