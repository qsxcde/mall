package com.geekmall.modules.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 评价回复入参。
 */
@Data
@Schema(description = "评价回复")
public class ReviewReplyDTO implements Serializable {

    @NotBlank(message = "评价 ID 不能为空")
    private String id;

    @NotBlank(message = "回复内容不能为空")
    private String content;
}
