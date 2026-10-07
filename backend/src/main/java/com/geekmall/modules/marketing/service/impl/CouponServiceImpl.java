package com.geekmall.modules.marketing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.marketing.entity.CouponTemplate;
import com.geekmall.modules.marketing.entity.UserCoupon;
import com.geekmall.modules.marketing.mapper.CouponTemplateMapper;
import com.geekmall.modules.marketing.mapper.UserCouponMapper;
import com.geekmall.modules.marketing.service.CouponService;
import com.geekmall.modules.marketing.vo.CouponTemplateVO;
import com.geekmall.modules.marketing.vo.UserCouponVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 优惠券服务实现（交易结算切片）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {

    private static final int STATUS_UNUSED = 0;
    private static final int STATUS_USED = 1;

    /** 演示账号发放的券模板：满999减50、新人专享20元 */
    private static final List<Long> DEMO_TEMPLATE_IDS = List.of(1L, 4L);

    private final UserCouponMapper userCouponMapper;
    private final CouponTemplateMapper templateMapper;

    /* ------------------------------ 领券中心 ------------------------------ */

    @Override
    public List<CouponTemplateVO> listTemplates(String type, Long userId) {
        List<CouponTemplate> templates = templateMapper.selectList(new LambdaQueryWrapper<CouponTemplate>()
                .eq(CouponTemplate::getStatus, 1)
                .eq(StringUtils.hasText(type), CouponTemplate::getType, type)
                .orderByAsc(CouponTemplate::getId));
        if (templates.isEmpty()) {
            return List.of();
        }
        Set<Long> claimedIds = Set.of();
        if (userId != null) {
            List<Long> templateIds = templates.stream().map(CouponTemplate::getId).toList();
            claimedIds = userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                            .eq(UserCoupon::getUserId, userId)
                            .in(UserCoupon::getTemplateId, templateIds))
                    .stream().map(UserCoupon::getTemplateId).collect(Collectors.toSet());
        }
        Set<Long> finalClaimed = claimedIds;
        return templates.stream()
                .map(template -> toTemplateVO(template, finalClaimed.contains(template.getId())))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void claim(Long userId, Long templateId) {
        CouponTemplate template = templateMapper.selectById(templateId);
        if (template == null || template.getStatus() == null || template.getStatus() != 1) {
            throw new BizException(ResultCode.NOT_FOUND, "优惠券不存在或已下架");
        }
        if (template.getSoldout() != null && template.getSoldout() == 1) {
            throw new BizException(ResultCode.BIZ_ERROR, "该券已被抢光");
        }
        int perLimit = template.getPerLimit() == null ? 1 : template.getPerLimit();
        Long owned = userCouponMapper.selectCount(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getTemplateId, templateId));
        if (owned != null && owned >= perLimit) {
            throw new BizException(ResultCode.BIZ_ERROR, "您已领取过该券，每人限领 " + perLimit + " 张");
        }

        // 先扣库存：带 stock > 0 条件，并发下不会超发
        if (templateMapper.deductStock(templateId) == 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "该券已被抢光");
        }
        try {
            UserCoupon coupon = new UserCoupon();
            coupon.setUserId(userId);
            coupon.setTemplateId(templateId);
            coupon.setStatus(STATUS_UNUSED);
            coupon.setReceiveTime(LocalDateTime.now());
            userCouponMapper.insert(coupon);
        } catch (DuplicateKeyException e) {
            // 唯一索引 (user_id, template_id) 兜底并发重复领取，异常抛出即整体回滚（库存一并回退）
            throw new BizException(ResultCode.BIZ_ERROR, "您已领取过该券");
        }
        templateMapper.refreshPercent(templateId);
        log.info("用户 {} 领取优惠券模板 {}", userId, templateId);
    }

    /* ------------------------------ 我的优惠券 ------------------------------ */

    @Override
    public List<UserCouponVO> myCoupons(Long userId, Integer status) {
        List<UserCoupon> coupons = userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .eq(status != null, UserCoupon::getStatus, status)
                .orderByDesc(UserCoupon::getId));
        return toVOList(coupons, null);
    }

    @Override
    public List<UserCouponVO> listForCheckout(Long userId, BigDecimal goodsAmount) {
        List<UserCoupon> coupons = userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getStatus, STATUS_UNUSED)
                .orderByDesc(UserCoupon::getId));
        return toVOList(coupons, goodsAmount == null ? BigDecimal.ZERO : goodsAmount);
    }

    @Override
    public UserCouponVO requireUsable(Long userId, Long userCouponId, BigDecimal goodsAmount) {
        UserCoupon coupon = userCouponMapper.selectById(userCouponId);
        if (coupon == null || !coupon.getUserId().equals(userId)) {
            throw new BizException(ResultCode.NOT_FOUND, "优惠券不存在");
        }
        CouponTemplate template = templateMapper.selectById(coupon.getTemplateId());
        if (template == null) {
            throw new BizException(ResultCode.BIZ_ERROR, "优惠券模板已失效");
        }
        UserCouponVO vo = buildVO(coupon, template, goodsAmount);
        if (Boolean.FALSE.equals(vo.getUsable())) {
            throw new BizException(ResultCode.BIZ_ERROR, vo.getUnusableReason());
        }
        return vo;
    }

    @Override
    public BigDecimal calcGoodsDiscount(UserCouponVO coupon, BigDecimal goodsAmount) {
        if (coupon == null || goodsAmount == null) {
            return BigDecimal.ZERO;
        }
        String type = coupon.getType() == null ? "full" : coupon.getType();
        BigDecimal discount = switch (type) {
            // 免运费券不减商品金额，运费由交易域置零
            case "shipping" -> BigDecimal.ZERO;
            // 折扣券：amount = 9 表示 9 折
            case "percent" -> goodsAmount.multiply(
                    BigDecimal.ONE.subtract(coupon.getAmount().divide(BigDecimal.TEN, 4, RoundingMode.HALF_UP)));
            // 满减券：抵扣额不超过商品金额
            default -> coupon.getAmount().min(goodsAmount);
        };
        return discount.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markUsed(Long userId, Long userCouponId, String orderNo) {
        UserCoupon update = new UserCoupon();
        update.setId(userCouponId);
        update.setStatus(STATUS_USED);
        update.setOrderNo(orderNo);
        update.setUseTime(LocalDateTime.now());
        userCouponMapper.updateById(update);
        log.info("用户 {} 核销优惠券 {}，订单 {}", userId, userCouponId, orderNo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseByOrderNo(String orderNo) {
        // 注意：需要显式 set 为 null，MyBatis-Plus 默认忽略 null 字段
        int rows = userCouponMapper.update(null, new LambdaUpdateWrapper<UserCoupon>()
                .eq(UserCoupon::getOrderNo, orderNo)
                .eq(UserCoupon::getStatus, STATUS_USED)
                .set(UserCoupon::getStatus, STATUS_UNUSED)
                .set(UserCoupon::getOrderNo, null)
                .set(UserCoupon::getUseTime, null));
        if (rows > 0) {
            log.info("订单 {} 取消，已回退 {} 张优惠券", orderNo, rows);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void grantDemoCoupons(Long userId) {
        for (Long templateId : DEMO_TEMPLATE_IDS) {
            Long exists = userCouponMapper.selectCount(new LambdaQueryWrapper<UserCoupon>()
                    .eq(UserCoupon::getUserId, userId)
                    .eq(UserCoupon::getTemplateId, templateId));
            if (exists != null && exists > 0) {
                continue;
            }
            CouponTemplate template = templateMapper.selectById(templateId);
            if (template == null) {
                continue;
            }
            UserCoupon coupon = new UserCoupon();
            coupon.setUserId(userId);
            coupon.setTemplateId(templateId);
            coupon.setStatus(STATUS_UNUSED);
            coupon.setReceiveTime(LocalDateTime.now());
            userCouponMapper.insert(coupon);
        }
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    private CouponTemplateVO toTemplateVO(CouponTemplate template, boolean claimed) {
        CouponTemplateVO vo = new CouponTemplateVO();
        vo.setId(template.getId());
        vo.setName(template.getName());
        vo.setType(template.getType());
        vo.setAmount(template.getAmount());
        vo.setUnit(template.getUnit());
        vo.setThreshold(template.getThreshold());
        vo.setScope(template.getScope());
        vo.setValidFrom(template.getValidFrom());
        vo.setValidTo(template.getValidTo());
        vo.setTotal(template.getTotal());
        vo.setStock(template.getStock());
        vo.setPerLimit(template.getPerLimit());
        vo.setPercent(template.getPercent());
        vo.setLimited(template.getLimited() != null && template.getLimited() == 1);
        vo.setSoldout((template.getSoldout() != null && template.getSoldout() == 1)
                || (template.getStock() != null && template.getStock() <= 0));
        vo.setClaimed(claimed);
        return vo;
    }

    private List<UserCouponVO> toVOList(List<UserCoupon> coupons, BigDecimal goodsAmount) {
        if (coupons == null || coupons.isEmpty()) {
            return List.of();
        }
        List<Long> templateIds = coupons.stream().map(UserCoupon::getTemplateId).distinct().toList();
        Map<Long, CouponTemplate> templateMap = templateMapper.selectBatchIds(templateIds).stream()
                .collect(Collectors.toMap(CouponTemplate::getId, Function.identity(), (a, b) -> a));
        return coupons.stream()
                .map(coupon -> buildVO(coupon, templateMap.get(coupon.getTemplateId()), goodsAmount))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private UserCouponVO buildVO(UserCoupon coupon, CouponTemplate template, BigDecimal goodsAmount) {
        if (template == null) {
            return null;
        }
        UserCouponVO vo = new UserCouponVO();
        vo.setId(coupon.getId());
        vo.setTemplateId(template.getId());
        vo.setName(template.getName());
        vo.setType(template.getType());
        vo.setAmount(template.getAmount());
        vo.setUnit(template.getUnit());
        vo.setThreshold(template.getThreshold());
        vo.setScope(template.getScope());
        vo.setValidFrom(template.getValidFrom());
        vo.setValidTo(template.getValidTo());
        vo.setStatus(coupon.getStatus());
        vo.setStatusText(statusText(coupon.getStatus()));

        // 仅当传入订单金额时才判断可用性（结算页场景）
        if (goodsAmount != null) {
            String reason = checkUsable(coupon, template, goodsAmount);
            vo.setUsable(reason == null);
            vo.setUnusableReason(reason);
        }
        return vo;
    }

    private String checkUsable(UserCoupon coupon, CouponTemplate template, BigDecimal goodsAmount) {
        if (coupon.getStatus() == null || coupon.getStatus() != STATUS_UNUSED) {
            return "该券已使用或已过期";
        }
        LocalDate today = LocalDate.now();
        if (template.getValidTo() != null && today.isAfter(template.getValidTo())) {
            return "该券已过期";
        }
        if (template.getValidFrom() != null && today.isBefore(template.getValidFrom())) {
            return "该券尚未生效";
        }
        if (template.getThreshold() != null && goodsAmount.compareTo(template.getThreshold()) < 0) {
            return "未满足使用门槛 ¥" + template.getThreshold().stripTrailingZeros().toPlainString();
        }
        if (template.getStatus() != null && template.getStatus() != 1) {
            return "该券已下架";
        }
        return null;
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case 0 -> "未使用";
            case 1 -> "已使用";
            case 2 -> "已过期";
            default -> "";
        };
    }
}
