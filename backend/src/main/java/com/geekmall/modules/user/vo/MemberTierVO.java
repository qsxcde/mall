package com.geekmall.modules.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 会员等级阶梯项。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会员等级阶梯")
public class MemberTierVO implements Serializable {

    private String icon;

    private String name;

    @Schema(description = "升级条件文案")
    private String req;

    @Schema(description = "权益摘要")
    private String perk;

    @Schema(description = "是否为当前等级")
    private Boolean current;
}
