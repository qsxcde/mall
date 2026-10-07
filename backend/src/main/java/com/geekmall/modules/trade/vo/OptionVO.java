package com.geekmall.modules.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 通用选项（配送方式 / 支付方式）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "选项")
public class OptionVO implements Serializable {

    private String value;

    private String label;

    @Schema(description = "附加费用，如运费")
    private BigDecimal fee;

    private String description;
}
