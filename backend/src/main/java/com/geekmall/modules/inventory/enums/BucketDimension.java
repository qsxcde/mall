package com.geekmall.modules.inventory.enums;

import java.util.Arrays;

/**
 * 分桶维度：决定「按什么把一份库存拆成多个桶」。
 *
 * <p>维度值统一落在 {@code inv_bucket.dimension_value}，并按其语义回填冗余列
 * （warehouse / batch_no / expire_date / region），便于筛选与排序。</p>
 */
public enum BucketDimension {

    /** 按仓库拆分，维度值 = 仓库名，如「深圳总仓」。 */
    WAREHOUSE,

    /** 按入库批次拆分，维度值 = 批次号。 */
    BATCH,

    /** 按效期拆分，维度值 = 效期（yyyy-MM-dd）。 */
    EXPIRY,

    /** 按地区拆分，维度值 = 大区名，如「华南」。 */
    REGION;

    public static BucketDimension of(String name) {
        if (name == null || name.isBlank()) {
            return WAREHOUSE;
        }
        return Arrays.stream(values())
                .filter(v -> v.name().equalsIgnoreCase(name.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的维度：" + name));
    }
}
