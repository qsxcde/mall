package com.geekmall.modules.merchant.converter;

import com.geekmall.common.enums.PayMethod;
import com.geekmall.modules.merchant.support.MerchantOrderStatus;
import com.geekmall.modules.merchant.vo.BuyerVO;
import com.geekmall.modules.merchant.vo.MerchantOrderVO;
import com.geekmall.modules.merchant.vo.OrderProductVO;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.entity.OrderItem;
import com.geekmall.modules.user.entity.SysUser;

import java.util.Map;

/**
 * 商家端订单对象转换。
 */
public final class MerchantOrderConverter {

    private MerchantOrderConverter() {
    }

    /**
     * 订单 → 商家端 VO。
     *
     * @param order    订单主体
     * @param item     订单首个明细（商品快照），可为 null
     * @param buyer    买家，可为 null
     * @param address  解析后的地址快照，可为 null
     * @param afterSale 是否处于「售后中」
     */
    public static MerchantOrderVO toVO(Order order, OrderItem item, SysUser buyer,
                                       Map<String, Object> address, boolean afterSale) {
        if (order == null) {
            return null;
        }
        MerchantOrderVO vo = new MerchantOrderVO();
        vo.setId(order.getOrderNo());
        vo.setStatus(afterSale ? MerchantOrderStatus.AFTER : MerchantOrderStatus.of(order.getStatus()));
        vo.setQty(item == null ? 0 : item.getQty());
        vo.setGoodsAmount(order.getGoodsAmount());
        vo.setShipFee(order.getShippingFee());
        vo.setDiscount(order.getDiscount());
        vo.setPayAmount(order.getPayAmount());
        vo.setPayWay(order.getPayMethod());
        vo.setPayWayLabel(PayMethod.labelOf(order.getPayMethod()));
        vo.setExpress(order.getExpressCompany());
        vo.setWaybill(order.getWaybillNo());
        vo.setWarehouse(order.getWarehouse());
        vo.setNote(order.getRemark());
        vo.setMerchantNote(order.getMerchantNote());
        vo.setCreatedAt(order.getCreateTime());
        vo.setPaidAt(order.getPayTime());
        vo.setShipAt(order.getDeliverTime());
        vo.setRecvAt(order.getReceiveTime());
        if (item != null) {
            OrderProductVO product = new OrderProductVO();
            product.setId(item.getProductId());
            product.setName(item.getTitle());
            product.setCover(item.getCover());
            product.setSpec(item.getSpec());
            product.setPrice(item.getPrice());
            vo.setProduct(product);
        }
        if (buyer != null) {
            BuyerVO buyerVO = new BuyerVO();
            buyerVO.setName(buyer.getNickname() == null ? buyer.getUsername() : buyer.getNickname());
            buyerVO.setLevel(buyer.getLevelId() == null ? null : "V" + buyer.getLevelId());
            vo.setBuyer(buyerVO);
            vo.setPhone(buyer.getPhone());
        }
        if (address != null) {
            Object detail = address.get("detail");
            Object province = address.get("province");
            StringBuilder sb = new StringBuilder();
            if (province != null) {
                sb.append(province).append(' ');
            }
            if (detail != null) {
                sb.append(detail);
            }
            vo.setAddress(sb.toString().trim());
            if (vo.getPhone() == null && address.get("phone") != null) {
                vo.setPhone(String.valueOf(address.get("phone")));
            }
        }
        return vo;
    }
}
