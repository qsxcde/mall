package com.geekmall.modules.merchant.service;

import com.geekmall.modules.merchant.dto.MerchantReviewQueryDTO;
import com.geekmall.modules.merchant.dto.ReviewReplyDTO;
import com.geekmall.modules.merchant.vo.MerchantPageVO;
import com.geekmall.modules.merchant.vo.MerchantReviewVO;

import java.util.List;
import java.util.Map;

/**
 * 商家端评价管理服务。
 */
public interface MerchantReviewService {

    /** 评价分页。 */
    MerchantPageVO<MerchantReviewVO> page(MerchantReviewQueryDTO query);

    /** 店铺 DSR 与评价标签。 */
    Map<String, Object> summary();

    /** 回复评价。 */
    void reply(ReviewReplyDTO dto);

    /** 忽略评价。 */
    int ignore(List<String> ids);
}
