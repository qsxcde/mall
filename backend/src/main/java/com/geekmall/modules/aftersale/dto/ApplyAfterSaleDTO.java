package com.geekmall.modules.aftersale.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 申请售后入参，对应前端 aftersale/ApplyView。
 */
@Data
@Schema(description = "申请售后")
public class ApplyAfterSaleDTO implements Serializable {

    @NotBlank(message = "请选择关联订单")
    private String orderNo;

    @NotBlank(message = "请选择服务类型")
    @Pattern(regexp = "^(refund|return|exchange|repair)$", message = "服务类型不合法")
    @Schema(description = "refund 仅退款 / return 退货退款 / exchange 换货 / repair 维修")
    private String type;

    @NotBlank(message = "请选择申请原因")
    @Size(max = 100, message = "申请原因过长")
    private String reason;

    @Size(max = 500, message = "问题描述最多 500 字")
    private String content;

    @Schema(description = "凭证图片地址，最多 6 张")
    @Size(max = 6, message = "最多上传 6 张凭证")
    private List<String> images;

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "联系电话格式不正确")
    private String phone;
}
