package com.geekmall.modules.marketing.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 积分商品出参，对应前端 PointsMallView。
 */
@Data
@Schema(description = "积分商品")
public class PointsGoodsVO implements Serializable {

    private Long id;

    private String name;

    private Integer points;

    private String icon;

    private String category;

    private String description;

    private Integer stock;

    @Schema(description = "当前用户积分是否足够")
    private Boolean affordable;

    @Schema(description = "是否已兑完")
    private Boolean soldout;
}
