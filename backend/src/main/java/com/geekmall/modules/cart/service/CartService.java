package com.geekmall.modules.cart.service;

import com.geekmall.modules.cart.dto.AddCartDTO;
import com.geekmall.modules.cart.dto.CartUpdateDTO;
import com.geekmall.modules.cart.vo.CartItemVO;
import com.geekmall.modules.cart.vo.CartSummaryVO;

import java.util.List;
import java.util.Map;

/**
 * 购物车服务。
 */
public interface CartService {

    List<CartItemVO> list(Long userId);

    /** 加入购物车：同商品同规格自动累加数量。 */
    Long add(Long userId, AddCartDTO dto);

    /** 批量加入（订单详情「再次购买」）。 */
    void addBatch(Long userId, List<AddCartDTO> items);

    void updateQty(Long userId, Long itemId, CartUpdateDTO dto);

    void updateChecked(Long userId, Long itemId, CartUpdateDTO dto);

    /** 全选 / 取消全选。 */
    void checkAll(Long userId, Boolean checked);

    void remove(Long userId, Long itemId);

    /**
     * 批量删除购物车项（下单成功后清理，见 P0-2）。
     *
     * <p>只删除「数量仍与下单时一致」的行：购物车对同商品是累加合并、复用同一行 id，
     * 若下单之后用户又加购合并进来了，该行的数量会变，此时必须保留而不是删掉。</p>
     *
     * @param cartItemQty 购物车项 ID → 下单时的数量
     */
    void removeBatch(Long userId, Map<Long, Integer> cartItemQty);

    /** 删除已勾选商品。 */
    void clearChecked(Long userId);

    /** 清空购物车。 */
    void clear(Long userId);

    CartSummaryVO summary(Long userId);
}
