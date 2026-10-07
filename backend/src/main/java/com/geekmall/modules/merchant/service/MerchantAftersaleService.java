package com.geekmall.modules.merchant.service;

import com.geekmall.modules.merchant.dto.AftersaleResolveDTO;
import com.geekmall.modules.merchant.dto.MerchantAftersaleQueryDTO;
import com.geekmall.modules.merchant.vo.MerchantAftersaleVO;
import com.geekmall.modules.merchant.vo.MerchantPageVO;

/**
 * 商家端售后管理服务。
 */
public interface MerchantAftersaleService {

    /** 售后工单分页。 */
    MerchantPageVO<MerchantAftersaleVO> page(MerchantAftersaleQueryDTO query);

    /** 工单详情。 */
    MerchantAftersaleVO detail(String id);

    /** 处理工单。 */
    int resolve(AftersaleResolveDTO dto);
}
