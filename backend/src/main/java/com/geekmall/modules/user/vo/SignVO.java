package com.geekmall.modules.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 签到结果 / 签到状态。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "签到状态")
public class SignVO implements Serializable {

    @Schema(description = "本次是否签到成功")
    private Boolean signed;

    @Schema(description = "今天是否已签到")
    private Boolean signedToday;

    @Schema(description = "本次获得积分")
    private Integer gainedPoints;

    @Schema(description = "当前积分余额")
    private Integer totalPoints;

    @Schema(description = "累计签到天数")
    private Integer signDays;
}
