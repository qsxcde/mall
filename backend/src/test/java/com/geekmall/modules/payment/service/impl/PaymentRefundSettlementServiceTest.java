package com.geekmall.modules.payment.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.geekmall.modules.payment.channel.PaymentChannelClient;
import com.geekmall.modules.payment.entity.PaymentRecord;
import com.geekmall.modules.payment.mapper.PaymentRecordMapper;
import com.geekmall.modules.payment.mapper.PaymentRefundRecordMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 退款结算事务的单元测试：验证退款单状态与支付单累计金额/整单状态的更新组合。
 */
class PaymentRefundSettlementServiceTest {

    private PaymentRefundRecordMapper refundRecordMapper;
    private PaymentRecordMapper paymentRecordMapper;
    private PaymentRefundSettlementService settlementService;

    @BeforeEach
    void setUp() {
        refundRecordMapper = mock(PaymentRefundRecordMapper.class);
        paymentRecordMapper = mock(PaymentRecordMapper.class);
        settlementService = new PaymentRefundSettlementService(refundRecordMapper, paymentRecordMapper);
        // 默认模拟「本次成功推进退款单状态」；重复结算用例再单独覆盖为 0
        lenient().when(refundRecordMapper.update(any(), any())).thenReturn(1);
    }

    private static PaymentRecord payment() {
        PaymentRecord payment = new PaymentRecord();
        payment.setId(1L);
        payment.setTradeNo("T1");
        return payment;
    }

    @Test
    @DisplayName("整单退款成功：累加退款金额并标记整单已退")
    void fullSuccessAddsAmountAndMarksDone() {
        PaymentChannelClient.RefundResult result = new PaymentChannelClient.RefundResult(true, "CH1", "{}", null);

        settlementService.settle(9L, payment(), result, new BigDecimal("70.00"), true);

        verify(paymentRecordMapper).addRefundAmount(1L, new BigDecimal("70.00"));
        verify(paymentRecordMapper).updateRefundStatus(1L, 2);
    }

    @Test
    @DisplayName("部分退款成功：只累加金额，不碰整单退款状态")
    void partialSuccessOnlyAddsAmount() {
        PaymentChannelClient.RefundResult result = new PaymentChannelClient.RefundResult(true, "CH1", "{}", null);

        settlementService.settle(9L, payment(), result, new BigDecimal("30.00"), false);

        verify(paymentRecordMapper).addRefundAmount(1L, new BigDecimal("30.00"));
        verify(paymentRecordMapper, never()).updateRefundStatus(anyLong(), anyInt());
    }

    @Test
    @DisplayName("整单退款失败：标记失败，不加累计金额")
    void fullFailureMarksFailedWithoutAdding() {
        PaymentChannelClient.RefundResult result = new PaymentChannelClient.RefundResult(false, null, null, "渠道超时");

        settlementService.settle(9L, payment(), result, new BigDecimal("70.00"), true);

        verify(paymentRecordMapper).updateRefundStatus(1L, 3);
        verify(paymentRecordMapper, never()).addRefundAmount(anyLong(), any());
    }

    @Test
    @DisplayName("部分退款失败：只记退款单失败，不影响支付单状态")
    void partialFailureDoesNotTouchPayment() {
        PaymentChannelClient.RefundResult result = new PaymentChannelClient.RefundResult(false, null, null, "渠道超时");

        settlementService.settle(9L, payment(), result, new BigDecimal("30.00"), false);

        verify(paymentRecordMapper, never()).addRefundAmount(anyLong(), any());
        verify(paymentRecordMapper, never()).updateRefundStatus(anyLong(), anyInt());
    }

    @Test
    @DisplayName("重复结算：退款单已落定时不得重复累加金额")
    void duplicateSettlementIsIgnored() {
        when(refundRecordMapper.update(any(), any())).thenReturn(0);
        PaymentChannelClient.RefundResult result = new PaymentChannelClient.RefundResult(true, "CH1", "{}", null);

        settlementService.settle(9L, payment(), result, new BigDecimal("70.00"), true);

        verify(paymentRecordMapper, never()).addRefundAmount(anyLong(), any());
        verify(paymentRecordMapper, never()).updateRefundStatus(anyLong(), anyInt());
    }
}
