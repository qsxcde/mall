package com.geekmall.modules.marketing.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 用户优惠券出参（结算页选择券 / 个人中心我的优惠券）。
 */
@Data
@Schema(description = "用户优惠券")
public class UserCouponVO implements Serializable {

    @Schema(description = "用户券 ID，结算提交时回传")
    private Long id;

    private Long templateId;

    private String name;

    @Schema(description = "full 满减 / percent 折扣 / shipping 免运费")
    private String type;

    private BigDecimal amount;

    @Schema(description = "单位：¥ 或 折")
    private String unit;

    private BigDecimal threshold;

    private String scope;

    private LocalDate validFrom;

    private LocalDate validTo;

    @Schema(description = "0 未使用 1 已使用 2 已过期")
    private Integer status;

    @Schema(description = "状态文案")
    private String statusText;

    @Schema(description = "当前订单是否可用")
    private Boolean usable;

    @Schema(description = "不可用原因")
    private String unusableReason;
}
