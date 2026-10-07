package com.geekmall.modules.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 物流信息出参，对应前端 LogisticsView。
 *
 * <p>骨架阶段轨迹由订单状态时间轴派生，接入真实快递查询后替换 steps 数据源即可。</p>
 */
@Data
@Schema(description = "物流信息")
public class LogisticsVO implements Serializable {

    private String company;

    @Schema(description = "运单号")
    private String no;

    private String phone;

    @Schema(description = "物流轨迹")
    private List<OrderTimelineVO> steps;
}
