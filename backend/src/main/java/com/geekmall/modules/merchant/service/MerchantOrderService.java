package com.geekmall.modules.merchant.service;

import com.geekmall.modules.merchant.dto.MerchantOrderQueryDTO;
import com.geekmall.modules.merchant.dto.OrderBatchDTO;
import com.geekmall.modules.merchant.dto.OrderNoteDTO;
import com.geekmall.modules.merchant.vo.MerchantOrderVO;
import com.geekmall.modules.merchant.vo.MerchantPageVO;

/**
 * 商家端订单管理服务。
 */
public interface MerchantOrderService {

    /** 订单分页（含 Tab 计数与统计条）。 */
    MerchantPageVO<MerchantOrderVO> page(MerchantOrderQueryDTO query);

    /** 订单详情。 */
    MerchantOrderVO detail(String orderNo);

    /** 发货：待发货 → 待收货，并写入快递公司与运单号。 */
    int ship(OrderBatchDTO dto);

    /** 关闭订单。 */
    int close(OrderBatchDTO dto);

    /** 保存商家备注。 */
    void saveNote(OrderNoteDTO dto);
}
