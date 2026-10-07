package com.geekmall.modules.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 财务域查询条件：结算单 / 资金流水共用。
 */
@Data
@Schema(description = "商家端财务查询")
public class MerchantFinanceQueryDTO implements Serializable {

    @Min(value = 1, message = "页码最小为 1")
    private Integer page = 1;

    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = 100, message = "每页条数最大为 100")
    private Integer size = 6;

    @Schema(description = "结算单状态：all/settled/settling/pending")
    private String status = "all";

    @Schema(description = "流水类型：all/settle/withdraw/commission/service/refund")
    private String type = "all";
}
