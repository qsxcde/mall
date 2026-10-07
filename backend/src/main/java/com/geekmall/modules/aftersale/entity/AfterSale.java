package com.geekmall.modules.aftersale.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 售后申请 oms_aftersale。
 */
@Data
@TableName("oms_aftersale")
public class AfterSale implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属店铺（商家端隔离维度） */
    private Long shopId;

    private Long userId;

    private String orderNo;

    /** refund 仅退款 / return 退货退款 / exchange 换货 / repair 维修 */
    private String type;

    private String typeName;

    private String reason;

    private String content;

    /** 凭证图片，逗号分隔 */
    private String images;

    private String phone;

    /** 退款金额 */
    private BigDecimal amount;

    /** 0 处理中 1 已完成 2 已取消 */
    private Integer status;

    /** 商家侧工单状态：pending/wait_return/wait_receive/done/rejected */
    private String merchantStatus;

    /** 商家拒绝理由 */
    private String rejectReason;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
