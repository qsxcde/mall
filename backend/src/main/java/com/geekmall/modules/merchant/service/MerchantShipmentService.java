package com.geekmall.modules.merchant.service;

import com.geekmall.modules.merchant.dto.MerchantShipmentQueryDTO;
import com.geekmall.modules.merchant.dto.OrderBatchDTO;
import com.geekmall.modules.merchant.vo.MerchantPageVO;
import com.geekmall.modules.merchant.vo.MerchantShipmentVO;

import java.util.List;
import java.util.Map;

/**
 * 商家端发货中心服务。
 */
public interface MerchantShipmentService {

    /** 发货单分页。 */
    MerchantPageVO<MerchantShipmentVO> page(MerchantShipmentQueryDTO query);

    /** 快递公司 / 发货仓下拉。 */
    Map<String, Object> filters();

    /** 打单：待打单 → 已打单，生成运单号。 */
    int print(List<String> ids);

    /** 确认发货：已打单 → 已发货。 */
    int deliver(OrderBatchDTO dto);
}
