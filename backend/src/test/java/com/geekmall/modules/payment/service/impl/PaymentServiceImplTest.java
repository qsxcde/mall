package com.geekmall.modules.payment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.payment.channel.PaymentChannelClient;
import com.geekmall.modules.payment.config.PaymentProperties;
import com.geekmall.modules.payment.dto.CreatePaymentDTO;
import com.geekmall.modules.payment.entity.PaymentRecord;
import com.geekmall.modules.payment.mapper.PaymentRecordMapper;
import com.geekmall.modules.payment.service.PaymentRefundService;
import com.geekmall.modules.payment.vo.PaymentVO;
import com.geekmall.modules.trade.service.TradeService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 支付服务单元测试。
 *
 * <p>覆盖两个会造成真实资金损失的边界：生产/关闭 mock 时不得自签支付成功；
 * 以及「支付单已成功、订单未落账」的对账补偿。</p>
 */
class PaymentServiceImplTest {

    private static final long USER_ID = 1L;

    private PaymentRecordMapper paymentRecordMapper;
    private TradeService tradeService;
    private PaymentRefundService paymentRefundService;
    private PaymentChannelClient paymentChannel;
    private PaymentServiceImpl paymentService;

    @BeforeEach
    void setUp() {
        paymentRecordMapper = mock(PaymentRecordMapper.class);
        tradeService = mock(TradeService.class);
        paymentRefundService = mock(PaymentRefundService.class);
        paymentChannel = mock(PaymentChannelClient.class);
        paymentService = new PaymentServiceImpl(
                paymentRecordMapper,
                tradeService,
                paymentRefundService,
                paymentChannel,
                new PaymentProperties(),
                mock(StringRedisTemplate.class));
    }

    private static PaymentRecord record(String tradeNo, String orderNo) {
        PaymentRecord record = new PaymentRecord();
        record.setId(1L);
        record.setTradeNo(tradeNo);
        record.setOrderNo(orderNo);
        record.setUserId(USER_ID);
        record.setPayMethod("wechat");
        record.setAmount(new BigDecimal("99.00"));
        record.setStatus(1);
        record.setRefundStatus(0);
        record.setCreateTime(LocalDateTime.now());
        return record;
    }

    @Nested
    @DisplayName("模拟支付闸门（P0-1）")
    class MockPayGate {

        @Test
        @DisplayName("本地渠道 + mock 关闭 → 拒绝自签支付成功")
        void simulatedPaymentRejectedWhenMockDisabled() {
            when(paymentRecordMapper.selectOne(any())).thenReturn(record("T1", "O1"));
            when(paymentChannel.supportsSimulatedPayment()).thenReturn(true);
            ReflectionTestUtils.setField(paymentService, "mockEnabled", false);

            assertThatThrownBy(() -> paymentService.mockPay(USER_ID, "T1"))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getCode())
                    .isEqualTo(ResultCode.FORBIDDEN.getCode());

            verify(paymentChannel, never()).simulatePaidNotify(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("真实渠道不受 mock 开关影响：仍允许主动查单刷新")
        void realChannelRefreshIsAllowed() {
            when(paymentRecordMapper.selectOne(any())).thenReturn(record("T2", "O2"));
            when(paymentChannel.supportsSimulatedPayment()).thenReturn(false);
            when(paymentChannel.query("T2"))
                    .thenReturn(new PaymentChannelClient.ChannelTradeState(
                            "T2", null, null, PaymentChannelClient.ChannelTradeStatus.WAIT_PAY));
            ReflectionTestUtils.setField(paymentService, "mockEnabled", false);

            paymentService.mockPay(USER_ID, "T2");

            verify(paymentChannel).query("T2");
            verify(paymentChannel, never()).simulatePaidNotify(anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("支付成功但订单未落账的对账（P0-2）")
    class ReconcileSucceeded {

        @Test
        @DisplayName("订单仍待支付 → 重新驱动订单落账")
        void appliesPaymentToPendingOrder() {
            PaymentRecord payment = record("T3", "O3");
            when(paymentRecordMapper.selectSuccessWithUnsettledOrder(any(), eq(50)))
                    .thenReturn(List.of(payment));
            when(tradeService.applyPayment("O3", "T3", "wechat")).thenReturn(TradeService.PaymentApplyResult.APPLIED);

            int reconciled = paymentService.reconcileSucceeded(50);

            assertThat(reconciled).isEqualTo(1);
            verify(tradeService).applyPayment("O3", "T3", "wechat");
            verify(paymentRefundService, never()).refundForClosedOrder(any(), anyString());
        }

        @Test
        @DisplayName("订单已关闭 → 支付成功必须转退款")
        void refundsWhenOrderClosed() {
            PaymentRecord payment = record("T4", "O4");
            when(paymentRecordMapper.selectSuccessWithUnsettledOrder(any(), eq(50)))
                    .thenReturn(List.of(payment));
            when(tradeService.applyPayment("O4", "T4", "wechat"))
                    .thenReturn(TradeService.PaymentApplyResult.ORDER_CLOSED);

            int reconciled = paymentService.reconcileSucceeded(50);

            assertThat(reconciled).isEqualTo(1);
            verify(paymentRefundService).refundForClosedOrder(eq(payment), eq("支付成功但订单已关闭，对账转退款"));
        }

        @Test
        @DisplayName("订单已被另一笔支付落账 → 本次支付全额退款")
        void refundsDuplicatePayment() {
            PaymentRecord payment = record("T7", "O7");
            when(paymentRecordMapper.selectSuccessWithUnsettledOrder(any(), eq(50)))
                    .thenReturn(List.of(payment));
            when(tradeService.applyPayment("O7", "T7", "wechat"))
                    .thenReturn(TradeService.PaymentApplyResult.DUPLICATE_PAYMENT);

            int reconciled = paymentService.reconcileSucceeded(50);

            assertThat(reconciled).isEqualTo(1);
            verify(paymentRefundService).refundFull(payment, "重复支付退款");
        }

        @Test
        @DisplayName("单笔失败不中断整批，下一轮仍会重试")
        void oneFailureDoesNotAbortBatch() {
            PaymentRecord first = record("T5", "O5");
            PaymentRecord second = record("T6", "O6");
            when(paymentRecordMapper.selectSuccessWithUnsettledOrder(any(), eq(50)))
                    .thenReturn(List.of(first, second));
            when(tradeService.applyPayment("O5", "T5", "wechat")).thenThrow(new IllegalStateException("模拟数据库抖动"));
            when(tradeService.applyPayment("O6", "T6", "wechat")).thenReturn(TradeService.PaymentApplyResult.APPLIED);

            int reconciled = paymentService.reconcileSucceeded(50);

            assertThat(reconciled).isEqualTo(1);
            verify(tradeService).applyPayment("O6", "T6", "wechat");
        }
    }

    @Nested
    @DisplayName("支付单创建")
    class CreatePayment {

        @Test
        @DisplayName("切换支付方式复用同一订单的待支付单，不再新建渠道订单")
        void reusesPendingPaymentAcrossMethods() {
            PaymentRecord existing = record("T8", "O8");
            existing.setStatus(0);
            existing.setChannel("local");
            existing.setPayMethod("wechat");
            when(tradeService.requirePayableAmount(USER_ID, "O8")).thenReturn(new BigDecimal("99.00"));
            when(paymentChannel.channelCode()).thenReturn("local");
            when(paymentRecordMapper.selectOne(any())).thenReturn(existing);

            CreatePaymentDTO dto = new CreatePaymentDTO();
            dto.setOrderNo("O8");
            dto.setPayMethod("alipay");

            PaymentVO vo = paymentService.create(USER_ID, dto);

            assertThat(vo.getTradeNo()).as("必须复用原支付单，而不是新建").isEqualTo("T8");
            assertThat(vo.getPayMethod()).as("支付方式随本次选择更新").isEqualTo("alipay");
            verify(paymentRecordMapper).updateById(any(PaymentRecord.class));
            verify(paymentChannel, never()).prepay(anyString(), any(), anyString(), any());
        }
    }
}
