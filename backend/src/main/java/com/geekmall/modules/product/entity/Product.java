package com.geekmall.modules.product.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品表 pms_product。
 *
 * <p>categoryKey / parentKey / categoryName 为便于列表查询而做的冗余字段，
 * 避免每次列表都 join 分类表。</p>
 */
@Data
@TableName("pms_product")
public class Product implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属店铺（商家端数据隔离维度） */
    private Long shopId;

    private String title;

    private Long categoryId;

    /** 叶子分类 key */
    private String categoryKey;

    /** 顶级分类 key，用于分类页 cat 参数筛选 */
    private String parentKey;

    private String categoryName;

    private Long brandId;

    private String brandName;

    private BigDecimal price;

    private BigDecimal oldPrice;

    /** 成本价（仅商家端可见） */
    private BigDecimal cost;

    /** 默认规格描述，如「256G 钛金属」 */
    private String spec;

    /** 库存（接入 SKU 后改为 SKU 维度汇总） */
    private Integer stock;

    /** 安全库存线，低于该值触发商家端预警 */
    private Integer safeStock;

    private Integer sales;

    /** 浏览量 */
    private Integer views;

    private BigDecimal rating;

    private String cover;

    /** 逗号分隔的标签，如「热销,包邮」 */
    private String tags;

    private Integer isHot;

    private Integer isNew;

    /** 发布时间（厂商公开发布/开售时间，区别于入库时间 createTime） */
    private LocalDateTime releaseTime;

    /** 1 上架 0 下架（买家侧可见性） */
    private Integer status;

    /** 商家侧状态：on 在售 / ware 仓库中 / audit 审核中 / sold 售罄 / off 下架 / trash 回收站 */
    private String merchantStatus;

    /**
     * 乐观锁版本号。
     *
     * <p>仅保护商家端「编辑商品」这条读改写路径：提交时若版本不匹配说明已被他人改过，
     * 更新影响行数为 0，由业务层提示刷新，避免后提交者整体覆盖前者的改动。</p>
     */
    @Version
    private Integer version;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
