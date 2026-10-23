package com.geekmall.modules.aftersale.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.io.Serializable;
import lombok.Data;

/**
 * 登记已寄回入参，对应前端 aftersale/DetailView 的「我已寄回」表单。
 *
 * <p>退货物流两项均选填：买家没有运单号时也应能登记寄回，
 * 但填了商家确认收货时就有核对凭据。</p>
 */
@Data
@Schema(description = "登记已寄回")
public class MarkReturnedDTO implements Serializable {

    @Size(max = 64, message = "快递公司名称过长")
    @Schema(description = "退货快递公司（选填）")
    private String returnExpress;

    @Size(max = 64, message = "物流单号过长")
    @Schema(description = "退货物流单号（选填）")
    private String returnWaybill;
}
