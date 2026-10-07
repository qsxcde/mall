package com.geekmall.modules.content.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 关于我们数据统计，对应前端 AboutView。数值由数据库实时统计，而非硬编码。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "站点统计")
public class AboutStatVO implements Serializable {

    @Schema(description = "展示数值")
    private String num;

    @Schema(description = "指标名称")
    private String lab;
}
