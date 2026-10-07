package com.geekmall.modules.merchant.support;

import com.geekmall.modules.aftersale.entity.AfterSale;

import java.util.List;

/**
 * 售后工单的「商家侧状态」模型。
 *
 * <p>买家侧 oms_aftersale.status 只有 0 处理中 / 1 已完成 / 2 已取消，
 * 商家端需要更细的状态机，因此在 merchant_status 列上承载。</p>
 *
 * <p><b>关键点：merchant_status 不能单独作为唯一依据。</b>
 * 买家撤销申请走的是买家侧接口，只会把 status 改成 2，不会碰 merchant_status；
 * 若商家侧只看 merchant_status，就会把「买家已撤销」的工单继续显示为「待买家退货」，
 * 商家会一直等一件永远不会寄回的商品。因此解析规则必须以买家侧的终态优先，
 * 且该规则在 Java 与 SQL 两侧必须完全一致（列表展示、Tab 计数、CAS 条件共用一套口径）。</p>
 */
public final class MerchantAftersaleStatus {

    public static final String PENDING = "pending";
    public static final String WAIT_RETURN = "wait_return";
    public static final String WAIT_RECEIVE = "wait_receive";
    public static final String DONE = "done";
    public static final String REJECTED = "rejected";
    /** 买家主动撤销申请（与「商家拒绝」区分开，两者都不该再由商家操作） */
    public static final String CANCELED = "canceled";

    /** 未终结（仍占用「售后中」）的状态。 */
    public static final List<String> NON_FINAL = List.of(PENDING, WAIT_RETURN, WAIT_RECEIVE);

    /** 未终结状态的 SQL IN 字面量，供各处拼接，避免手写字符串漂移。 */
    public static final String NON_FINAL_IN =
            "('" + PENDING + "', '" + WAIT_RETURN + "', '" + WAIT_RECEIVE + "')";

    /** 全部状态，供 Tab 渲染。 */
    public static final List<String> ALL = List.of(PENDING, WAIT_RETURN, WAIT_RECEIVE, DONE, REJECTED, CANCELED);

    /**
     * 商家侧状态的统一 SQL 口径。
     *
     * <p>解析优先级：商家已拒绝 &gt; 买家已撤销 &gt; 商家侧已落库的状态 &gt; 由买家侧状态推导。
     * 与 {@link #resolve(AfterSale)} 一一对应，改动必须同步。</p>
     */
    public static final String STATUS_SQL =
            "CASE "
                    + "WHEN merchant_status = '" + REJECTED + "' THEN '" + REJECTED + "' "
                    + "WHEN status = 2 THEN '" + CANCELED + "' "
                    + "WHEN merchant_status IS NOT NULL THEN merchant_status "
                    + "WHEN status = 1 THEN '" + DONE + "' "
                    + "ELSE '" + PENDING + "' END";

    private MerchantAftersaleStatus() {
    }

    /** Java 侧口径，必须与 {@link #STATUS_SQL} 保持一致。 */
    public static String resolve(AfterSale item) {
        if (item == null) {
            return PENDING;
        }
        if (REJECTED.equals(item.getMerchantStatus())) {
            return REJECTED;
        }
        if (item.getStatus() != null && item.getStatus() == 2) {
            return CANCELED;
        }
        if (item.getMerchantStatus() != null) {
            return item.getMerchantStatus();
        }
        return fromBuyerStatus(item.getStatus());
    }

    /** 仅依据买家侧数字状态推导（merchant_status 为空时的兜底）。 */
    public static String fromBuyerStatus(Integer status) {
        if (status == null) {
            return PENDING;
        }
        return switch (status) {
            case 1 -> DONE;
            case 2 -> CANCELED;
            default -> PENDING;
        };
    }
}
