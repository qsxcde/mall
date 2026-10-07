package com.geekmall.modules.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家端资金流水行。
 */
@Data
@Schema(description = "商家端资金流水")
public class MerchantFundFlowVO implements Serializable {

    private String id;

    @Schema(description = "类型：settle/withdraw/commission/service/refund")
    private String type;

    private String label;

    @Schema(description = "配色键")
    private String tone;

    @Schema(description = "1 入账 -1 出账（金额带符号输出）")
    private Integer direction;

    @Schema(description = "带符号金额")
    private BigDecimal amount;

    private String remark;

    private LocalDateTime at;
}
