package com.geekmall.modules.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单内嵌的商品快照。
 */
@Data
@Schema(description = "订单商品快照")
public class OrderProductVO implements Serializable {

    private Long id;

    private String name;

    private String cover;

    private String spec;

    private BigDecimal price;
}
