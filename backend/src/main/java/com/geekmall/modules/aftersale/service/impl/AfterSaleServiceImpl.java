package com.geekmall.modules.aftersale.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.aftersale.dto.ApplyAfterSaleDTO;
import com.geekmall.modules.aftersale.entity.AfterSale;
import com.geekmall.modules.aftersale.enums.BuyerAfterSaleStatus;
import com.geekmall.modules.aftersale.mapper.AfterSaleMapper;
import com.geekmall.modules.aftersale.service.AfterSaleService;
import com.geekmall.modules.aftersale.vo.AfterSaleStepVO;
import com.geekmall.modules.aftersale.vo.AfterSaleVO;
import com.geekmall.modules.merchant.support.MerchantAftersaleStatus;
import com.geekmall.modules.payment.service.PaymentRefundService;
import com.geekmall.modules.trade.service.OrderService;
import com.geekmall.modules.trade.vo.OrderVO;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 售后服务实现。
 *
 * <p>订单归属与「是否可申请售后」的判断复用交易域的 {@code OrderVO.canAfterSale}，
 * 避免把订单状态机在售后域里再写一遍。</p>
 *
 * <p>状态展示口径统一走 {@link BuyerAfterSaleStatus}：买家侧 {@code status} 只有三态，
 * 而商家侧真实的处置结果在 {@code merchant_status} 上 —— 商家拒绝与买家撤销都落成 2，
 * 直接按数字列展示会把「商家拒绝」显示成「你已取消」。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AfterSaleServiceImpl implements AfterSaleService {

    private static final int STATUS_DOING = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_CANCELED = 2;

    /** 仅这两种类型涉及真实退款；换货/维修不产生资金流。 */
    private static final String TYPE_REFUND = "refund";

    private static final String TYPE_RETURN = "return";

    /** 服务类型 → 展示名 */
    private static final Map<String, String> TYPE_NAMES = new LinkedHashMap<>();

    static {
        TYPE_NAMES.put("refund", "仅退款");
        TYPE_NAMES.put("return", "退货退款");
        TYPE_NAMES.put("exchange", "换货");
        TYPE_NAMES.put("repair", "维修");
    }

    private final AfterSaleMapper afterSaleMapper;
    private final OrderService orderService;
    private final PaymentRefundService paymentRefundService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long apply(Long userId, ApplyAfterSaleDTO dto) {
        OrderVO order = orderService.detail(userId, dto.getOrderNo()).getOrder();
        if (!Boolean.TRUE.equals(order.getCanAfterSale())) {
            throw new BizException(ResultCode.BIZ_ERROR, "当前订单为「" + order.getStatusText() + "」，不可申请售后");
        }
        Long doing = afterSaleMapper.selectCount(new LambdaQueryWrapper<AfterSale>()
                .eq(AfterSale::getUserId, userId)
                .eq(AfterSale::getOrderNo, dto.getOrderNo())
                .eq(AfterSale::getStatus, STATUS_DOING));
        if (doing != null && doing > 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "该订单已有处理中的售后申请");
        }

        AfterSale entity = new AfterSale();
        entity.setUserId(userId);
        entity.setOrderNo(dto.getOrderNo());
        entity.setType(dto.getType());
        entity.setTypeName(TYPE_NAMES.getOrDefault(dto.getType(), dto.getType()));
        entity.setReason(dto.getReason());
        entity.setContent(dto.getContent());
        entity.setImages(
                dto.getImages() == null || dto.getImages().isEmpty() ? null : String.join(",", dto.getImages()));
        entity.setPhone(dto.getPhone());
        // 退款/退货涉及金额，换货/维修不涉及
        entity.setAmount(
                switch (dto.getType()) {
                    case TYPE_REFUND, TYPE_RETURN -> order.getPayAmount() == null
                            ? BigDecimal.ZERO
                            : order.getPayAmount();
                    default -> BigDecimal.ZERO;
                });
        entity.setStatus(STATUS_DOING);
        try {
            afterSaleMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // P2-1：唯一索引 uk_order_doing 兜底，并发重复提交时给出友好提示
            throw new BizException(ResultCode.BIZ_ERROR, "该订单已有处理中的售后申请");
        }
        log.info("用户 {} 就订单 {} 提交售后申请 {}，类型 {}", userId, dto.getOrderNo(), entity.getId(), dto.getType());
        return entity.getId();
    }

    @Override
    public List<AfterSaleVO> list(Long userId, Integer status) {
        List<AfterSale> records = afterSaleMapper.selectList(new LambdaQueryWrapper<AfterSale>()
                .eq(AfterSale::getUserId, userId)
                .eq(status != null, AfterSale::getStatus, status)
                .orderByDesc(AfterSale::getId));
        if (records.isEmpty()) {
            return List.of();
        }
        // 一次查出所有订单的首件商品名，避免逐条查询
        Map<String, String> titles = orderService.firstItemTitles(
                records.stream().map(AfterSale::getOrderNo).distinct().toList());
        return records.stream()
                .map(record -> toVO(record, titles.get(record.getOrderNo()), false))
                .toList();
    }

    @Override
    public AfterSaleVO detail(Long userId, Long id) {
        AfterSale record = requireOwn(userId, id);
        Map<String, String> titles = orderService.firstItemTitles(List.of(record.getOrderNo()));
        return toVO(record, titles.get(record.getOrderNo()), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, Long id) {
        AfterSale record = requireOwn(userId, id);
        // 撤销规则：处理中 / 待寄回可撤销（商品还在买家手里，撤销无资金损失）；
        // 已寄回、已完成、已拒绝、已取消都不可撤销
        BuyerAfterSaleStatus current = BuyerAfterSaleStatus.resolve(record);
        if (!current.isCancelable()) {
            throw new BizException(ResultCode.BIZ_ERROR, "当前售后" + current.getText() + "，无法取消");
        }
        AfterSale update = new AfterSale();
        update.setId(id);
        update.setStatus(STATUS_CANCELED);
        afterSaleMapper.updateById(update);
        log.info("用户 {} 取消售后申请 {}", userId, id);
    }

    /**
     * 买家已寄回商品：wait_return → wait_receive。
     *
     * <p>用买家侧 CAS（带 {@code user_id} 条件）而不是先读后写：
     * 商家可能同时点了「确认收货」或买家已撤销，只有 CAS 能保证不会把终态改回中间态。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markReturned(Long userId, Long id) {
        AfterSale record = requireOwn(userId, id);
        BuyerAfterSaleStatus current = BuyerAfterSaleStatus.resolve(record);
        if (current != BuyerAfterSaleStatus.WAIT_RETURN) {
            throw new BizException(ResultCode.BIZ_ERROR, "当前售后为「" + current.getText() + "」，无法登记寄回");
        }
        int rows = afterSaleMapper.transferStatusByBuyer(
                id, userId, MerchantAftersaleStatus.WAIT_RETURN, MerchantAftersaleStatus.WAIT_RECEIVE);
        if (rows == 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "工单状态已变更，请刷新后重试");
        }
        log.info("用户 {} 登记售后 {} 已寄回，等待商家确认收货", userId, id);
    }

    /**
     * 售后审批通过后触发退款。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：退款要发起渠道 HTTP 调用。
     * 调用方（商家域）必须在本域事务<b>提交之后</b>再调用本方法，否则会出现
     * 「渠道已退款但本地事务回滚」这种最难对账的中间态。</p>
     *
     * <p>幂等兜底在支付域：{@code refund_no = "RFAS" + 售后单号} 的唯一索引，
     * 重复调用只会有一笔真正发起。</p>
     */
    @Override
    public boolean refundAfterSale(Long afterSaleId) {
        AfterSale record = afterSaleMapper.selectById(afterSaleId);
        if (record == null) {
            log.warn("售后单 {} 不存在，跳过退款", afterSaleId);
            return false;
        }
        if (!TYPE_REFUND.equals(record.getType()) && !TYPE_RETURN.equals(record.getType())) {
            log.debug("售后单 {} 类型为 {}，不涉及退款", afterSaleId, record.getType());
            return false;
        }
        BigDecimal amount = record.getAmount();
        if (amount == null || amount.signum() <= 0) {
            log.warn("售后单 {} 退款金额为 {}，跳过退款", afterSaleId, amount);
            return false;
        }
        boolean refunded = paymentRefundService.refundForAfterSale(
                record.getOrderNo(), String.valueOf(afterSaleId), amount, "售后退款（" + record.getTypeName() + "）");
        if (refunded) {
            log.info("售后单 {} 已发起退款 ¥{}（订单 {}）", afterSaleId, amount, record.getOrderNo());
        }
        return refunded;
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    private AfterSale requireOwn(Long userId, Long id) {
        AfterSale record = afterSaleMapper.selectById(id);
        if (record == null || !record.getUserId().equals(userId)) {
            throw new BizException(ResultCode.NOT_FOUND, "售后单不存在");
        }
        return record;
    }

    private AfterSaleVO toVO(AfterSale record, String productTitle, boolean withSteps) {
        BuyerAfterSaleStatus state = BuyerAfterSaleStatus.resolve(record);
        AfterSaleVO vo = new AfterSaleVO();
        vo.setId(record.getId());
        vo.setOrderNo(record.getOrderNo());
        vo.setProductTitle(productTitle);
        vo.setType(record.getType());
        vo.setTypeName(
                record.getTypeName() == null
                        ? TYPE_NAMES.getOrDefault(record.getType(), record.getType())
                        : record.getTypeName());
        vo.setReason(record.getReason());
        vo.setContent(record.getContent());
        vo.setImages(splitImages(record.getImages()));
        vo.setPhone(record.getPhone());
        vo.setAmount(record.getAmount());
        vo.setStatus(record.getStatus());
        vo.setStatusText(state.getText());
        vo.setStatusTip(state.getTip());
        vo.setMerchantStatus(record.getMerchantStatus());
        vo.setRejectReason(record.getRejectReason());
        vo.setCanCancel(state.isCancelable());
        vo.setCreateTime(record.getCreateTime());
        if (withSteps) {
            vo.setSteps(buildSteps(record, state));
        }
        return vo;
    }

    private List<AfterSaleStepVO> buildSteps(AfterSale record, BuyerAfterSaleStatus state) {
        List<AfterSaleStepVO> steps = new ArrayList<>();
        steps.add(new AfterSaleStepVO("提交售后申请", record.getCreateTime(), true));
        switch (state) {
            case REJECTED -> {
                steps.add(new AfterSaleStepVO("商家审核", record.getUpdateTime(), true));
                steps.add(new AfterSaleStepVO(
                        "商家未同意：" + (StringUtils.hasText(record.getRejectReason()) ? record.getRejectReason() : "未填写理由"),
                        record.getUpdateTime(),
                        true));
            }
            case CANCELED -> steps.add(new AfterSaleStepVO("你已取消本次申请", record.getUpdateTime(), true));
            case WAIT_RETURN -> {
                steps.add(new AfterSaleStepVO("商家已同意，等待你寄回商品", record.getUpdateTime(), true));
                steps.add(new AfterSaleStepVO("商家确认收货后退款", null, false));
            }
            case WAIT_RECEIVE -> {
                steps.add(new AfterSaleStepVO("商家已同意", record.getUpdateTime(), true));
                steps.add(new AfterSaleStepVO("商品已寄出，等待商家确认收货", null, false));
            }
            case DONE -> {
                steps.add(new AfterSaleStepVO("商家已处理", record.getUpdateTime(), true));
                steps.add(new AfterSaleStepVO("处理完成，退款将原路退回", record.getUpdateTime(), true));
            }
            case PENDING -> {
                steps.add(new AfterSaleStepVO("商家审核中，请耐心等待", null, false));
                steps.add(new AfterSaleStepVO("处理完成", null, false));
            }
        }
        return steps;
    }

    private List<String> splitImages(String images) {
        if (!StringUtils.hasText(images)) {
            return List.of();
        }
        return Arrays.stream(images.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
