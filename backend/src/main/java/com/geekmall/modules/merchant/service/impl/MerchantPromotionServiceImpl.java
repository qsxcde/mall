package com.geekmall.modules.merchant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.geekmall.modules.merchant.converter.MerchantPromotionConverter;
import com.geekmall.modules.merchant.dto.MerchantPromotionQueryDTO;
import com.geekmall.modules.merchant.entity.Promotion;
import com.geekmall.modules.merchant.mapper.PromotionMapper;
import com.geekmall.modules.merchant.service.MerchantPromotionService;
import com.geekmall.modules.merchant.support.Numbers;
import com.geekmall.modules.merchant.vo.MerchantCouponVO;
import com.geekmall.modules.merchant.vo.MerchantPageVO;
import com.geekmall.modules.merchant.vo.MerchantPromotionVO;
import com.geekmall.modules.marketing.entity.CouponTemplate;
import com.geekmall.modules.marketing.mapper.CouponTemplateMapper;
import com.geekmall.security.MerchantSecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家端营销中心服务实现。
 */
@Service
@RequiredArgsConstructor
public class MerchantPromotionServiceImpl implements MerchantPromotionService {

    private static final List<String> TAB_KEYS =
            List.of("all", "running", "pending", "paused", "ended");

    private final PromotionMapper promotionMapper;
    private final CouponTemplateMapper couponTemplateMapper;

    @Override
    public MerchantPageVO<MerchantPromotionVO> page(MerchantPromotionQueryDTO query) {
        Long shopId = MerchantSecurityUtils.getShopId();
        LambdaQueryWrapper<Promotion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Promotion::getShopId, shopId);
        if (StringUtils.hasText(query.getStatus()) && !"all".equals(query.getStatus())) {
            wrapper.eq(Promotion::getStatus, query.getStatus());
        }
        if (StringUtils.hasText(query.getType()) && !"all".equals(query.getType())) {
            wrapper.eq(Promotion::getType, query.getType());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword().trim();
            wrapper.and(w -> w.like(Promotion::getName, kw).or().like(Promotion::getType, kw));
        }
        applySort(wrapper, query.getSort());

        Page<Promotion> page = new Page<>(query.getPage(), query.getSize());
        IPage<Promotion> result = promotionMapper.selectPage(page, wrapper);
        List<MerchantPromotionVO> list = result.getRecords().stream()
                .map(MerchantPromotionConverter::toVO).toList();

        MerchantPageVO<MerchantPromotionVO> vo = new MerchantPageVO<>(
                list, result.getTotal(), result.getCurrent(), result.getSize());
        List<Promotion> all = promotionMapper.selectList(new LambdaQueryWrapper<Promotion>()
                .eq(Promotion::getShopId, shopId));
        vo.put("tabs", buildTabs(all));
        vo.put("stats", buildStats(all));
        return vo;
    }

    @Override
    public List<Map<String, Object>> channels() {
        // 推广渠道效果依赖广告投放系统的回传数据，当前后端无数据源，返回空集合
        return List.of();
    }

    @Override
    public List<MerchantCouponVO> coupons() {
        return couponTemplateMapper.selectList(new LambdaQueryWrapper<CouponTemplate>()
                        .orderByDesc(CouponTemplate::getId)).stream()
                .map(MerchantPromotionConverter::toCoupon).toList();
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    private void applySort(LambdaQueryWrapper<Promotion> wrapper, String sort) {
        String key = sort == null ? "" : sort;
        switch (key) {
            case "roi_desc" -> wrapper.orderByDesc(Promotion::getRoi);
            case "cost_desc" -> wrapper.orderByDesc(Promotion::getCost);
            case "start_desc" -> wrapper.orderByDesc(Promotion::getStartAt);
            default -> wrapper.orderByDesc(Promotion::getGmv);
        }
    }

    private Map<String, Object> buildTabs(List<Promotion> all) {
        Map<String, Object> tabs = new LinkedHashMap<>();
        tabs.put("all", (long) all.size());
        for (String key : TAB_KEYS) {
            if ("all".equals(key)) {
                continue;
            }
            tabs.put(key, all.stream().filter(p -> key.equals(p.getStatus())).count());
        }
        return tabs;
    }

    private Map<String, Object> buildStats(List<Promotion> all) {
        BigDecimal totalCost = all.stream().map(p -> MerchantPromotionConverter.safe(p.getCost()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalGmv = all.stream().map(p -> MerchantPromotionConverter.safe(p.getGmv()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal runningGmv = all.stream()
                .filter(p -> "running".equals(p.getStatus()))
                .map(p -> MerchantPromotionConverter.safe(p.getGmv()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<MerchantCouponVO> coupons = coupons();
        long taken = coupons.stream().mapToLong(MerchantCouponVO::getTaken).sum();
        long used = coupons.stream().mapToLong(MerchantCouponVO::getUsed).sum();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("running", all.stream().filter(p -> "running".equals(p.getStatus())).count());
        stats.put("runningGmv", runningGmv);
        stats.put("totalCost", totalCost);
        stats.put("totalGmv", totalGmv);
        stats.put("roas", Numbers.round(Numbers.divide(totalGmv.doubleValue(), totalCost.doubleValue()), 4));
        stats.put("couponUsedRate", Numbers.round(Numbers.divide(used, taken), 4));
        return stats;
    }
}
