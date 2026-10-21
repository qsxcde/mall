package com.geekmall.modules.payment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.geekmall.modules.payment.channel.PaymentChannelClient;
import com.geekmall.modules.payment.config.PaymentProperties;
import com.geekmall.modules.payment.entity.PaymentRecord;
import com.geekmall.modules.payment.entity.PaymentRefundRecord;
import com.geekmall.modules.payment.mapper.PaymentRecordMapper;
import com.geekmall.modules.payment.mapper.PaymentRefundRecordMapper;
import com.geekmall.modules.payment.support.PaymentRefundLock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 退款编排单元测试。
 *
 * <p>重点覆盖三个直接决定资损的边界：只退剩余金额、部分售后退款不关闭整单退款闸门、
 * 多笔成功支付（历史重复支付）逐笔退款。</p>
 */
class PaymentRefundServiceImplTest {

    private PaymentRecordMapper paymentRecordMapper;
    private PaymentRefundRecordMapper refundRecordMapper;
    private PaymentChannelClient paymentChannel;
    private PaymentRefundSettlementService settlementService;
    private PaymentRefundLock refundLock;
    private PaymentRefundServiceImpl refundService;

    @BeforeEach
    void setUp() {
        paymentRecordMapper = mock(PaymentRecordMapper.class);
        refundRecordMapper = mock(PaymentRefundRecordMapper.class);
        paymentChannel = mock(PaymentChannelClient.class);
        settlementService = mock(PaymentRefundSettlementService.class);
        refundLock = mock(PaymentRefundLock.class);
        lenient().when(refundLock.tryLock(anyString(), any(), any())).thenReturn("token");
        refundService = new PaymentRefundServiceImpl(
                paymentRecordMapper,
                refundRecordMapper,
                paymentChannel,
                settlementService,
                new PaymentProperties(),
                new SimpleMeterRegistry(),
                refundLock);
    }

    private static PaymentRecord payment(String tradeNo, String amount, String refunded, int refundStatus) {
        PaymentRecord payment = new PaymentRecord();
        payment.setId(1L);
        payment.setTradeNo(tradeNo);
        payment.setOrderNo("O1");
        payment.setUserId(1L);
        payment.setChannel("local");
        payment.setAmount(new BigDecimal(amount));
        payment.setRefundAmount(new BigDecimal(refunded));
        payment.setStatus(1);
        payment.setRefundStatus(refundStatus);
        return payment;
    }

    private static PaymentChannelClient.RefundResult success() {
        return new PaymentChannelClient.RefundResult(true, "CH1", "{}", null);
    }

    private static PaymentRefundRecord refundRecord(
            String refundNo, String tradeNo, String amount, int status, int retryCount, int fullRefund) {
        PaymentRefundRecord record = new PaymentRefundRecord();
        record.setId(9L);
        record.setRefundNo(refundNo);
        record.setTradeNo(tradeNo);
        record.setAmount(new BigDecimal(amount));
        record.setStatus(status);
        record.setRetryCount(retryCount);
        record.setFullRefund(fullRefund);
        record.setReason("reason");
        return record;
    }

    @Nested
    @DisplayName("整单退款")
    class FullRefund {

        @Test
        @DisplayName("已有部分退款时只退剩余金额")
        void refundsRemainingAmount() {
            PaymentRecord payment = payment("T1", "100.00", "30.00", 0);
            when(paymentRecordMapper.selectById(1L)).thenReturn(payment);
            when(paymentRecordMapper.update(any(), any())).thenReturn(1);
            when(refundRecordMapper.insert(any(PaymentRefundRecord.class))).thenReturn(1);
            when(paymentChannel.refund(eq("T1"), eq("RFT1"), eq(new BigDecimal("70.00")), eq("reason")))
                    .thenReturn(success());

            assertThat(refundService.refundFull(payment, "reason")).isTrue();

            verify(paymentChannel).refund(eq("T1"), eq("RFT1"), eq(new BigDecimal("70.00")), eq("reason"));
            verify(settlementService).settle(any(), eq(payment), any(), eq(new BigDecimal("70.00")), eq(true));
        }

        @Test
        @DisplayName("已全额退款时不再调用渠道")
        void fullyRefundedIsNoop() {
            PaymentRecord payment = payment("T2", "100.00", "100.00", 2);
            when(paymentRecordMapper.selectById(1L)).thenReturn(payment);

            assertThat(refundService.refundFull(payment, "reason")).isFalse();

            verify(paymentChannel, never()).refund(anyString(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("历史重复支付：逐笔成功支付都退款")
        void refundsAllSuccessfulPayments() {
            PaymentRecord first = payment("T3", "50.00", "0.00", 0);
            PaymentRecord second = payment("T4", "50.00", "0.00", 0);
            second.setId(2L);
            when(paymentRecordMapper.selectList(any())).thenReturn(List.of(first, second));
            when(paymentRecordMapper.selectById(1L)).thenReturn(first);
            when(paymentRecordMapper.selectById(2L)).thenReturn(second);
            when(paymentRecordMapper.update(any(), any())).thenReturn(1);
            when(refundRecordMapper.insert(any(PaymentRefundRecord.class))).thenReturn(1);
            when(paymentChannel.refund(anyString(), anyString(), any(), anyString()))
                    .thenReturn(success());

            assertThat(refundService.refundIfPaid("O1", "reason")).isTrue();

            verify(paymentChannel, times(2)).refund(anyString(), anyString(), any(), anyString());
            verify(settlementService, times(2)).settle(any(), any(), any(), any(), eq(true));
        }
    }

    @Nested
    @DisplayName("部分售后退款")
    class PartialAfterSale {

        @Test
        @DisplayName("未退完剩余金额时不标记整单退款完成")
        void partialRefundDoesNotCloseFullRefundGate() {
            PaymentRecord payment = payment("T5", "100.00", "0.00", 0);
            when(paymentRecordMapper.selectOne(any())).thenReturn(payment);
            when(paymentRecordMapper.selectById(1L)).thenReturn(payment);
            when(refundRecordMapper.insert(any(PaymentRefundRecord.class))).thenReturn(1);
            when(paymentChannel.refund(eq("T5"), eq("RFASAS1"), eq(new BigDecimal("30.00")), eq("reason")))
                    .thenReturn(success());

            assertThat(refundService.refundForAfterSale("O1", "AS1", new BigDecimal("30.00"), "reason"))
                    .isTrue();

            verify(settlementService).settle(any(), eq(payment), any(), eq(new BigDecimal("30.00")), eq(false));
        }

        @Test
        @DisplayName("恰好退完剩余金额时标记整单退款完成")
        void fullAmountAfterSaleClosesGate() {
            PaymentRecord payment = payment("T6", "100.00", "0.00", 0);
            when(paymentRecordMapper.selectOne(any())).thenReturn(payment);
            when(paymentRecordMapper.selectById(1L)).thenReturn(payment);
            when(refundRecordMapper.insert(any(PaymentRefundRecord.class))).thenReturn(1);
            when(paymentChannel.refund(eq("T6"), eq("RFASAS2"), eq(new BigDecimal("100.00")), eq("reason")))
                    .thenReturn(success());

            assertThat(refundService.refundForAfterSale("O1", "AS2", new BigDecimal("100.00"), "reason"))
                    .isTrue();

            verify(settlementService).settle(any(), eq(payment), any(), eq(new BigDecimal("100.00")), eq(true));
        }
    }

    @Nested
    @DisplayName("退款失败重试")
    class RetryFailedRefunds {

        @Test
        @DisplayName("抢占成功后复用原退款单号调用渠道，并按整单退款落定")
        void retriesAndSettlesFullRefund() {
            PaymentRefundRecord refund = refundRecord("RFT9", "T9", "50.00", 2, 0, 1);
            PaymentRecord payment = payment("T9", "50.00", "0.00", 0);
            when(refundRecordMapper.selectRetryable(eq(5), eq(10))).thenReturn(List.of(refund));
            when(refundRecordMapper.claimRetry(eq(9L), eq(0), any(LocalDateTime.class)))
                    .thenReturn(1);
            when(paymentRecordMapper.selectOne(any())).thenReturn(payment);
            when(paymentChannel.refund(eq("T9"), eq("RFT9"), eq(new BigDecimal("50.00")), eq("reason")))
                    .thenReturn(success());

            int retried = refundService.retryFailedRefunds(10);

            assertThat(retried).isEqualTo(1);
            verify(settlementService).settle(eq(9L), eq(payment), any(), eq(new BigDecimal("50.00")), eq(true));
        }

        @Test
        @DisplayName("乐观锁抢占失败时不得重复调用渠道")
        void claimLostDoesNotCallChannel() {
            PaymentRefundRecord refund = refundRecord("RFT10", "T10", "50.00", 2, 0, 1);
            when(refundRecordMapper.selectRetryable(eq(5), eq(10))).thenReturn(List.of(refund));
            when(refundRecordMapper.claimRetry(eq(9L), eq(0), any(LocalDateTime.class)))
                    .thenReturn(0);

            assertThat(refundService.retryFailedRefunds(10)).isZero();
            verify(paymentChannel, never()).refund(anyString(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("支付单不存在或非成功状态时跳过，不发起退款")
        void skipsWhenPaymentNotSuccessful() {
            PaymentRefundRecord refund = refundRecord("RFT11", "T11", "50.00", 2, 0, 1);
            when(refundRecordMapper.selectRetryable(eq(5), eq(10))).thenReturn(List.of(refund));
            when(refundRecordMapper.claimRetry(eq(9L), eq(0), any(LocalDateTime.class)))
                    .thenReturn(1);
            PaymentRecord notSuccess = payment("T11", "50.00", "0.00", 0);
            notSuccess.setStatus(0);
            when(paymentRecordMapper.selectOne(any())).thenReturn(notSuccess);

            assertThat(refundService.retryFailedRefunds(10)).isZero();
            verify(paymentChannel, never()).refund(anyString(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("历史部分退款单即使 full_refund=0，也按单号前缀识别为部分退款")
        void legacyPartialRefundKeepsFullFlagFalse() {
            PaymentRefundRecord refund = refundRecord("RFASAS3", "T12", "30.00", 2, 0, 0);
            PaymentRecord payment = payment("T12", "100.00", "0.00", 0);
            when(refundRecordMapper.selectRetryable(eq(5), eq(10))).thenReturn(List.of(refund));
            when(refundRecordMapper.claimRetry(eq(9L), eq(0), any(LocalDateTime.class)))
                    .thenReturn(1);
            when(paymentRecordMapper.selectOne(any())).thenReturn(payment);
            when(paymentChannel.refund(eq("T12"), eq("RFASAS3"), eq(new BigDecimal("30.00")), eq("reason")))
                    .thenReturn(success());

            assertThat(refundService.retryFailedRefunds(10)).isEqualTo(1);
            verify(settlementService).settle(eq(9L), eq(payment), any(), eq(new BigDecimal("30.00")), eq(false));
        }
    }
}
