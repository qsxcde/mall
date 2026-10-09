package com.geekmall.modules.payment.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.geekmall.common.constant.RedisKeys;
import com.geekmall.common.enums.PayMethod;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.payment.channel.PaymentChannelClient;
import com.geekmall.modules.payment.config.PaymentProperties;
import com.geekmall.modules.payment.dto.CreatePaymentDTO;
import com.geekmall.modules.payment.entity.PaymentRecord;
import com.geekmall.modules.payment.mapper.PaymentRecordMapper;
import com.geekmall.modules.payment.service.PaymentRefundService;
import com.geekmall.modules.payment.service.PaymentService;
import com.geekmall.modules.payment.support.ChannelNotify;
import com.geekmall.modules.payment.vo.PaymentVO;
import com.geekmall.modules.trade.service.TradeService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 支付服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    /** 支付宝对异步通知的应答约定：成功必须是裸字符串 {@code success}。 */
    private static final String NOTIFY_SUCCESS = "success";

    private static final String NOTIFY_FAILURE = "failure";

    private static final int STATUS_PENDING = 0;
    private static final int STATUS_SUCCESS = 1;
    private static final int STATUS_CLOSED = 3;

    /** 渠道要求支付超时最短 1 分钟（支付宝 timeout_express 下限）。 */
    private static final long MIN_PREPAY_TIMEOUT_SECONDS = 60L;

    private static final int MAX_CONTENT_LENGTH = 2000;

    private final PaymentRecordMapper paymentRecordMapper;
    private final TradeService tradeService;
    private final PaymentRefundService paymentRefundService;
    private final PaymentChannelClient paymentChannel;
    private final PaymentProperties paymentProperties;
    private final StringRedisTemplate redisTemplate;

    /**
     * 创建支付单。
     *
     * <p>刻意<b>不加事务</b>：预下单是对渠道网关的网络调用，放进事务会长时间持有数据库连接与行锁。</p>
     */
    @Override
    public PaymentVO create(Long userId, CreatePaymentDTO dto) {
        // 让交易域校验订单归属与可支付状态，金额以服务端订单为准，禁止信任客户端
        BigDecimal amount = tradeService.requirePayableAmount(userId, dto.getOrderNo());
        String payMethod = StringUtils.hasText(dto.getPayMethod()) ? dto.getPayMethod() : PayMethod.DEFAULT_CODE;
        String channel = paymentChannel.channelCode();

        // 复用未过期的待支付单：前端切换支付方式会重新 create，
        // 不复用就会对同一订单产生多张可付的渠道订单，用户可能付两笔
        PaymentRecord reusable = findReusable(dto.getOrderNo(), payMethod, channel);
        if (reusable != null) {
            log.info("支付单复用：订单 {} 已有待支付单 {}（{}）", dto.getOrderNo(), reusable.getTradeNo(), channel);
            return toVO(reusable);
        }

        String tradeNo = generateTradeNo();
        // 支付超时取订单剩余时间：写死固定值会让渠道订单比本地订单活得久，
        // 制造「本地订单已超时关闭，二维码却还能付款」的场景
        long remainSeconds = Math.max(MIN_PREPAY_TIMEOUT_SECONDS, tradeService.remainingPaySeconds(dto.getOrderNo()));
        PaymentChannelClient.PrepayResult prepay = paymentChannel.prepay(
                tradeNo, amount, "极客数码商城订单 " + dto.getOrderNo(), Duration.ofSeconds(remainSeconds));

        PaymentRecord record = new PaymentRecord();
        record.setTradeNo(tradeNo);
        record.setOrderNo(dto.getOrderNo());
        record.setUserId(userId);
        record.setPayMethod(payMethod);
        record.setChannel(channel);
        record.setPrepayQr(prepay.qrCode());
        record.setAmount(amount);
        record.setStatus(STATUS_PENDING);
        record.setRefundStatus(0);
        record.setRefundAmount(BigDecimal.ZERO);
        paymentRecordMapper.insert(record);
        log.info("创建支付单 {}，订单 {}，渠道 {}，金额 ¥{}", tradeNo, dto.getOrderNo(), channel, amount);
        return toVO(record);
    }

    @Override
    public PaymentVO query(Long userId, String tradeNo) {
        PaymentRecord record = requireOwnRecord(userId, tradeNo);
        return toVO(queryChannelIfNeeded(record));
    }

    @Override
    public void mockPay(Long userId, String tradeNo) {
        PaymentRecord record = requireOwnRecord(userId, tradeNo);
        if (paymentChannel.supportsSimulatedPayment()) {
            // 本地渠道：签一份「渠道侧支付成功」通知投递给自己，等价于真实回调
            Map<String, String> params = paymentChannel.simulatePaidNotify(
                    record.getTradeNo(), "LOCAL" + record.getTradeNo(), record.getAmount());
            if (!NOTIFY_SUCCESS.equals(handleNotify(params))) {
                throw new BizException(ResultCode.BIZ_ERROR, "模拟付款失败");
            }
            return;
        }
        // 真实渠道没有「我已支付」按钮的语义，改为主动查单刷新
        queryAndApply(record);
    }

    /**
     * 处理渠道异步通知。
     *
     * <p>契约：<b>只返回裸字符串、永不向外抛异常</b>。抛异常会走全局异常处理器返回 JSON，
     * 渠道按失败处理并反复重试。</p>
     */
    @Override
    public String handleNotify(Map<String, String> params) {
        try {
            if (!paymentChannel.verifyNotify(params)) {
                log.warn("[支付回调] 验签失败：out_trade_no={}", params.get("out_trade_no"));
                return NOTIFY_FAILURE;
            }
            ChannelNotify notify = ChannelNotify.parse(params);
            if (!StringUtils.hasText(notify.outTradeNo())) {
                log.warn("[支付回调] 通知缺少 out_trade_no");
                return NOTIFY_FAILURE;
            }

            PaymentRecord record = findByTradeNo(notify.outTradeNo());
            if (record == null) {
                // 非本系统的通知：应答 success，避免渠道做无意义的重试轰炸
                log.warn("[支付回调] 支付单不存在，忽略：{}", notify.outTradeNo());
                return NOTIFY_SUCCESS;
            }
            if (record.getStatus() != null && record.getStatus() == STATUS_SUCCESS) {
                log.info("[支付回调] 支付单 {} 已成功，忽略重复通知", notify.outTradeNo());
                return NOTIFY_SUCCESS;
            }
            // 金额必须一致，防伪造（out_trade_no 即平台流水号，映射关系唯一）
            if (notify.amount() == null
                    || record.getAmount() == null
                    || notify.amount().compareTo(record.getAmount()) != 0) {
                log.warn("[支付回调] 金额不一致：通知 {} vs 支付单 {}", notify.amount(), record.getAmount());
                return NOTIFY_FAILURE;
            }
            if (notify.isWaitBuyerPay()) {
                // 中间态：不动状态，但必须应答 success，否则渠道会一直重推
                log.info("[支付回调] 支付单 {} 处于等待付款中间态，不改状态", notify.outTradeNo());
                return NOTIFY_SUCCESS;
            }
            if (notify.isClosed()) {
                markClosed(record, "渠道关闭通知");
                return NOTIFY_SUCCESS;
            }
            if (!notify.isPaid()) {
                log.warn("[支付回调] 未识别的交易状态 {}，忽略", notify.tradeStatus());
                return NOTIFY_SUCCESS;
            }
            applySuccess(record, notify.channelTradeNo(), summarize(params));
            return NOTIFY_SUCCESS;
        } catch (Exception e) {
            log.error("[支付回调] 处理异常", e);
            return NOTIFY_FAILURE;
        }
    }

    @Override
    public int compensatePending(int batchSize) {
        LocalDateTime now = LocalDateTime.now();
        PaymentProperties.Compensation cfg = paymentProperties.getCompensation();
        List<PaymentRecord> pendings = paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getStatus, STATUS_PENDING)
                // 「创建满 N 秒」用 <= ：create_time 是秒精度，用 < 会让「恰好同一秒创建」的单被漏掉
                .le(PaymentRecord::getCreateTime, now.minusSeconds(cfg.getMinAgeSeconds()))
                .gt(PaymentRecord::getCreateTime, now.minusSeconds(cfg.getMaxAgeSeconds()))
                .orderByAsc(PaymentRecord::getId)
                .last("limit " + Math.max(1, batchSize)));
        int changed = 0;
        for (PaymentRecord pending : pendings) {
            try {
                if (queryAndApply(pending)) {
                    changed++;
                }
            } catch (Exception e) {
                // 单笔失败不中断整批
                log.warn("支付单 {} 主动查单失败", pending.getTradeNo(), e);
            }
        }
        if (changed > 0) {
            log.info("[支付补偿] 主动查单修正 {} 笔（本批扫描 {} 笔）", changed, pendings.size());
        }
        return changed;
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    /**
     * 落账：CAS 置成功 → 驱动订单 → 订单已关闭则转退款。
     *
     * <p>回调与主动查单都收敛到本方法，{@code status != 1} 的条件更新是「只落账一次」的唯一判定。</p>
     */
    private void applySuccess(PaymentRecord record, String channelTradeNo, String content) {
        PaymentRecord update = new PaymentRecord();
        update.setStatus(STATUS_SUCCESS);
        if (StringUtils.hasText(channelTradeNo)) {
            update.setChannelTradeNo(channelTradeNo);
        }
        update.setCallbackTime(LocalDateTime.now());
        update.setCallbackContent(cap(content));
        int rows = paymentRecordMapper.update(
                update,
                new LambdaUpdateWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getId, record.getId())
                        .ne(PaymentRecord::getStatus, STATUS_SUCCESS));
        if (rows == 0) {
            log.info("支付单 {} 已被并发处理为成功，忽略", record.getTradeNo());
            return;
        }
        record.setStatus(STATUS_SUCCESS);
        if (StringUtils.hasText(channelTradeNo)) {
            record.setChannelTradeNo(channelTradeNo);
        }

        TradeService.PaymentApplyResult result =
                tradeService.applyPayment(record.getOrderNo(), record.getTradeNo(), record.getPayMethod());
        if (result == TradeService.PaymentApplyResult.ORDER_CLOSED) {
            // 钱收到了但订单已关闭 —— 资金必须有出路
            log.warn("订单 {} 已关闭，支付单 {} 转退款", record.getOrderNo(), record.getTradeNo());
            paymentRefundService.refundForClosedOrder(record, "订单已关闭，支付成功转退款");
        }
    }

    private void markClosed(PaymentRecord record, String content) {
        PaymentRecord update = new PaymentRecord();
        update.setStatus(STATUS_CLOSED);
        update.setCallbackContent(cap(content));
        int rows = paymentRecordMapper.update(
                update,
                new LambdaUpdateWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getId, record.getId())
                        .ne(PaymentRecord::getStatus, STATUS_SUCCESS));
        if (rows > 0) {
            log.info("支付单 {} 已置为关闭", record.getTradeNo());
        }
    }

    /** 主动向渠道查单并按结果落账；返回是否发生状态变更。 */
    private boolean queryAndApply(PaymentRecord record) {
        PaymentChannelClient.ChannelTradeState state = paymentChannel.query(record.getTradeNo());
        return switch (state.status()) {
            case SUCCESS -> {
                if (state.amount() != null
                        && record.getAmount() != null
                        && state.amount().compareTo(record.getAmount()) != 0) {
                    log.warn(
                            "支付单 {} 主动查单金额不一致：渠道 {} vs 本地 {}", record.getTradeNo(), state.amount(), record.getAmount());
                    yield false;
                }
                applySuccess(record, state.channelTradeNo(), "主动查单确认支付成功");
                yield true;
            }
            case CLOSED -> {
                markClosed(record, "主动查单确认已关闭");
                yield true;
            }
            case WAIT_PAY, NOT_FOUND -> false;
        };
    }

    /**
     * 无公网回调通道（内网穿透）时的主链路：前端轮询状态时顺带主动查单。
     *
     * <p>保护措施：只查待支付单 + 同一支付单在 {@code throttle-seconds} 内最多查一次
     * （Redis SET NX）—— 否则一个用户开多个页面就能把网关打爆、并误触发熔断。</p>
     *
     * <p><b>刻意不用「创建满 N 秒」做前置过滤</b>：{@code create_time} 由数据库的
     * {@code CURRENT_TIMESTAMP} 生成并截断到秒，而比较基准是 JVM 的 {@code now}。
     * 应用与数据库的时钟只要有毫秒级漂移（NTP 同步误差、容器时钟偏移都会造成），
     * 刚写入的行就可能「比现在还新」，导致查单被静默跳过。
     * 真正需要的保护是节流，不是这个时间窗。</p>
     *
     * <p>查单失败（网关异常 / 熔断打开）不影响「查状态」：吞掉异常，等下一轮或补偿任务再试。</p>
     */
    private PaymentRecord queryChannelIfNeeded(PaymentRecord record) {
        PaymentProperties.StatusQuery cfg = paymentProperties.getStatusQuery();
        if (!cfg.isEnabled() || record.getStatus() == null || record.getStatus() != STATUS_PENDING) {
            return record;
        }
        Boolean acquired = redisTemplate
                .opsForValue()
                .setIfAbsent(
                        RedisKeys.payQueryThrottle(record.getTradeNo()),
                        "1",
                        Duration.ofSeconds(cfg.getThrottleSeconds()));
        if (!Boolean.TRUE.equals(acquired)) {
            return record;
        }
        try {
            if (queryAndApply(record)) {
                return findByTradeNo(record.getTradeNo());
            }
        } catch (Exception e) {
            log.warn("支付单 {} 查询状态时主动查单失败：{}", record.getTradeNo(), e.getMessage());
        }
        return record;
    }

    /** 查找同一订单 + 同一方式 + 同一渠道下未过期的待支付单。 */
    private PaymentRecord findReusable(String orderNo, String payMethod, String channel) {
        return paymentRecordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getOrderNo, orderNo)
                .eq(PaymentRecord::getPayMethod, payMethod)
                .eq(PaymentRecord::getChannel, channel)
                .eq(PaymentRecord::getStatus, STATUS_PENDING)
                .orderByDesc(PaymentRecord::getId)
                .last("limit 1"));
    }

    private PaymentRecord findByTradeNo(String tradeNo) {
        return paymentRecordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getTradeNo, tradeNo)
                .last("limit 1"));
    }

    private PaymentRecord requireOwnRecord(Long userId, String tradeNo) {
        PaymentRecord record = findByTradeNo(tradeNo);
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
        vo.setChannel(record.getChannel());
        vo.setRefundStatus(record.getRefundStatus());
        vo.setCallbackTime(record.getCallbackTime());
        vo.setExpireSecondsLeft(0L);
        // 二维码来自渠道预下单（本地渠道为本地串，支付宝为真实 qr_code）
        vo.setQrCode(record.getPrepayQr());
        return vo;
    }

    /** 只留关键字段做留痕，避免把整份通知（含 fund_bill_list）塞进 2000 字符的列。 */
    private String summarize(Map<String, String> params) {
        return "out_trade_no=" + params.get("out_trade_no")
                + ",trade_no=" + params.get("trade_no")
                + ",trade_status=" + params.get("trade_status")
                + ",total_amount=" + params.get("total_amount");
    }

    private String cap(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= MAX_CONTENT_LENGTH ? content : content.substring(0, MAX_CONTENT_LENGTH);
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
