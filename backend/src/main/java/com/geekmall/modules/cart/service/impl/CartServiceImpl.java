package com.geekmall.modules.cart.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.cart.dto.AddCartDTO;
import com.geekmall.modules.cart.dto.CartUpdateDTO;
import com.geekmall.modules.cart.entity.CartItem;
import com.geekmall.modules.cart.mapper.CartItemMapper;
import com.geekmall.modules.cart.service.CartService;
import com.geekmall.modules.cart.vo.CartItemVO;
import com.geekmall.modules.cart.vo.CartSummaryVO;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 购物车服务实现。
 */
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private static final int MAX_QTY = 99;
    private static final int ON_SHELF = 1;

    private final CartItemMapper cartItemMapper;
    private final ProductMapper productMapper;

    @Override
    public List<CartItemVO> list(Long userId) {
        return queryItems(userId).stream().map(this::toVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long add(Long userId, AddCartDTO dto) {
        Product product = productMapper.selectById(dto.getProductId());
        if (product == null || (product.getStatus() != null && product.getStatus() != ON_SHELF)) {
            throw BizException.of(ResultCode.NOT_FOUND);
        }
        String spec = StringUtils.hasText(dto.getSpec()) ? dto.getSpec() : product.getSpec();
        int qty = dto.getQty() == null ? 1 : dto.getQty();

        CartItem exist = findSameItem(userId, dto.getProductId(), spec);
        if (exist != null) {
            CartItem update = new CartItem();
            update.setId(exist.getId());
            update.setQty(Math.min(MAX_QTY, nvl(exist.getQty()) + qty));
            update.setChecked(1);
            cartItemMapper.updateById(update);
            return exist.getId();
        }

        CartItem item = new CartItem();
        item.setUserId(userId);
        item.setProductId(product.getId());
        item.setTitle(product.getTitle());
        item.setCover(product.getCover());
        item.setPrice(product.getPrice());
        item.setSpec(spec);
        item.setQty(qty);
        item.setChecked(1);
        cartItemMapper.insert(item);
        return item.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addBatch(Long userId, List<AddCartDTO> items) {
        if (items == null || items.isEmpty()) {
            throw BizException.of(ResultCode.CART_EMPTY);
        }
        items.forEach(item -> add(userId, item));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateQty(Long userId, Long itemId, CartUpdateDTO dto) {
        requireOwnItem(userId, itemId);
        if (dto.getQty() == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "缺少数量");
        }
        CartItem update = new CartItem();
        update.setId(itemId);
        update.setQty(dto.getQty());
        cartItemMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateChecked(Long userId, Long itemId, CartUpdateDTO dto) {
        requireOwnItem(userId, itemId);
        CartItem update = new CartItem();
        update.setId(itemId);
        update.setChecked(Boolean.TRUE.equals(dto.getChecked()) ? 1 : 0);
        cartItemMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void checkAll(Long userId, Boolean checked) {
        CartItem update = new CartItem();
        update.setChecked(Boolean.TRUE.equals(checked) ? 1 : 0);
        cartItemMapper.update(update, new LambdaQueryWrapper<CartItem>().eq(CartItem::getUserId, userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long userId, Long itemId) {
        requireOwnItem(userId, itemId);
        cartItemMapper.deleteById(itemId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeBatch(Long userId, Map<Long, Integer> cartItemQty) {
        if (cartItemQty == null || cartItemQty.isEmpty()) {
            return;
        }
        // 一条 DELETE 完成（由 N 条 select + N 条 delete 降为 1 条），
        // 并对每个购物车项附加「数量与下单时一致」的乐观守卫：
        // 下单之后若用户又加购合并进同一行，qty 会变，此时保留该行而不是误删。
        LambdaQueryWrapper<CartItem> wrapper = new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId);
        wrapper.and(w -> cartItemQty.forEach((itemId, qty) ->
                w.or(x -> x.eq(CartItem::getId, itemId).eq(CartItem::getQty, qty))));
        cartItemMapper.delete(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearChecked(Long userId) {
        cartItemMapper.delete(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getChecked, 1));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clear(Long userId) {
        cartItemMapper.delete(new LambdaQueryWrapper<CartItem>().eq(CartItem::getUserId, userId));
    }

    @Override
    public CartSummaryVO summary(Long userId) {
        List<CartItem> items = queryItems(userId);
        int totalQty = items.stream().mapToInt(i -> nvl(i.getQty())).sum();
        int checkedQty = items.stream().filter(this::isChecked).mapToInt(i -> nvl(i.getQty())).sum();
        BigDecimal checkedAmount = items.stream()
                .filter(this::isChecked)
                .map(i -> nvl(i.getPrice()).multiply(BigDecimal.valueOf(nvl(i.getQty()))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        CartSummaryVO vo = new CartSummaryVO();
        vo.setTotalQty(totalQty);
        vo.setCheckedQty(checkedQty);
        vo.setCheckedAmount(checkedAmount);
        vo.setItemCount(items.size());
        vo.setAllChecked(!items.isEmpty() && items.stream().allMatch(this::isChecked));
        return vo;
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    private List<CartItem> queryItems(Long userId) {
        return cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .orderByDesc(CartItem::getId));
    }

    private CartItem findSameItem(Long userId, Long productId, String spec) {
        LambdaQueryWrapper<CartItem> wrapper = new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getProductId, productId);
        if (spec == null) {
            wrapper.isNull(CartItem::getSpec);
        } else {
            wrapper.eq(CartItem::getSpec, spec);
        }
        return cartItemMapper.selectOne(wrapper.last("limit 1"));
    }

    private CartItem requireOwnItem(Long userId, Long itemId) {
        CartItem item = cartItemMapper.selectById(itemId);
        if (item == null || !item.getUserId().equals(userId)) {
            throw BizException.of(ResultCode.NOT_FOUND);
        }
        return item;
    }

    private CartItemVO toVO(CartItem item) {
        CartItemVO vo = new CartItemVO();
        vo.setId(item.getId());
        vo.setProductId(item.getProductId());
        vo.setTitle(item.getTitle());
        vo.setCover(item.getCover());
        vo.setPrice(item.getPrice());
        vo.setSpec(item.getSpec());
        vo.setQty(item.getQty());
        vo.setChecked(isChecked(item));
        vo.setAmount(nvl(item.getPrice()).multiply(BigDecimal.valueOf(nvl(item.getQty()))));
        return vo;
    }

    private boolean isChecked(CartItem item) {
        return item.getChecked() != null && item.getChecked() == 1;
    }

    private int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
