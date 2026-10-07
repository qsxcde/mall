package com.geekmall.modules.merchant.converter;

import com.geekmall.modules.merchant.entity.FundFlow;
import com.geekmall.modules.merchant.entity.Settlement;
import com.geekmall.modules.merchant.vo.MerchantFundFlowVO;
import com.geekmall.modules.merchant.vo.MerchantSettlementVO;

/**
 * 商家端财务对象转换。
 */
public final class MerchantFinanceConverter {

    private MerchantFinanceConverter() {
    }

    public static MerchantSettlementVO toSettlement(Settlement settlement) {
        if (settlement == null) {
            return null;
        }
        MerchantSettlementVO vo = new MerchantSettlementVO();
        vo.setId(settlement.getSettleNo());
        vo.setRange(settlement.getRangeLabel());
        vo.setGmv(settlement.getGmv());
        vo.setCommission(settlement.getCommission());
        vo.setService(settlement.getService());
        vo.setRefund(settlement.getRefund());
        vo.setSettle(settlement.getSettle());
        vo.setRate(settlement.getRate());
        vo.setStatus(settlement.getStatus());
        return vo;
    }

    public static MerchantFundFlowVO toFlow(FundFlow flow) {
        if (flow == null) {
            return null;
        }
        MerchantFundFlowVO vo = new MerchantFundFlowVO();
        vo.setId("CF" + flow.getId());
        vo.setType(flow.getType());
        int direction = flow.getDirection() == null ? 1 : flow.getDirection();
        vo.setDirection(direction);
        vo.setAmount(flow.getAmount() == null ? null : flow.getAmount().multiply(java.math.BigDecimal.valueOf(direction)));
        vo.setRemark(flow.getTitle());
        vo.setAt(flow.getOccupyTime());
        applyLabel(vo);
        return vo;
    }

    private static void applyLabel(MerchantFundFlowVO vo) {
        switch (vo.getType()) {
            case "settle" -> {
                vo.setLabel("结算入账");
                vo.setTone("green");
            }
            case "withdraw" -> {
                vo.setLabel("提现到账");
                vo.setTone("brand");
            }
            case "commission" -> {
                vo.setLabel("平台佣金");
                vo.setTone("coral");
            }
            case "service" -> {
                vo.setLabel("支付服务费");
                vo.setTone("amber");
            }
            case "refund" -> {
                vo.setLabel("售后退款扣减");
                vo.setTone("coral");
            }
            default -> vo.setTone("brand");
        }
    }
}
