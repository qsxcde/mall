package com.geekmall.modules.marketing.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 秒杀商品出参，对应前端 SeckillView。
 */
@Data
@Schema(description = "秒杀商品")
public class SeckillItemVO implements Serializable {

    private Long id;

    private Long sessionId;

    private Long productId;

    private String title;

    private String cover;

    private String spec;

    private BigDecimal seckillPrice;

    private BigDecimal oldPrice;

    @Schema(description = "剩余活动库存")
    private Integer stock;

    private Integer total;

    private Integer sold;

    @Schema(description = "已抢百分比")
    private Integer percent;

    private String tip;

    @Schema(description = "是否未开始")
    private Boolean notStart;

    @Schema(description = "是否已抢光")
    private Boolean soldout;
}
