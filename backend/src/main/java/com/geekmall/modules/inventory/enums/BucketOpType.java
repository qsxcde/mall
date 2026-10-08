package com.geekmall.modules.inventory.enums;

/**
 * 桶间操作类型。调拨与合并都只在桶之间搬动库存，不改变商品总库存。
 */
public enum BucketOpType {

    /** 跨桶调拨：从来源桶搬指定数量到目标桶。 */
    TRANSFER,

    /** 跨桶合并：把多个来源桶的余量全部并入目标桶。 */
    MERGE
}
