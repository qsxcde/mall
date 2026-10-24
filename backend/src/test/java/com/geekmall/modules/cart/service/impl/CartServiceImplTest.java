package com.geekmall.modules.cart.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.geekmall.common.exception.BizException;
import com.geekmall.modules.cart.dto.AddCartDTO;
import com.geekmall.modules.cart.dto.CartUpdateDTO;
import com.geekmall.modules.cart.entity.CartItem;
import com.geekmall.modules.cart.mapper.CartItemMapper;
import com.geekmall.modules.cart.vo.CartItemVO;
import com.geekmall.modules.cart.vo.CartSummaryVO;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.service.ProductStockService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 购物车服务单元测试。
 *
 * <p>重点守住两类风险：<b>越权</b>（改 / 删他人购物车项必须 404）与
 * <b>数量口径</b>（合并加购封顶 99、金额 = 单价 × 数量且空值按 0）。</p>
 */
@ExtendWith(MockitoExtension.class)
class CartServiceImplTest {

    private static final Long USER_ID = 42L;

    @Mock
    private CartItemMapper cartItemMapper;

    @Mock
    private ProductStockService productStockService;

    @InjectMocks
    private CartServiceImpl cartService;

    private static Product product(long id, String spec, String price) {
        Product product = new Product();
        product.setId(id);
        product.setTitle("测试商品");
        product.setCover("cover.png");
        product.setPrice(new BigDecimal(price));
        product.setSpec(spec);
        product.setStatus(1);
        return product;
    }

    private static CartItem item(long id, long userId, String price, Integer qty, Integer checked) {
        CartItem item = new CartItem();
        item.setId(id);
        item.setUserId(userId);
        item.setProductId(1L);
        item.setTitle("测试商品");
        item.setPrice(new BigDecimal(price));
        item.setQty(qty);
        item.setChecked(checked);
        return item;
    }

    @Nested
    @DisplayName("加购")
    class Add {

        @Test
        @DisplayName("商品不存在或已下架 → 404")
        void shouldRejectMissingOrOffShelfProduct() {
            when(productStockService.findById(1L)).thenReturn(null);
            assertThatThrownBy(() -> cartService.add(USER_ID, dto(1L, null, 1))).isInstanceOf(BizException.class);

            Product offShelf = product(1L, "黑色", "99.00");
            offShelf.setStatus(0);
            when(productStockService.findById(1L)).thenReturn(offShelf);
            assertThatThrownBy(() -> cartService.add(USER_ID, dto(1L, null, 1))).isInstanceOf(BizException.class);
        }

        @Test
        @DisplayName("新商品插入：spec 缺省取商品规格、数量缺省 1")
        void shouldInsertNewItemWithDefaults() {
            when(productStockService.findById(1L)).thenReturn(product(1L, "黑色", "99.00"));
            when(cartItemMapper.selectOne(any())).thenReturn(null);
            when(cartItemMapper.insert(any(CartItem.class))).thenReturn(1);

            cartService.add(USER_ID, dto(1L, null, null));

            ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
            verify(cartItemMapper).insert(captor.capture());
            assertThat(captor.getValue().getSpec()).isEqualTo("黑色");
            assertThat(captor.getValue().getQty()).isEqualTo(1);
            assertThat(captor.getValue().getChecked()).isEqualTo(1);
            assertThat(captor.getValue().getPrice()).isEqualByComparingTo("99.00");
        }

        @Test
        @DisplayName("同款合并加购：数量叠加且封顶 99，勾选状态置回 1")
        void shouldMergeSameItemAndCapQty() {
            when(productStockService.findById(1L)).thenReturn(product(1L, "黑色", "99.00"));
            CartItem exist = item(7L, USER_ID, "99.00", 98, 0);
            when(cartItemMapper.selectOne(any())).thenReturn(exist);

            cartService.add(USER_ID, dto(1L, "黑色", 5));

            ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
            verify(cartItemMapper).updateById(captor.capture());
            assertThat(captor.getValue().getId()).isEqualTo(7L);
            assertThat(captor.getValue().getQty()).isEqualTo(99);
            assertThat(captor.getValue().getChecked()).isEqualTo(1);
            verify(cartItemMapper, never()).insert(any(CartItem.class));
        }

        @Test
        @DisplayName("批量加购：空列表 → CART_EMPTY")
        void shouldRejectEmptyBatch() {
            assertThatThrownBy(() -> cartService.addBatch(USER_ID, List.of()))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("购物车");
            assertThatThrownBy(() -> cartService.addBatch(USER_ID, null)).isInstanceOf(BizException.class);
        }
    }

    @Nested
    @DisplayName("修改与删除（含越权守卫）")
    class UpdateAndRemove {

        @Test
        @DisplayName("改他人购物车项 → 404（不泄露存在性）")
        void shouldRejectForeignItem() {
            when(cartItemMapper.selectById(5L)).thenReturn(item(5L, 999L, "9.90", 1, 1));

            CartUpdateDTO dto = new CartUpdateDTO();
            dto.setQty(2);
            assertThatThrownBy(() -> cartService.updateQty(USER_ID, 5L, dto)).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> cartService.remove(USER_ID, 5L)).isInstanceOf(BizException.class);
            verify(cartItemMapper, never()).updateById(any(CartItem.class));
            verify(cartItemMapper, never()).deleteById(anyLong());
        }

        @Test
        @DisplayName("改数量缺 qty → PARAM_ERROR")
        void shouldRejectNullQty() {
            when(cartItemMapper.selectById(5L)).thenReturn(item(5L, USER_ID, "9.90", 1, 1));
            assertThatThrownBy(() -> cartService.updateQty(USER_ID, 5L, new CartUpdateDTO()))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("数量");
        }

        @Test
        @DisplayName("勾选 / 取消勾选正常落库")
        void shouldUpdateChecked() {
            when(cartItemMapper.selectById(5L)).thenReturn(item(5L, USER_ID, "9.90", 1, 0));

            CartUpdateDTO dto = new CartUpdateDTO();
            dto.setChecked(true);
            cartService.updateChecked(USER_ID, 5L, dto);

            ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
            verify(cartItemMapper).updateById(captor.capture());
            assertThat(captor.getValue().getChecked()).isEqualTo(1);
        }

        @Test
        @DisplayName("下单清项带数量守卫：只删「数量与下单时一致」的行")
        void shouldRemoveBatchWithQtyGuard() {
            cartService.removeBatch(USER_ID, Map.of(5L, 2, 6L, 1));
            verify(cartItemMapper).delete(any());
            // 空入参不触发任何删除
            cartService.removeBatch(USER_ID, Map.of());
            verify(cartItemMapper, times(1)).delete(any());
        }
    }

    @Nested
    @DisplayName("汇总")
    class Summary {

        @Test
        @DisplayName("汇总只计勾选项金额，数量口径含未勾选")
        void shouldSummarizeCheckedAmount() {
            when(cartItemMapper.selectList(any()))
                    .thenReturn(List.of(
                            item(1L, USER_ID, "10.00", 2, 1),
                            item(2L, USER_ID, "5.00", 3, 0),
                            item(3L, USER_ID, "1.00", 1, 1)));

            CartSummaryVO summary = cartService.summary(USER_ID);

            assertThat(summary.getTotalQty()).isEqualTo(6);
            assertThat(summary.getCheckedQty()).isEqualTo(3);
            assertThat(summary.getCheckedAmount()).isEqualByComparingTo("21.00");
            assertThat(summary.getItemCount()).isEqualTo(3);
            assertThat(summary.getAllChecked()).isFalse();
        }

        @Test
        @DisplayName("空购物车：金额为 0 且 allChecked=false（不能把「空」当「全选」）")
        void emptyCartIsNotAllChecked() {
            when(cartItemMapper.selectList(any())).thenReturn(List.of());

            CartSummaryVO summary = cartService.summary(USER_ID);

            assertThat(summary.getCheckedAmount()).isEqualByComparingTo("0");
            assertThat(summary.getAllChecked()).isFalse();
        }

        @Test
        @DisplayName("列表金额 = 单价 × 数量，空值按 0 兜底")
        void listAmountHandlesNulls() {
            when(cartItemMapper.selectList(any())).thenReturn(List.of(item(1L, USER_ID, "10.00", null, null)));

            List<CartItemVO> list = cartService.list(USER_ID);

            assertThat(list).hasSize(1);
            assertThat(list.get(0).getAmount()).isEqualByComparingTo("0");
            assertThat(list.get(0).getChecked()).isFalse();
        }
    }

    private static AddCartDTO dto(Long productId, String spec, Integer qty) {
        AddCartDTO dto = new AddCartDTO();
        dto.setProductId(productId);
        dto.setSpec(spec);
        dto.setQty(qty);
        return dto;
    }
}
