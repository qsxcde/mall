package com.geekmall.modules.aftersale.enums;

import com.geekmall.modules.aftersale.entity.AfterSale;

/**
 * 买家侧售后状态（展示口径）。
 *
 * <p><b>为什么不能只看 {@code oms_aftersale.status}：</b>
 * 该列只承担三态语义（0 处理中 / 1 已完成 / 2 已取消），而商家侧真正的处置结果
 * 落在 {@code merchant_status} 上（pending / wait_return / wait_receive / done / rejected）。
 * 商家拒绝时为了让工单终结，会把 {@code status} 一并写成 2 —— 如果买家侧只看数字列，
 * 就会把「<b>商家已拒绝</b>」显示成「<b>买家已取消</b>」：用户看到的是自己从没做过的操作，
 * 且拒绝理由完全不可见。</p>
 *
 * <p>因此买家侧口径与商家侧 {@code MerchantAftersaleStatus} 取值一致，仅承担展示语义；
 * 解析优先级：<b>商家拒绝 &gt; 买家撤销 &gt; 商家侧未终结状态 &gt; 买家侧数字状态</b>。</p>
 */
public enum BuyerAfterSaleStatus {

    /** 商家已拒绝（含理由），买家可考虑平台介入。 */
    REJECTED("已拒绝", "商家未同意本次售后，可在详情查看拒绝理由"),
    /** 商家同意退货退款，等待买家寄回商品。 */
    WAIT_RETURN("待寄回", "商家已同意，请将商品寄回后在详情页点击「我已寄回」"),
    /** 买家已寄回，等待商家确认收货。 */
    WAIT_RECEIVE("待收货", "商品已寄出，等待商家确认收货"),
    /** 售后完成（退款已原路退回）。 */
    DONE("已完成", "本次售后已完成，退款将原路退回"),
    /** 买家主动撤销。 */
    CANCELED("已取消", "你已撤销本次售后申请"),
    /** 处理中。 */
    PENDING("处理中", "商家正在处理你的售后申请，请留意进度");

    private final String text;
    private final String tip;

    BuyerAfterSaleStatus(String text, String tip) {
        this.text = text;
        this.tip = tip;
    }

    /** 状态文案，直接用于列表与详情。 */
    public String getText() {
        return text;
    }

    /** 一句话说明，供详情页状态头展示。 */
    public String getTip() {
        return tip;
    }

    /**
     * 是否仍可被买家撤销。
     *
     * <p>「待寄回」也是可撤销的：此时商品还在买家手里，撤销等于放弃退货，
     * 对双方都没有资金损失。一旦买家寄出（待收货），撤销就应由商家侧拦截。</p>
     */
    public boolean isCancelable() {
        return this == PENDING || this == WAIT_RETURN;
    }

    /** 是否仍在商家处理/流转中（用于「处理中」类筛选与文案）。 */
    public boolean isDoing() {
        return this == PENDING || this == WAIT_RETURN || this == WAIT_RECEIVE;
    }

    /** 是否为买家主动撤销（区别于商家拒绝）。 */
    public boolean isCanceledByBuyer() {
        return this == CANCELED;
    }

    /**
     * 解析买家侧展示状态；入参为 null 时按「处理中」兜底。
     *
     * <p><b>优先级（顺序不可调换）</b>：商家拒绝 &gt; 买家撤销 &gt; 商家侧未终结状态 &gt; 数字状态。
     * 买家撤销只把 {@code status} 写成 2、<b>不会清空</b> {@code merchant_status}
     * （商家同意退货后买家改主意放弃退货，merchant_status 仍是 wait_return）。
     * 若让商家侧状态优先，撤销后的工单会一直显示「待寄回」，
     * 商家也会继续等一件永远不会寄回的商品 —— 与 {@code MerchantAftersaleStatus}
     * 的解析顺序必须保持完全一致。</p>
     */
    public static BuyerAfterSaleStatus resolve(AfterSale item) {
        if (item == null) {
            return PENDING;
        }
        String merchantStatus = item.getMerchantStatus();
        if (REJECTED.name().equalsIgnoreCase(merchantStatus)) {
            return REJECTED;
        }
        // 买家撤销优先于商家侧落库状态
        Integer status = item.getStatus();
        if (status != null && status == 2) {
            return CANCELED;
        }
        if (merchantStatus != null) {
            switch (merchantStatus) {
                case "wait_return":
                    return WAIT_RETURN;
                case "wait_receive":
                    return WAIT_RECEIVE;
                case "done":
                    return DONE;
                default:
                    break;
            }
        }
        if (status == null) {
            return PENDING;
        }
        return switch (status) {
            case 1 -> DONE;
            default -> PENDING;
        };
    }
}
