package com.geekmall.modules.inventory.enums;

/**
 * 库存桶审计流水的业务类型。
 *
 * <p>出库 / 回滚两侧共用 {@code order_no} 作为幂等键，保证「重复调用不重复扣回」。</p>
 */
public enum BucketBizType {

    /** 把商品现有库存分配到各桶（只建桶，不改商品总库存）。 */
    ALLOCATE,

    /** 出库：按优先级从桶扣减。 */
    OUTBOUND,

    /** 调拨入库（目标桶 +）。 */
    TRANSFER_IN,

    /** 调拨出库（来源桶 -）。 */
    TRANSFER_OUT,

    /** 合并入库（目标桶 +）。 */
    MERGE_IN,

    /** 合并出库（来源桶清零）。 */
    MERGE_OUT,

    /** 出库回滚：把数量还回原来那一桶。 */
    ROLLBACK,

    /** 人工盘点调整。 */
    ADJUST
}
