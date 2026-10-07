package com.geekmall.modules.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 时间轴节点（订单进度 / 物流轨迹通用）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "时间轴节点")
public class OrderTimelineVO implements Serializable {

    private String text;

    private LocalDateTime time;

    @Schema(description = "该节点是否已完成")
    private Boolean done;
}
