package com.geekmall.modules.inventory.enums;

import java.util.Arrays;

/**
 * 出库挑桶优先级：决定一次出库「先扣哪个桶」。
 *
 * <p>与文档一致：始终是「先按规则选中一批有货的桶，再逐个 CAS 扣减」，
 * 桶空了才换下一个桶；桶被锁住不会跳行，只会在该桶上排队或由 CAS 失败重试。</p>
 */
public enum DeductPolicy {

    /** 先进先出：先入桶的（create_time 更早）先出。 */
    FIFO,

    /** 效期优先：效期最近的先出，无效期的排在最后。 */
    EXPIRY_FIRST,

    /** 人工优先级：按 bucket.priority 升序出库。 */
    MANUAL;

    public static DeductPolicy of(String name) {
        if (name == null || name.isBlank()) {
            return FIFO;
        }
        return Arrays.stream(values())
                .filter(v -> v.name().equalsIgnoreCase(name.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的出库策略：" + name));
    }
}
