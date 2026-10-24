package com.geekmall.modules.trade.support;

import com.geekmall.common.enums.PayMethod;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.cart.service.CartService;
import com.geekmall.modules.cart.vo.CartItemVO;
import com.geekmall.modules.marketing.service.CouponService;
import com.geekmall.modules.marketing.vo.UserCouponVO;
import com.geekmall.modules.trade.dto.PreOrderDTO;
import com.geekmall.modules.trade.vo.OptionVO;
import com.geekmall.modules.trade.vo.PreOrderVO;
import com.geekmall.modules.user.service.UserService;
import com.geekmall.modules.user.vo.AddressVO;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 结算试算协作类：结算项筛选、金额/运费试算、配送与支付选项。
 *
 * <p>从 {@code TradeServiceImpl} 拆出的询价块，方法体逐字搬运、行为不变；
 * 交易门面保留接口方法与事务边界，试算的内部计算收敛在这里。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCheckoutSupport {

    private static final Map<String, BigDecimal> SHIPPING_FEES = new LinkedHashMap<>();
    private static final Map<String, String> SHIPPING_LABELS = new LinkedHashMap<>();

    static {
        SHIPPING_FEES.put("standard", new BigDecimal("0.00"));
        SHIPPING_FEES.put("express", new BigDecimal("18.00"));
        SHIPPING_FEES.put("same-day", new BigDecimal("25.00"));

        SHIPPING_LABELS.put("standard", "标准配送");
        SHIPPING_LABELS.put("express", "顺丰速运");
        SHIPPING_LABELS.put("same-day", "次日达");
    }

    private final CartService cartService;
    private final CouponService couponService;
    private final UserService userService;

    /** 结算页试算（{@code TradeServiceImpl#preOrder} 的内部计算）。 */
    public PreOrderVO preOrder(Long userId, PreOrderDTO dto) {
        List<CartItemVO> items = resolveCheckoutItems(userId, dto == null ? null : dto.getCartItemIds());
        if (items.isEmpty()) {
            throw BizException.of(ResultCode.CART_EMPTY);
        }
        BigDecimal goodsAmount = sumAmount(items);

        // 运费与优惠券都参与试算：用户换配送方式或换券时前端重新调用本接口，
        // 金额始终由服务端给出，避免前端自己拼算出现与下单不一致
        String shippingType = resolveShippingType(dto == null ? null : dto.getShippingType());
        BigDecimal shippingFee = resolveShippingFee(shippingType);
        BigDecimal discount = BigDecimal.ZERO;
        Long couponId = dto == null ? null : dto.getCouponId();
        String couponNotice = null;
        if (couponId != null) {
            try {
                UserCouponVO coupon = couponService.requireUsable(userId, couponId, goodsAmount);
                discount = couponService.calcGoodsDiscount(coupon, goodsAmount);
                if ("shipping".equals(coupon.getType())) {
                    shippingFee = BigDecimal.ZERO;
                }
            } catch (BizException e) {
                // 试算阶段不因券失效而整页失败：降级为不使用券并给出提示
                log.info("试算时优惠券 {} 不可用：{}", couponId, e.getMessage());
                couponId = null;
                couponNotice = e.getMessage();
            }
        }

        PreOrderVO vo = new PreOrderVO();
        vo.setItems(items);
        vo.setGoodsAmount(goodsAmount);
        vo.setShippingFee(shippingFee);
        vo.setDiscount(discount);
        vo.setPayTotal(goodsAmount
                .add(shippingFee)
                .subtract(discount)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP));
        vo.setShippingOptions(buildOptions(SHIPPING_FEES, SHIPPING_LABELS));
        vo.setPaymentOptions(buildPaymentOptions());
        vo.setSelectedShippingType(shippingType);
        vo.setSelectedCouponId(couponId);
        vo.setCouponNotice(couponNotice);

        List<AddressVO> addresses = userService.listAddresses(userId);
        vo.setAddresses(addresses);
        if (!addresses.isEmpty()) {
            Long defaultId = addresses.stream()
                    .filter(a -> a.getIsDefault() != null && a.getIsDefault() == 1)
                    .map(AddressVO::getId)
                    .findFirst()
                    .orElse(addresses.get(0).getId());
            vo.setDefaultAddressId(defaultId);
        }

        vo.setCoupons(couponService.listForCheckout(userId, goodsAmount));
        return vo;
    }

    public List<CartItemVO> resolveCheckoutItems(Long userId, List<Long> cartItemIds) {
        List<CartItemVO> all = cartService.list(userId);
        if (cartItemIds == null || cartItemIds.isEmpty()) {
            return all.stream().filter(i -> Boolean.TRUE.equals(i.getChecked())).toList();
        }
        Set<Long> ids = cartItemIds.stream().collect(Collectors.toSet());
        return all.stream().filter(i -> ids.contains(i.getId())).toList();
    }

    private BigDecimal sumAmount(List<CartItemVO> items) {
        return items.stream()
                .map(i -> i.getAmount() == null ? BigDecimal.ZERO : i.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private String resolveShippingType(String shippingType) {
        return (StringUtils.hasText(shippingType) && SHIPPING_FEES.containsKey(shippingType))
                ? shippingType
                : "standard";
    }

    public BigDecimal resolveShippingFee(String shippingType) {
        return SHIPPING_FEES.getOrDefault(resolveShippingType(shippingType), BigDecimal.ZERO);
    }

    private List<OptionVO> buildOptions(Map<String, BigDecimal> fees, Map<String, String> labels) {
        return fees.entrySet().stream()
                .map(e -> new OptionVO(e.getKey(), labels.get(e.getKey()), e.getValue(), null))
                .toList();
    }

    private List<OptionVO> buildPaymentOptions() {
        return Arrays.stream(PayMethod.values())
                .map(m -> new OptionVO(m.getCode(), m.getLabel(), null, null))
                .toList();
    }
}
