package com.geekmall.modules.payment.service;

import com.geekmall.modules.payment.dto.CreatePaymentDTO;
import com.geekmall.modules.payment.dto.PayCallbackDTO;
import com.geekmall.modules.payment.vo.PaymentVO;

/**
 * 支付服务。
 */
public interface PaymentService {

    /** 创建支付单（收银台发起）。 */
    PaymentVO create(Long userId, CreatePaymentDTO dto);

    /** 查询支付状态，供前端轮询。 */
    PaymentVO query(Long userId, String tradeNo);

    /**
     * 模拟支付成功（演示用「我已支付」按钮）。
     * 接入真实渠道后应删除此接口。
     */
    void mockPay(Long userId, String tradeNo);

    /**
     * 处理支付渠道异步回调。
     *
     * <p>要求：按 tradeNo 幂等、校验金额一致、成功后驱动订单状态流转。</p>
     */
    void handleCallback(PayCallbackDTO dto);
}
