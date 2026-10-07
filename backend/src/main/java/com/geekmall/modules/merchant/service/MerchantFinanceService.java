package com.geekmall.modules.merchant.service;

import com.geekmall.modules.merchant.dto.MerchantFinanceQueryDTO;
import com.geekmall.modules.merchant.dto.WithdrawDTO;
import com.geekmall.modules.merchant.vo.MerchantFundFlowVO;
import com.geekmall.modules.merchant.vo.MerchantPageVO;
import com.geekmall.modules.merchant.vo.MerchantSettlementVO;

import java.util.Map;

/**
 * 商家端财务结算服务。
 */
public interface MerchantFinanceService {

    /** 资金总览。 */
    Map<String, Object> fundSummary();

    /** 结算单分页（含趋势 / 扣费构成）。 */
    MerchantPageVO<MerchantSettlementVO> settlementPage(MerchantFinanceQueryDTO query);

    /** 资金流水分页。 */
    MerchantPageVO<MerchantFundFlowVO> fundFlow(MerchantFinanceQueryDTO query);

    /** 结算单明细（抽样订单）。 */
    Map<String, Object> settleOrders(String settlementId);

    /** 提现申请：返回手续费与到账金额。 */
    Map<String, Object> withdraw(WithdrawDTO dto);
}
