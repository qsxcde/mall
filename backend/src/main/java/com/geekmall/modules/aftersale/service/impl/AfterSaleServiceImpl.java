package com.geekmall.modules.aftersale.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.aftersale.dto.ApplyAfterSaleDTO;
import com.geekmall.modules.aftersale.entity.AfterSale;
import com.geekmall.modules.aftersale.mapper.AfterSaleMapper;
import com.geekmall.modules.aftersale.service.AfterSaleService;
import com.geekmall.modules.aftersale.vo.AfterSaleStepVO;
import com.geekmall.modules.aftersale.vo.AfterSaleVO;
import com.geekmall.modules.trade.service.OrderService;
import com.geekmall.modules.trade.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 售后服务实现。
 *
 * <p>订单归属与「是否可申请售后」的判断复用交易域的 {@code OrderVO.canAfterSale}，
 * 避免把订单状态机在售后域里再写一遍。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AfterSaleServiceImpl implements AfterSaleService {

    private static final int STATUS_DOING = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_CANCELED = 2;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long apply(Long userId, ApplyAfterSaleDTO dto) {
        OrderVO order = orderService.detail(userId, dto.getOrderNo()).getOrder();
        if (!Boolean.TRUE.equals(order.getCanAfterSale())) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "当前订单为「" + order.getStatusText() + "」，不可申请售后");
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
        entity.setImages(dto.getImages() == null || dto.getImages().isEmpty()
                ? null : String.join(",", dto.getImages()));
        entity.setPhone(dto.getPhone());
        // 退款/退货涉及金额，换货/维修不涉及
        entity.setAmount(switch (dto.getType()) {
            case "refund", "return" -> order.getPayAmount() == null ? BigDecimal.ZERO : order.getPayAmount();
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
        return records.stream().map(record -> toVO(record, titles.get(record.getOrderNo()), false)).toList();
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
        if (record.getStatus() == null || record.getStatus() != STATUS_DOING) {
            throw new BizException(ResultCode.BIZ_ERROR, "当前售后已" + statusText(record.getStatus()) + "，无法取消");
        }
        AfterSale update = new AfterSale();
        update.setId(id);
        update.setStatus(STATUS_CANCELED);
        afterSaleMapper.updateById(update);
        log.info("用户 {} 取消售后申请 {}", userId, id);
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
        AfterSaleVO vo = new AfterSaleVO();
        vo.setId(record.getId());
        vo.setOrderNo(record.getOrderNo());
        vo.setProductTitle(productTitle);
        vo.setType(record.getType());
        vo.setTypeName(record.getTypeName() == null
                ? TYPE_NAMES.getOrDefault(record.getType(), record.getType()) : record.getTypeName());
        vo.setReason(record.getReason());
        vo.setContent(record.getContent());
        vo.setImages(splitImages(record.getImages()));
        vo.setPhone(record.getPhone());
        vo.setAmount(record.getAmount());
        vo.setStatus(record.getStatus());
        vo.setStatusText(statusText(record.getStatus()));
        vo.setCreateTime(record.getCreateTime());
        if (withSteps) {
            vo.setSteps(buildSteps(record));
        }
        return vo;
    }

    private List<AfterSaleStepVO> buildSteps(AfterSale record) {
        List<AfterSaleStepVO> steps = new ArrayList<>();
        steps.add(new AfterSaleStepVO("提交售后申请", record.getCreateTime(), true));
        if (record.getStatus() != null && record.getStatus() == STATUS_DOING) {
            steps.add(new AfterSaleStepVO("商家审核中，请耐心等待", null, false));
            steps.add(new AfterSaleStepVO("处理完成", null, false));
        } else {
            steps.add(new AfterSaleStepVO(statusText(record.getStatus()), record.getUpdateTime(), true));
        }
        return steps;
    }

    private List<String> splitImages(String images) {
        if (!StringUtils.hasText(images)) {
            return List.of();
        }
        return Arrays.stream(images.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case STATUS_DOING -> "处理中";
            case STATUS_DONE -> "已完成";
            case STATUS_CANCELED -> "已取消";
            default -> "";
        };
    }
}
