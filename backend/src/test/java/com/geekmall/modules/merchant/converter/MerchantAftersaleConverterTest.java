package com.geekmall.modules.merchant.converter;

import com.geekmall.common.exception.BizException;
import com.geekmall.modules.aftersale.entity.AfterSale;
import com.geekmall.modules.merchant.support.MerchantAftersaleStatus;
import com.geekmall.modules.merchant.vo.MerchantAftersaleVO;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.entity.OrderItem;
import com.geekmall.modules.user.entity.SysUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商家端售后工单转换器单元测试。
 *
 * <p>覆盖三块易错逻辑：工单号编解码、处理时限计算、以及按状态重建进度时间轴。</p>
 */
class MerchantAftersaleConverterTest {

    private static final LocalDateTime APPLY_AT = LocalDateTime.of(2026, 10, 5, 10, 0);

    private static AfterSale aftersale(Long id, Integer buyerStatus, String merchantStatus) {
        AfterSale item = new AfterSale();
        item.setId(id);
        item.setOrderNo("GM202610050001");
        item.setType("return");
        item.setReason("商品有划痕");
        item.setAmount(new BigDecimal("199.00"));
        item.setImages("a.png,b.png");
        item.setPhone("13900000000");
        item.setStatus(buyerStatus);
        item.setMerchantStatus(merchantStatus);
        item.setCreateTime(APPLY_AT);
        return item;
    }

    @Nested
    @DisplayName("工单号编解码")
    class IdCodec {

        @Test
        @DisplayName("主键 → 工单号带 AS 前缀")
        void shouldEncode() {
            assertThat(MerchantAftersaleConverter.toId(1L)).startsWith("AS");
            assertThat(MerchantAftersaleConverter.toId(1L)).isEqualTo("AS2026100503");
        }

        @Test
        @DisplayName("编码后可无损解码回主键（含 0 与较大主键）")
        void shouldRoundTrip() {
            for (long pk : new long[]{0L, 1L, 2L, 17L, 9999L}) {
                String id = MerchantAftersaleConverter.toId(pk);
                assertThat(MerchantAftersaleConverter.parseId(id)).isEqualTo(pk);
            }
        }

        @Test
        @DisplayName("工单号格式非法时抛业务异常，而不是 NumberFormatException")
        void shouldRejectIllegalId() {
            assertThatThrownBy(() -> MerchantAftersaleConverter.parseId(null))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> MerchantAftersaleConverter.parseId(""))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> MerchantAftersaleConverter.parseId("XX2026100503"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> MerchantAftersaleConverter.parseId("ASabc"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> MerchantAftersaleConverter.parseId("AS"))
                    .isInstanceOf(BizException.class);
        }
    }

    @Nested
    @DisplayName("详情转换")
    class ToVo {

        @Test
        @DisplayName("null 入参返回 null")
        void nullItem() {
            assertThat(MerchantAftersaleConverter.toVO(null, null, null, null)).isNull();
        }

        @Test
        @DisplayName("订单与商品信息映射，凭证图按逗号计数")
        void shouldMapOrderAndProduct() {
            Order order = new Order();
            order.setPayAmount(new BigDecimal("5967.00"));

            OrderItem orderItem = new OrderItem();
            orderItem.setProductId(11L);
            orderItem.setTitle("极客手机 Pro");
            orderItem.setCover("https://img/11.png");
            orderItem.setSpec("256G");
            orderItem.setPrice(new BigDecimal("5999.00"));
            orderItem.setQty(1);

            MerchantAftersaleVO vo = MerchantAftersaleConverter.toVO(
                    aftersale(3L, 0, MerchantAftersaleStatus.PENDING), order, orderItem, null);

            assertThat(vo.getId()).isEqualTo(MerchantAftersaleConverter.toId(3L));
            assertThat(vo.getOrderId()).isEqualTo("GM202610050001");
            assertThat(vo.getType()).isEqualTo("return");
            assertThat(vo.getRefundAmount()).isEqualByComparingTo("199.00");
            assertThat(vo.getOrderAmount()).isEqualByComparingTo("5967.00");
            assertThat(vo.getQty()).isEqualTo(1);
            assertThat(vo.getImages()).isEqualTo(2);
            assertThat(vo.getProduct().getName()).isEqualTo("极客手机 Pro");
            assertThat(vo.getProduct().getPrice()).isEqualByComparingTo("5999.00");
        }

        @Test
        @DisplayName("无订单时金额为空但件数仍可用；无商品时商品字段为空")
        void shouldTolerateMissingOrder() {
            MerchantAftersaleVO vo = MerchantAftersaleConverter.toVO(
                    aftersale(3L, 0, MerchantAftersaleStatus.PENDING), null, null, null);

            assertThat(vo.getOrderAmount()).isNull();
            assertThat(vo.getQty()).isZero();
            assertThat(vo.getProduct()).isNull();
            assertThat(vo.getBuyer()).isNull();
        }

        @Test
        @DisplayName("凭证图缺省计为 0")
        void shouldCountZeroImages() {
            AfterSale item = aftersale(3L, 0, MerchantAftersaleStatus.PENDING);
            item.setImages(null);
            assertThat(MerchantAftersaleConverter.toVO(item, null, null, null).getImages()).isZero();

            item.setImages(" , , ");
            assertThat(MerchantAftersaleConverter.toVO(item, null, null, null).getImages()).isZero();
        }

        @Test
        @DisplayName("买家昵称为空时回落到账号；等级映射为 V+等级 ID")
        void shouldMapBuyer() {
            SysUser buyer = new SysUser();
            buyer.setUsername("13800000000");
            buyer.setNickname(null);
            buyer.setLevelId(2L);
            buyer.setPhone("13800000000");

            MerchantAftersaleVO vo = MerchantAftersaleConverter.toVO(
                    aftersale(3L, 0, MerchantAftersaleStatus.PENDING), null, null, buyer);

            assertThat(vo.getBuyer().getName()).isEqualTo("13800000000");
            assertThat(vo.getBuyer().getLevel()).isEqualTo("V2");
            // 申请单自带手机号时优先使用申请单上的联系方式
            assertThat(vo.getPhone()).isEqualTo("13900000000");
        }

        @Test
        @DisplayName("申请单未留手机号时回落到买家账号手机号")
        void shouldFallbackToBuyerPhone() {
            AfterSale item = aftersale(3L, 0, MerchantAftersaleStatus.PENDING);
            item.setPhone(null);
            SysUser buyer = new SysUser();
            buyer.setUsername("13800000000");
            buyer.setNickname("极客小张");
            buyer.setPhone("13800000000");

            MerchantAftersaleVO vo = MerchantAftersaleConverter.toVO(item, null, null, buyer);

            assertThat(vo.getPhone()).isEqualTo("13800000000");
            assertThat(vo.getBuyer().getName()).isEqualTo("极客小张");
            assertThat(vo.getBuyer().getLevel()).isNull();
        }
    }

    @Nested
    @DisplayName("处理时限")
    class Deadline {

        @Test
        @DisplayName("待处理为 48 小时")
        void pendingIs48Hours() {
            MerchantAftersaleVO vo = MerchantAftersaleConverter.toVO(
                    aftersale(3L, 0, MerchantAftersaleStatus.PENDING), null, null, null);
            assertThat(vo.getDeadline()).isEqualTo(APPLY_AT.plusHours(48));
        }

        @Test
        @DisplayName("待寄回 / 待收货为 168 小时")
        void returnStagesAre168Hours() {
            assertThat(MerchantAftersaleConverter.toVO(
                    aftersale(3L, 0, MerchantAftersaleStatus.WAIT_RETURN), null, null, null).getDeadline())
                    .isEqualTo(APPLY_AT.plusHours(168));
            assertThat(MerchantAftersaleConverter.toVO(
                    aftersale(3L, 0, MerchantAftersaleStatus.WAIT_RECEIVE), null, null, null).getDeadline())
                    .isEqualTo(APPLY_AT.plusHours(168));
        }

        @Test
        @DisplayName("已终结状态无处理时限")
        void finalStatesHaveNoDeadline() {
            assertThat(MerchantAftersaleConverter.toVO(
                    aftersale(3L, 1, MerchantAftersaleStatus.DONE), null, null, null).getDeadline()).isNull();
            assertThat(MerchantAftersaleConverter.toVO(
                    aftersale(3L, 0, MerchantAftersaleStatus.REJECTED), null, null, null).getDeadline()).isNull();
            assertThat(MerchantAftersaleConverter.toVO(
                    aftersale(3L, 2, null), null, null, null).getDeadline()).isNull();
        }

        @Test
        @DisplayName("申请时间为空时无时限，且不抛异常")
        void nullApplyAt() {
            AfterSale item = aftersale(3L, 0, MerchantAftersaleStatus.PENDING);
            item.setCreateTime(null);

            MerchantAftersaleVO vo = MerchantAftersaleConverter.toVO(item, null, null, null);

            assertThat(vo.getDeadline()).isNull();
            assertThat(vo.getTimeline()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("进度时间轴")
    class Timeline {

        @Test
        @DisplayName("待处理：已提交 + 待商家处理")
        void pendingTimeline() {
            MerchantAftersaleVO vo = MerchantAftersaleConverter.toVO(
                    aftersale(3L, 0, MerchantAftersaleStatus.PENDING), null, null, null);

            assertThat(vo.getTimeline()).hasSize(2);
            assertThat(vo.getTimeline().get(0)).containsEntry("title", "买家提交申请")
                    .containsEntry("done", true);
            assertThat(vo.getTimeline().get(1)).containsEntry("title", "商家处理")
                    .containsEntry("done", false)
                    .containsEntry("tip", "待响应");
        }

        @Test
        @DisplayName("待买家寄回：已同意 + 等待寄回")
        void waitReturnTimeline() {
            MerchantAftersaleVO vo = MerchantAftersaleConverter.toVO(
                    aftersale(3L, 0, MerchantAftersaleStatus.WAIT_RETURN), null, null, null);

            assertThat(vo.getTimeline()).hasSize(3);
            assertThat(vo.getTimeline().get(1)).containsEntry("title", "商家同意售后");
            assertThat(vo.getTimeline().get(2)).containsEntry("done", false)
                    .containsEntry("tip", "待买家寄回");
        }

        @Test
        @DisplayName("待商家收货：含已寄回节点")
        void waitReceiveTimeline() {
            MerchantAftersaleVO vo = MerchantAftersaleConverter.toVO(
                    aftersale(3L, 0, MerchantAftersaleStatus.WAIT_RECEIVE), null, null, null);

            assertThat(vo.getTimeline()).hasSize(4);
            assertThat(vo.getTimeline().get(2)).containsEntry("title", "买家已寄回商品")
                    .containsEntry("done", true);
            assertThat(vo.getTimeline().get(3)).containsEntry("tip", "待商家收货");
        }

        @Test
        @DisplayName("已拒绝：给出平台介入入口")
        void rejectedTimeline() {
            MerchantAftersaleVO vo = MerchantAftersaleConverter.toVO(
                    aftersale(3L, 0, MerchantAftersaleStatus.REJECTED), null, null, null);

            assertThat(vo.getTimeline()).hasSize(3);
            assertThat(vo.getTimeline().get(1)).containsEntry("title", "商家拒绝申请");
            assertThat(vo.getTimeline().get(2)).containsEntry("title", "买家可申请平台介入");
        }

        @Test
        @DisplayName("已完成：退款完成节点为终态")
        void doneTimeline() {
            MerchantAftersaleVO vo = MerchantAftersaleConverter.toVO(
                    aftersale(3L, 1, MerchantAftersaleStatus.DONE), null, null, null);

            assertThat(vo.getTimeline()).hasSize(5);
            assertThat(vo.getTimeline().get(4)).containsEntry("title", "退款已完成")
                    .containsEntry("done", true);
        }

        @Test
        @DisplayName("买家已撤销：状态解析为 canceled，避免商家空等退货")
        void canceledTimeline() {
            MerchantAftersaleVO vo = MerchantAftersaleConverter.toVO(
                    aftersale(3L, 2, MerchantAftersaleStatus.WAIT_RETURN), null, null, null);

            assertThat(vo.getStatus()).isEqualTo(MerchantAftersaleStatus.CANCELED);
            assertThat(vo.getDeadline()).isNull();
        }
    }
}
