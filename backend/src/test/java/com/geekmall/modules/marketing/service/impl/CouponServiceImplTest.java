package com.geekmall.modules.marketing.service.impl;

import com.geekmall.common.exception.BizException;
import com.geekmall.modules.marketing.entity.CouponTemplate;
import com.geekmall.modules.marketing.entity.UserCoupon;
import com.geekmall.modules.marketing.mapper.CouponTemplateMapper;
import com.geekmall.modules.marketing.mapper.UserCouponMapper;
import com.geekmall.modules.marketing.vo.UserCouponVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 优惠券服务单元测试。
 *
 * <p>折扣金额是直接的资损点：算多了平台亏、算少了用户投诉，
 * 因此对满减、折扣、免运费、门槛、有效期逐项做边界断言。</p>
 */
@ExtendWith(MockitoExtension.class)
class CouponServiceImplTest {

    @Mock
    private UserCouponMapper userCouponMapper;
    @Mock
    private CouponTemplateMapper templateMapper;

    @InjectMocks
    private CouponServiceImpl couponService;

    private static CouponTemplate template(Long id, String type, String amount, String threshold) {
        CouponTemplate template = new CouponTemplate();
        template.setId(id);
        template.setName("测试券");
        template.setType(type);
        template.setAmount(amount == null ? null : new BigDecimal(amount));
        template.setThreshold(threshold == null ? null : new BigDecimal(threshold));
        template.setUnit("¥");
        template.setStatus(1);
        template.setPerLimit(1);
        template.setStock(100);
        template.setValidFrom(LocalDate.now().minusDays(1));
        template.setValidTo(LocalDate.now().plusDays(30));
        return template;
    }

    private static UserCouponVO couponVO(String type, String amount, String threshold) {
        UserCouponVO vo = new UserCouponVO();
        vo.setId(1L);
        vo.setType(type);
        vo.setAmount(amount == null ? null : new BigDecimal(amount));
        vo.setThreshold(threshold == null ? null : new BigDecimal(threshold));
        return vo;
    }

    @Nested
    @DisplayName("折扣计算")
    class DiscountCalculation {

        @Test
        @DisplayName("满减券抵扣额不超过商品金额")
        void fullReductionShouldNotExceedGoodsAmount() {
            assertThat(couponService.calcGoodsDiscount(couponVO("full", "50", "0"), new BigDecimal("100")))
                    .isEqualByComparingTo("50.00");
            // 券面 200 但商品只有 100，最多抵 100
            assertThat(couponService.calcGoodsDiscount(couponVO("full", "200", "0"), new BigDecimal("100")))
                    .isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("折扣券按 amount=9 表示 9 折")
        void percentShouldApplyRate() {
            assertThat(couponService.calcGoodsDiscount(couponVO("percent", "9", "0"), new BigDecimal("100")))
                    .isEqualByComparingTo("10.00");
            assertThat(couponService.calcGoodsDiscount(couponVO("percent", "8.5", "0"), new BigDecimal("200")))
                    .isEqualByComparingTo("30.00");
        }

        @Test
        @DisplayName("免运费券不减商品金额（运费由交易域置零）")
        void shippingCouponShouldNotReduceGoodsAmount() {
            assertThat(couponService.calcGoodsDiscount(couponVO("shipping", "18", "0"), new BigDecimal("100")))
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("类型缺失时按满减处理")
        void nullTypeFallsBackToFullReduction() {
            assertThat(couponService.calcGoodsDiscount(couponVO(null, "20", "0"), new BigDecimal("100")))
                    .isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("入参为空时返回 0，不抛异常")
        void nullInputsReturnZero() {
            assertThat(couponService.calcGoodsDiscount(null, BigDecimal.TEN)).isEqualByComparingTo("0.00");
            assertThat(couponService.calcGoodsDiscount(couponVO("full", "10", "0"), null))
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("结果保留 2 位小数")
        void shouldScaleToTwoDecimals() {
            assertThat(couponService.calcGoodsDiscount(couponVO("percent", "8.88", "0"), new BigDecimal("99.99")))
                    .isEqualByComparingTo("11.20");
        }
    }

    @Nested
    @DisplayName("领取优惠券")
    class Claim {

        @Test
        @DisplayName("券模板不存在或已下架时拒绝")
        void shouldRejectMissingTemplate() {
            when(templateMapper.selectById(1L)).thenReturn(null);
            assertThatThrownBy(() -> couponService.claim(1L, 1L))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("优惠券不存在或已下架");

            CouponTemplate offline = template(1L, "full", "10", "0");
            offline.setStatus(0);
            when(templateMapper.selectById(1L)).thenReturn(offline);
            assertThatThrownBy(() -> couponService.claim(1L, 1L))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("优惠券不存在或已下架");
        }

        @Test
        @DisplayName("已抢光的券拒绝领取")
        void shouldRejectSoldout() {
            CouponTemplate soldout = template(1L, "full", "10", "0");
            soldout.setSoldout(1);
            when(templateMapper.selectById(1L)).thenReturn(soldout);

            assertThatThrownBy(() -> couponService.claim(1L, 1L))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("已被抢光");
            verify(templateMapper, never()).deductStock(anyLong());
        }

        @Test
        @DisplayName("超出每人限领张数时拒绝")
        void shouldRespectPerLimit() {
            CouponTemplate limited = template(1L, "full", "10", "0");
            limited.setPerLimit(1);
            when(templateMapper.selectById(1L)).thenReturn(limited);
            when(userCouponMapper.selectCount(any())).thenReturn(1L);

            assertThatThrownBy(() -> couponService.claim(1L, 1L))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("每人限领 1 张");
            verify(templateMapper, never()).deductStock(anyLong());
        }

        @Test
        @DisplayName("库存扣减失败（并发抢光）时拒绝，且不写入用户券")
        void shouldRejectWhenStockExhausted() {
            CouponTemplate template = template(1L, "full", "10", "0");
            template.setPerLimit(1);
            when(templateMapper.selectById(1L)).thenReturn(template);
            when(userCouponMapper.selectCount(any())).thenReturn(0L);
            when(templateMapper.deductStock(1L)).thenReturn(0);

            assertThatThrownBy(() -> couponService.claim(1L, 1L))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("已被抢光");
            verify(userCouponMapper, never()).insert(any(UserCoupon.class));
        }

        @Test
        @DisplayName("领取成功：先扣库存，再写用户券，最后刷新领取百分比")
        void shouldClaimSuccessfully() {
            CouponTemplate template = template(1L, "full", "10", "0");
            template.setPerLimit(2);
            when(templateMapper.selectById(1L)).thenReturn(template);
            when(userCouponMapper.selectCount(any())).thenReturn(0L);
            when(templateMapper.deductStock(1L)).thenReturn(1);

            couponService.claim(9L, 1L);

            ArgumentCaptor<UserCoupon> captor = ArgumentCaptor.forClass(UserCoupon.class);
            verify(userCouponMapper).insert(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(9L);
            assertThat(captor.getValue().getTemplateId()).isEqualTo(1L);
            assertThat(captor.getValue().getStatus()).isZero();
            assertThat(captor.getValue().getReceiveTime()).isNotNull();
            verify(templateMapper).refreshPercent(1L);
        }

        @Test
        @DisplayName("唯一索引兜底并发重复领取时转为友好业务异常（回滚同时回退库存）")
        void shouldTranslateDuplicateKey() {
            CouponTemplate template = template(1L, "full", "10", "0");
            when(templateMapper.selectById(1L)).thenReturn(template);
            when(userCouponMapper.selectCount(any())).thenReturn(0L);
            when(templateMapper.deductStock(1L)).thenReturn(1);
            when(userCouponMapper.insert(any(UserCoupon.class)))
                    .thenThrow(new DuplicateKeyException("uk_user_template"));

            assertThatThrownBy(() -> couponService.claim(9L, 1L))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("已领取过该券");
        }
    }

    @Nested
    @DisplayName("结算用券校验")
    class RequireUsable {

        @Test
        @DisplayName("券不存在或不属于当前用户时拒绝")
        void shouldRejectForeignCoupon() {
            when(userCouponMapper.selectById(1L)).thenReturn(null);
            assertThatThrownBy(() -> couponService.requireUsable(1L, 1L, BigDecimal.TEN))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("优惠券不存在");

            UserCoupon other = new UserCoupon();
            other.setId(1L);
            other.setUserId(2L);
            other.setTemplateId(1L);
            other.setStatus(0);
            when(userCouponMapper.selectById(1L)).thenReturn(other);
            assertThatThrownBy(() -> couponService.requireUsable(1L, 1L, BigDecimal.TEN))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("优惠券不存在");
        }

        @Test
        @DisplayName("模板失效时拒绝")
        void shouldRejectMissingTemplate() {
            UserCoupon coupon = new UserCoupon();
            coupon.setId(1L);
            coupon.setUserId(1L);
            coupon.setTemplateId(5L);
            coupon.setStatus(0);
            when(userCouponMapper.selectById(1L)).thenReturn(coupon);
            when(templateMapper.selectById(5L)).thenReturn(null);

            assertThatThrownBy(() -> couponService.requireUsable(1L, 1L, BigDecimal.TEN))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("模板已失效");
        }

        @Test
        @DisplayName("未达使用门槛时给出明确原因")
        void shouldRejectBelowThreshold() {
            UserCoupon coupon = new UserCoupon();
            coupon.setId(1L);
            coupon.setUserId(1L);
            coupon.setTemplateId(5L);
            coupon.setStatus(0);
            when(userCouponMapper.selectById(1L)).thenReturn(coupon);
            when(templateMapper.selectById(5L)).thenReturn(template(5L, "full", "50", "999"));

            assertThatThrownBy(() -> couponService.requireUsable(1L, 1L, new BigDecimal("100")))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("未满足使用门槛");
        }

        @Test
        @DisplayName("已过期时给出明确原因")
        void shouldRejectExpired() {
            UserCoupon coupon = new UserCoupon();
            coupon.setId(1L);
            coupon.setUserId(1L);
            coupon.setTemplateId(5L);
            coupon.setStatus(0);
            CouponTemplate expired = template(5L, "full", "50", "0");
            expired.setValidTo(LocalDate.now().minusDays(1));
            when(userCouponMapper.selectById(1L)).thenReturn(coupon);
            when(templateMapper.selectById(5L)).thenReturn(expired);

            assertThatThrownBy(() -> couponService.requireUsable(1L, 1L, new BigDecimal("100")))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("已过期");
        }

        @Test
        @DisplayName("券已使用时给出明确原因")
        void shouldRejectUsed() {
            UserCoupon coupon = new UserCoupon();
            coupon.setId(1L);
            coupon.setUserId(1L);
            coupon.setTemplateId(5L);
            coupon.setStatus(1);
            when(userCouponMapper.selectById(1L)).thenReturn(coupon);
            when(templateMapper.selectById(5L)).thenReturn(template(5L, "full", "50", "0"));

            assertThatThrownBy(() -> couponService.requireUsable(1L, 1L, new BigDecimal("100")))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("已使用或已过期");
        }

        @Test
        @DisplayName("满足条件时返回带可用标记的券")
        void shouldReturnUsableCoupon() {
            UserCoupon coupon = new UserCoupon();
            coupon.setId(1L);
            coupon.setUserId(1L);
            coupon.setTemplateId(5L);
            coupon.setStatus(0);
            when(userCouponMapper.selectById(1L)).thenReturn(coupon);
            when(templateMapper.selectById(5L)).thenReturn(template(5L, "full", "50", "100"));

            UserCouponVO vo = couponService.requireUsable(1L, 1L, new BigDecimal("200"));

            assertThat(vo.getUsable()).isTrue();
            assertThat(vo.getUnusableReason()).isNull();
            assertThat(vo.getAmount()).isEqualByComparingTo("50");
            assertThat(vo.getStatusText()).isEqualTo("未使用");
        }
    }

    @Nested
    @DisplayName("结算页可用券列表")
    class CheckoutList {

        @Test
        @DisplayName("无券时返回空列表")
        void shouldReturnEmpty() {
            when(userCouponMapper.selectList(any())).thenReturn(List.of());
            assertThat(couponService.listForCheckout(1L, BigDecimal.TEN)).isEmpty();
        }

        @Test
        @DisplayName("一次性批量查模板，避免逐条查询（N+1）")
        void shouldBatchLoadTemplates() {
            UserCoupon first = new UserCoupon();
            first.setId(1L);
            first.setUserId(1L);
            first.setTemplateId(5L);
            first.setStatus(0);
            UserCoupon second = new UserCoupon();
            second.setId(2L);
            second.setUserId(1L);
            second.setTemplateId(6L);
            second.setStatus(0);
            when(userCouponMapper.selectList(any())).thenReturn(List.of(first, second));
            when(templateMapper.selectBatchIds(any())).thenReturn(List.of(
                    template(5L, "full", "50", "100"),
                    template(6L, "percent", "9", "0")));

            List<UserCouponVO> list = couponService.listForCheckout(1L, new BigDecimal("200"));

            assertThat(list).hasSize(2);
            assertThat(list.get(0).getUsable()).isTrue();
            assertThat(list.get(1).getUsable()).isTrue();
            verify(templateMapper).selectBatchIds(any());
        }

        @Test
        @DisplayName("门槛未达的券仍返回但标记为不可用，便于前端置灰")
        void shouldMarkUnusable() {
            UserCoupon coupon = new UserCoupon();
            coupon.setId(1L);
            coupon.setUserId(1L);
            coupon.setTemplateId(5L);
            coupon.setStatus(0);
            when(userCouponMapper.selectList(any())).thenReturn(List.of(coupon));
            when(templateMapper.selectBatchIds(any()))
                    .thenReturn(List.of(template(5L, "full", "50", "999")));

            List<UserCouponVO> list = couponService.listForCheckout(1L, new BigDecimal("10"));

            assertThat(list).hasSize(1);
            assertThat(list.get(0).getUsable()).isFalse();
            assertThat(list.get(0).getUnusableReason()).contains("未满足使用门槛");
        }

        @Test
        @DisplayName("模板已删除（查不到）的券直接过滤掉，避免前端渲染空对象")
        void shouldFilterOrphanCoupons() {
            UserCoupon coupon = new UserCoupon();
            coupon.setId(1L);
            coupon.setUserId(1L);
            coupon.setTemplateId(5L);
            coupon.setStatus(0);
            when(userCouponMapper.selectList(any())).thenReturn(List.of(coupon));
            when(templateMapper.selectBatchIds(any())).thenReturn(List.of());

            assertThat(couponService.listForCheckout(1L, BigDecimal.TEN)).isEmpty();
        }
    }
}
