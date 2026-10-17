package com.geekmall.modules.aftersale.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 售后出参：列表与详情共用。
 */
@Data
@Schema(description = "售后申请")
public class AfterSaleVO implements Serializable {

    private Long id;

    private String orderNo;

    @Schema(description = "关联订单的首件商品名")
    private String productTitle;

    private String type;

    private String typeName;

    private String reason;

    private String content;

    private List<String> images;

    private String phone;

    private BigDecimal amount;

    @Schema(description = "0 处理中 1 已完成 2 已取消")
    private Integer status;

    @Schema(description = "买家侧状态文案：处理中 / 待寄回 / 待收货 / 已完成 / 已取消 / 已拒绝")
    private String statusText;

    /**
     * 商家侧工单状态（pending / wait_return / wait_receive / done / rejected）。
     *
     * <p>必须暴露给买家：{@code status} 只有三态，商家拒绝与买家撤销都会落成 2，
     * 只看数字列无法区分「商家拒绝了我」和「我自己撤销了」。</p>
     */
    @Schema(description = "商家侧工单状态：pending/wait_return/wait_receive/done/rejected")
    private String merchantStatus;

    @Schema(description = "商家拒绝理由，仅 merchantStatus=rejected 时有值")
    private String rejectReason;

    @Schema(description = "状态说明，供详情页状态头展示")
    private String statusTip;

    @Schema(description = "是否仍在流程中（可撤销）")
    private Boolean canCancel;

    private LocalDateTime createTime;

    @Schema(description = "处理进度时间轴，仅详情返回")
    private List<AfterSaleStepVO> steps;
}
