package com.geekmall.modules.merchant.converter;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.aftersale.entity.AfterSale;
import com.geekmall.modules.merchant.support.MerchantAftersaleStatus;
import com.geekmall.modules.merchant.vo.BuyerVO;
import com.geekmall.modules.merchant.vo.MerchantAftersaleVO;
import com.geekmall.modules.merchant.vo.OrderProductVO;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.entity.OrderItem;
import com.geekmall.modules.user.entity.SysUser;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家端售后工单转换（含进度时间轴构建）。
 */
public final class MerchantAftersaleConverter {

    /** 处理时限：待处理 48 小时、待买家寄回 168 小时。 */
    private static final int PENDING_LIMIT_HOURS = 48;
    private static final int RETURN_LIMIT_HOURS = 168;

    /**
     * 商家侧工单 ID 规则：以 oms_aftersale 主键派生，稳定且不暴露自增连续性。
     * 编解码集中在这里，避免 Service 与 Converter 各存一份常量而漂移。
     */
    public static final String ID_PREFIX = "AS";
    private static final long ID_BASE = 2026100500L;
    private static final long ID_STEP = 3L;

    private MerchantAftersaleConverter() {
    }

    /** 主键 → 对外的工单号。 */
    public static String toId(Long pk) {
        return ID_PREFIX + (ID_BASE + pk * ID_STEP);
    }

    /** 对外的工单号 → 主键；格式非法时抛业务异常。 */
    public static Long parseId(String id) {
        if (!StringUtils.hasText(id) || !id.startsWith(ID_PREFIX)) {
            throw new BizException(ResultCode.PARAM_ERROR, "非法的工单号：" + id);
        }
        try {
            return (Long.parseLong(id.substring(ID_PREFIX.length())) - ID_BASE) / ID_STEP;
        } catch (NumberFormatException e) {
            throw new BizException(ResultCode.PARAM_ERROR, "非法的工单号：" + id);
        }
    }

    public static MerchantAftersaleVO toVO(AfterSale item, Order order, OrderItem orderItem, SysUser buyer) {
        if (item == null) {
            return null;
        }
        MerchantAftersaleVO vo = new MerchantAftersaleVO();
        vo.setId(toId(item.getId()));
        vo.setOrderId(item.getOrderNo());
        vo.setType(item.getType());
        String status = resolveStatus(item);
        vo.setStatus(status);
        vo.setReason(item.getReason());
        vo.setRefundAmount(item.getAmount());
        vo.setApplyAt(item.getCreateTime());
        vo.setRejectReason(item.getRejectReason());
        vo.setImages(countImages(item.getImages()));
        vo.setPhone(item.getPhone());
        // 寄回物流信息当前未落库，先返回空字符串占位
        vo.setReturnExpress("");
        vo.setReturnWaybill("");
        if (order != null) {
            vo.setOrderAmount(order.getPayAmount());
        }
        // 件数只与订单商品行有关，与订单主体是否存在无关，故放在分支外统一赋值
        vo.setQty(orderItem == null ? 0 : orderItem.getQty());
        if (orderItem != null) {
            OrderProductVO product = new OrderProductVO();
            product.setId(orderItem.getProductId());
            product.setName(orderItem.getTitle());
            product.setCover(orderItem.getCover());
            product.setSpec(orderItem.getSpec());
            product.setPrice(orderItem.getPrice());
            vo.setProduct(product);
        }
        if (buyer != null) {
            BuyerVO buyerVO = new BuyerVO();
            buyerVO.setName(buyer.getNickname() == null ? buyer.getUsername() : buyer.getNickname());
            buyerVO.setLevel(buyer.getLevelId() == null ? null : "V" + buyer.getLevelId());
            vo.setBuyer(buyerVO);
            if (vo.getPhone() == null) {
                vo.setPhone(buyer.getPhone());
            }
        }
        vo.setDeadline(deadline(status, item.getCreateTime()));
        vo.setTimeline(buildTimeline(status, item.getCreateTime()));
        return vo;
    }

    /** 状态解析统一走 {@link MerchantAftersaleStatus#resolve}，保证与 SQL 口径、CAS 条件一致。 */
    private static String resolveStatus(AfterSale item) {
        return MerchantAftersaleStatus.resolve(item);
    }

    private static Integer countImages(String images) {
        if (!StringUtils.hasText(images)) {
            return 0;
        }
        return (int) java.util.Arrays.stream(images.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .count();
    }

    private static LocalDateTime deadline(String status, LocalDateTime applyAt) {
        if (applyAt == null) {
            return null;
        }
        return switch (status) {
            case MerchantAftersaleStatus.PENDING -> applyAt.plusHours(PENDING_LIMIT_HOURS);
            case MerchantAftersaleStatus.WAIT_RETURN, MerchantAftersaleStatus.WAIT_RECEIVE ->
                    applyAt.plusHours(RETURN_LIMIT_HOURS);
            default -> null;
        };
    }

    /** 依据当前状态重建进度时间轴（与前端 mock 的口径一致）。 */
    private static List<Map<String, Object>> buildTimeline(String status, LocalDateTime applyAt) {
        List<Map<String, Object>> timeline = new ArrayList<>();
        timeline.add(node("买家提交申请", applyAt, true, null));
        if (applyAt == null) {
            return timeline;
        }
        LocalDateTime agreedAt = applyAt.plusHours(6);

        if (MerchantAftersaleStatus.PENDING.equals(status)) {
            timeline.add(node("商家处理", null, false, "待响应"));
            return timeline;
        }
        if (MerchantAftersaleStatus.REJECTED.equals(status)) {
            timeline.add(node("商家拒绝申请", agreedAt, true, null));
            timeline.add(node("买家可申请平台介入", null, false, "待买家决定"));
            return timeline;
        }
        timeline.add(node("商家同意售后", agreedAt, true, null));
        if (MerchantAftersaleStatus.WAIT_RETURN.equals(status)) {
            timeline.add(node("买家寄回商品", null, false, "待买家寄回"));
            return timeline;
        }
        timeline.add(node("买家已寄回商品", applyAt.plusHours(30), true, null));
        if (MerchantAftersaleStatus.WAIT_RECEIVE.equals(status)) {
            timeline.add(node("商家确认收货", null, false, "待商家收货"));
            return timeline;
        }
        timeline.add(node("商家确认收货", applyAt.plusHours(48), true, null));
        timeline.add(node("退款已完成", applyAt.plusHours(50), true, null));
        return timeline;
    }

    private static Map<String, Object> node(String title, LocalDateTime time, boolean done, String tip) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("title", title);
        node.put("time", time);
        node.put("done", done);
        if (tip != null) {
            node.put("tip", tip);
        }
        return node;
    }
}
