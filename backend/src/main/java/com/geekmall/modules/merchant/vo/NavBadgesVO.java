package com.geekmall.modules.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 侧栏导航角标。
 *
 * <p>集中在一次请求内计算，保证「不管在哪个页面，角标数值都一致」。</p>
 */
@Data
@Schema(description = "侧栏导航角标")
public class NavBadgesVO implements Serializable {

    @Schema(description = "库存预警商品数")
    private long productWarn;

    @Schema(description = "待发货订单数")
    private long orderPending;

    @Schema(description = "发货临期/超时数")
    private long shippingLate;

    @Schema(description = "售后待处理数")
    private long aftersale;

    @Schema(description = "进行中营销活动数")
    private long marketing;

    @Schema(description = "待回复评价数")
    private long reviewWait;
}
