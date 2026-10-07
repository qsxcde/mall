package com.geekmall.modules.marketing.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 积分兑换结果 / 兑换记录。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "积分兑换结果")
public class PointsExchangeVO implements Serializable {

    private Long goodsId;

    private String goodsName;

    @Schema(description = "本次消耗积分")
    private Integer points;

    @Schema(description = "兑换后剩余积分")
    private Integer remainPoints;

    private LocalDateTime exchangeTime;
}
