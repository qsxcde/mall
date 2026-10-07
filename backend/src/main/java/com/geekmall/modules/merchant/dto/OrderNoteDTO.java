package com.geekmall.modules.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 商家备注入参。
 */
@Data
@Schema(description = "订单备注")
public class OrderNoteDTO implements Serializable {

    @NotBlank(message = "订单号不能为空")
    private String id;

    @Schema(description = "备注内容")
    private String note;
}
