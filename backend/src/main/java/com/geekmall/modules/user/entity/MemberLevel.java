package com.geekmall.modules.user.entity;

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
 * 会员等级 ums_member_level。
 */
@Data
@TableName("ums_member_level")
public class MemberLevel implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String levelName;

    /** 成长值下限（含） */
    private Integer growthMin;

    /** 成长值上限（含） */
    private Integer growthMax;

    /** 折扣率，如 0.95 表示 95 折 */
    private BigDecimal discountRate;

    /** 权益，逗号分隔 */
    private String benefits;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
