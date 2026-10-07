package com.geekmall.modules.trade.vo;

import com.geekmall.modules.cart.vo.CartItemVO;
import com.geekmall.modules.marketing.vo.UserCouponVO;
import com.geekmall.modules.user.vo.AddressVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 结算试算出参，对应前端 CheckoutView。
 */
@Data
@Schema(description = "结算试算结果")
public class PreOrderVO implements Serializable {

    @Schema(description = "待结算商品")
    private List<CartItemVO> items;

    private BigDecimal goodsAmount;

    private BigDecimal shippingFee;

    private BigDecimal discount;

    @Schema(description = "应付金额")
    private BigDecimal payTotal;

    @Schema(description = "收货地址列表")
    private List<AddressVO> addresses;

    @Schema(description = "默认选中的地址 ID")
    private Long defaultAddressId;

    @Schema(description = "配送方式选项")
    private List<OptionVO> shippingOptions;

    @Schema(description = "可用优惠券（不可用的也会返回，便于前端置灰）")
    private List<UserCouponVO> coupons;

    @Schema(description = "支付方式选项")
    private List<OptionVO> paymentOptions;

    @Schema(description = "实际生效的配送方式")
    private String selectedShippingType;

    @Schema(description = "实际生效的优惠券 ID；券不可用时为 null")
    private Long selectedCouponId;

    @Schema(description = "优惠券提示，例如「所选优惠券不可用」")
    private String couponNotice;
}
