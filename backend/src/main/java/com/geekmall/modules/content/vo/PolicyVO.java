package com.geekmall.modules.content.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 政策条款出参，对应前端 PolicyView。
 */
@Data
@Schema(description = "政策条款")
public class PolicyVO implements Serializable {

    private Long id;

    private String title;

    @Schema(description = "条款内容，一条一项")
    private List<String> items;
}
