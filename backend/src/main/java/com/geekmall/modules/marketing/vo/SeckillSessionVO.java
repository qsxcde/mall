package com.geekmall.modules.marketing.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 秒杀场次出参。
 */
@Data
@Schema(description = "秒杀场次")
public class SeckillSessionVO implements Serializable {

    private Long id;

    @Schema(description = "场次时间，如 14:00")
    private String time;

    private String label;

    @Schema(description = "wait 未开始 / running 进行中 / done 已结束")
    private String state;
}
