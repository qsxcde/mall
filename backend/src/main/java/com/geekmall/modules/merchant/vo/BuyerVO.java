package com.geekmall.modules.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 买家精简信息。
 */
@Data
@Schema(description = "买家")
public class BuyerVO implements Serializable {

    private String name;

    @Schema(description = "会员等级，如 V5")
    private String level;
}
