package com.geekmall.modules.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 提交评价入参，对应前端 ReviewView 的三维评分表单。
 */
@Data
@Schema(description = "提交评价")
public class SubmitReviewDTO implements Serializable {

    @NotBlank(message = "缺少订单号")
    private String orderNo;

    @Min(value = 1, message = "描述评分最低 1 分")
    @Max(value = 5, message = "描述评分最高 5 分")
    private Integer scoreDesc = 5;

    @Min(value = 1, message = "物流评分最低 1 分")
    @Max(value = 5, message = "物流评分最高 5 分")
    private Integer scoreLogistics = 5;

    @Min(value = 1, message = "服务评分最低 1 分")
    @Max(value = 5, message = "服务评分最高 5 分")
    private Integer scoreService = 5;

    @Size(max = 500, message = "评价内容最多 500 字")
    private String content;

    @Schema(description = "图片地址列表，最多 6 张")
    @Size(max = 6, message = "最多上传 6 张图片")
    private List<String> images;

    private Boolean anonymous = false;
}
