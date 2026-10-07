package com.geekmall.modules.trade.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 取消订单入参。
 */
@Data
@Schema(description = "取消订单")
public class CancelOrderDTO implements Serializable {

    @Size(max = 128, message = "取消原因过长")
    @Schema(description = "取消原因", example = "不想要了")
    private String reason;
}
