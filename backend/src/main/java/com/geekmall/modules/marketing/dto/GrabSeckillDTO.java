package com.geekmall.modules.marketing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 秒杀抢购入参。
 */
@Data
@Schema(description = "秒杀抢购")
public class GrabSeckillDTO implements Serializable {

    @NotNull(message = "请先选择收货地址")
    @Schema(description = "收货地址 ID，抢购成功后会直接生成待付款订单")
    private Long addressId;
}
