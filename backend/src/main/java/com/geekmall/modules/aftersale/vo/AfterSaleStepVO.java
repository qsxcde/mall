package com.geekmall.modules.aftersale.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 售后处理进度节点。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "售后进度节点")
public class AfterSaleStepVO implements Serializable {

    private String text;

    private LocalDateTime time;

    @Schema(description = "是否已完成")
    private Boolean done;
}
