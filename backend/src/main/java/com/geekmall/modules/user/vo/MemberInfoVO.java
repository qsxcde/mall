package com.geekmall.modules.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 会员中心出参，对应前端 MemberView。
 */
@Data
@Schema(description = "会员信息")
public class MemberInfoVO implements Serializable {

    private String nickname;

    private Long levelId;

    private String levelName;

    private Integer points;

    private Integer growth;

    private Integer growthMin;

    private Integer growthMax;

    @Schema(description = "当前等级成长进度百分比")
    private Integer growthPercent;

    private String nextLevelName;

    @Schema(description = "升级到下一等级所需成长值")
    private Integer nextLevelGrowth;

    @Schema(description = "会员折扣率")
    private BigDecimal discountRate;

    @Schema(description = "当前等级权益")
    private List<String> benefits;

    @Schema(description = "今日是否已签到")
    private Boolean signedToday;

    @Schema(description = "累计签到天数")
    private Integer signDays;

    @Schema(description = "等级阶梯")
    private List<MemberTierVO> tiers;
}
