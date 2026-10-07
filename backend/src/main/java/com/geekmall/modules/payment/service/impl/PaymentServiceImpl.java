package com.geekmall.modules.payment.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.geekmall.common.enums.PayMethod;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.payment.dto.CreatePaymentDTO;
import com.geekmall.modules.payment.dto.PayCallbackDTO;
import com.geekmall.modules.payment.entity.PaymentRecord;
import com.geekmall.modules.payment.mapper.PaymentRecordMapper;
import com.geekmall.modules.payment.service.PaymentService;
import com.geekmall.modules.payment.vo.PaymentVO;
import com.geekmall.modules.trade.service.TradeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 支付服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private static final int STATUS_PENDING = 0;
    private static final int STATUS_SUCCESS = 1;

    private final PaymentRecordMapper paymentRecordMapper;
    private final TradeService tradeService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaymentVO create(Long userId, CreatePaymentDTO dto) {
        // 让交易域校验订单归属与可支付状态，金额以服务端订单为准，禁止信任客户端
        BigDecimal amount = tradeService.requirePayableAmount(userId, dto.getOrderNo());

        String payMethod = StringUtils.hasText(dto.getPayMethod()) ? dto.getPayMethod() : "wechat";
        PaymentRecord record = new PaymentRecord();
        record.setTradeNo(generateTradeNo());
        record.setOrderNo(dto.getOrderNo());
        record.setUserId(userId);
        record.setPayMethod(payMethod);
        record.setAmount(amount);
        record.setStatus(STATUS_PENDING);
        paymentRecordMapper.insert(record);
        log.info("创建支付单 {}，订单 {}，金额 ¥{}", record.getTradeNo(), record.getOrderNo(), amount);
        return toVO(record);
    }

    @Override
    public PaymentVO query(Long userId, String tradeNo) {
        return toVO(requireOwnRecord(userId, tradeNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void mockPay(Long userId, String tradeNo) {
        PaymentRecord record = requireOwnRecord(userId, tradeNo);
        PayCallbackDTO callback = new PayCallbackDTO();
        callback.setTradeNo(record.getTradeNo());
        callback.setOrderNo(record.getOrderNo());
        callback.setAmount(record.getAmount());
        callback.setStatus(STATUS_SUCCESS);
        callback.setSign("mock");
        handleCallback(callback);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void handleCallback(PayCallbackDTO dto) {
        PaymentRecord record = paymentRecordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getTradeNo, dto.getTradeNo())
                .last("limit 1"));
        if (record == null) {
            throw new BizException(ResultCode.NOT_FOUND, "支付单不存在：" + dto.getTradeNo());
        }

        // 幂等：渠道会重复通知，已成功的直接返回
        if (record.getStatus() != null && record.getStatus() == STATUS_SUCCESS) {
            log.info("支付单 {} 已处理成功，忽略重复回调", dto.getTradeNo());
            return;
        }

        // 校验金额与订单号，防止伪造回调
        if (dto.getAmount() == null || record.getAmount() == null
                || dto.getAmount().compareTo(record.getAmount()) != 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "回调金额与支付单不一致");
        }
        if (StringUtils.hasText(dto.getOrderNo()) && !dto.getOrderNo().equals(record.getOrderNo())) {
            throw new BizException(ResultCode.BIZ_ERROR, "回调订单号与支付单不一致");
        }
        // TODO 接入真实渠道时在此校验渠道签名（RSA/HMAC）与 out_trade_no

        if (dto.getStatus() != null && dto.getStatus() != STATUS_SUCCESS) {
            PaymentRecord failed = new PaymentRecord();
            failed.setId(record.getId());
            failed.setStatus(2);
            failed.setCallbackTime(LocalDateTime.now());
            failed.setCallbackContent("支付失败回调：" + dto.getTradeNo());
            paymentRecordMapper.updateById(failed);
            log.warn("支付单 {} 回调为失败状态", dto.getTradeNo());
            return;
        }

        PaymentRecord update = new PaymentRecord();
        update.setStatus(STATUS_SUCCESS);
        update.setCallbackTime(LocalDateTime.now());
        update.setCallbackContent("tradeNo=" + dto.getTradeNo() + ",amount=" + dto.getAmount());
        // P1-7：check-then-update 改为 CAS 条件更新，并发/重复回调只有一个能成功落状态
        int rows = paymentRecordMapper.update(update, new LambdaUpdateWrapper<PaymentRecord>()
                .eq(PaymentRecord::getId, record.getId())
                .ne(PaymentRecord::getStatus, STATUS_SUCCESS));
        if (rows == 0) {
            log.info("支付单 {} 已被并发处理为成功，忽略重复回调", dto.getTradeNo());
            return;
        }

        // 驱动订单流转：待付款 → 待发货
        // 注意：若订单已被超时关闭，此处会抛异常并回滚，真实场景应转为退款流程
        tradeService.markPaid(record.getOrderNo(), record.getTradeNo(), record.getPayMethod());
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    private PaymentRecord requireOwnRecord(Long userId, String tradeNo) {
        PaymentRecord record = paymentRecordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getTradeNo, tradeNo)
                .last("limit 1"));
        if (record == null || !record.getUserId().equals(userId)) {
            throw new BizException(ResultCode.NOT_FOUND, "支付单不存在：" + tradeNo);
        }
        return record;
    }

    private PaymentVO toVO(PaymentRecord record) {
        PaymentVO vo = new PaymentVO();
        vo.setTradeNo(record.getTradeNo());
        vo.setOrderNo(record.getOrderNo());
        vo.setAmount(record.getAmount());
        vo.setPayMethod(record.getPayMethod());
        vo.setPayMethodLabel(PayMethod.labelOf(record.getPayMethod()));
        vo.setStatus(record.getStatus());
        vo.setStatusText(statusText(record.getStatus()));
        vo.setCallbackTime(record.getCallbackTime());
        vo.setExpireSecondsLeft(0L);
        // 扫码类支付返回二维码内容，骨架阶段用占位串；接入渠道后替换为真实 code_url
        if ("wechat".equals(record.getPayMethod()) || "alipay".equals(record.getPayMethod())) {
            vo.setQrCode("https://pay.geekmall.local/qr?tradeNo=" + record.getTradeNo());
        }
        return vo;
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case 0 -> "待支付";
            case 1 -> "支付成功";
            case 2 -> "支付失败";
            case 3 -> "已关闭";
            default -> "";
        };
    }

    private String generateTradeNo() {
        return "PAY" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
                + String.format("%05d", ThreadLocalRandom.current().nextInt(100_000));
    }
}
