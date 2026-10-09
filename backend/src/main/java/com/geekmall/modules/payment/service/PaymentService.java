package com.geekmall.modules.payment.service;

import com.geekmall.modules.payment.dto.CreatePaymentDTO;
import com.geekmall.modules.payment.vo.PaymentVO;
import java.util.Map;

/**
 * 支付服务。
 */
public interface PaymentService {

    /** 创建支付单（收银台发起）。同一订单 + 同一方式会复用未过期的待支付单。 */
    PaymentVO create(Long userId, CreatePaymentDTO dto);

    /** 查询支付状态，供前端轮询。 */
    PaymentVO query(Long userId, String tradeNo);

    /**
     * 模拟 / 刷新支付：
     * 本地渠道 = 用本渠道密钥签一份通知回环投递（等价于渠道回调）；
     * 支付宝渠道 = 主动查单一次（「我已支付，刷新状态」）。
     */
    void mockPay(Long userId, String tradeNo);

    /**
     * 处理渠道异步通知（原始表单参数）。
     *
     * <p><b>只返回裸字符串 {@code success} / {@code failure}</b>（支付宝的应答约定），
     * 且<b>永不向外抛异常</b> —— 抛异常会走全局异常处理器返回 JSON，
     * 渠道按失败处理并反复重试。</p>
     */
    String handleNotify(Map<String, String> params);

    /**
     * 主动查单补偿：扫描「待支付」且已超出窗口的支付单，向渠道查真实状态并落账。
     *
     * @return 本次发生状态变更的单数
     */
    int compensatePending(int batchSize);
}
