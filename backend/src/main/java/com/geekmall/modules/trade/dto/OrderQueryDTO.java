package com.geekmall.modules.trade.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 订单列表查询条件，对应前端 OrdersView 的状态筛选与订单搜索。
 */
@Data
@Schema(description = "订单查询条件")
public class OrderQueryDTO implements Serializable {

    @Min(value = 1, message = "页码最小为 1")
    private Integer page = 1;

    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = 100, message = "每页条数最多 100")
    private Integer pageSize = 5;

    @Schema(description = "订单状态 0-5，不传表示全部")
    private Integer status;

    @Schema(description = "订单号或商品名关键词")
    private String keyword;
}
