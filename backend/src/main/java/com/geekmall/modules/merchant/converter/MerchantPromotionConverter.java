package com.geekmall.modules.merchant.converter;

import com.geekmall.modules.merchant.entity.Promotion;
import com.geekmall.modules.merchant.vo.MerchantCouponVO;
import com.geekmall.modules.merchant.vo.MerchantPromotionVO;
import com.geekmall.modules.marketing.entity.CouponTemplate;

import java.math.BigDecimal;

/**
 * 商家端营销对象转换。
 */
public final class MerchantPromotionConverter {

    private MerchantPromotionConverter() {
    }

    public static MerchantPromotionVO toVO(Promotion promotion) {
        if (promotion == null) {
            return null;
        }
        MerchantPromotionVO vo = new MerchantPromotionVO();
        vo.setId("PM" + (2026100500L + promotion.getId() * 4));
        vo.setName(promotion.getName());
        vo.setType(promotion.getType());
        vo.setStatus(promotion.getStatus());
        vo.setStartAt(promotion.getStartAt());
        vo.setEndAt(promotion.getEndAt());
        vo.setBudget(promotion.getBudget());
        vo.setCost(promotion.getCost());
        vo.setRoi(promotion.getRoi());
        vo.setGmv(promotion.getGmv());
        vo.setSold(promotion.getSold());
        vo.setJoined(promotion.getJoined());
        return vo;
    }

    public static MerchantCouponVO toCoupon(CouponTemplate template) {
        if (template == null) {
            return null;
        }
        MerchantCouponVO vo = new MerchantCouponVO();
        vo.setId("CP" + template.getId());
        vo.setName(template.getName());
        vo.setThreshold(template.getThreshold());
        vo.setAmount(template.getAmount());
        int total = template.getTotal() == null ? 0 : template.getTotal();
        int stock = template.getStock() == null ? 0 : template.getStock();
        vo.setTotal(total);
        vo.setTaken(Math.max(total - stock, 0));
        // 暂无核销明细表，核销数固定为 0
        vo.setUsed(0);
        vo.setStatus(template.getStatus() != null && template.getStatus() == 1 ? "running" : "paused");
        vo.setEndAt(template.getValidTo());
        return vo;
    }

    /** 空金额兜底，避免前端拿到 null 参与运算。 */
    public static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
