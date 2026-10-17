package com.geekmall.modules.merchant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.aftersale.entity.AfterSale;
import com.geekmall.modules.aftersale.mapper.AfterSaleMapper;
import com.geekmall.modules.aftersale.service.AfterSaleService;
import com.geekmall.modules.merchant.converter.MerchantAftersaleConverter;
import com.geekmall.modules.merchant.dto.AftersaleResolveDTO;
import com.geekmall.modules.merchant.dto.MerchantAftersaleQueryDTO;
import com.geekmall.modules.merchant.mapper.MerchantStatsMapper;
import com.geekmall.modules.merchant.service.MerchantAftersaleService;
import com.geekmall.modules.merchant.support.MerchantAftersaleStatus;
import com.geekmall.modules.merchant.support.Numbers;
import com.geekmall.modules.merchant.vo.MerchantAftersaleVO;
import com.geekmall.modules.merchant.vo.MerchantPageVO;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.entity.OrderItem;
import com.geekmall.modules.trade.mapper.OrderItemMapper;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.modules.user.entity.SysUser;
import com.geekmall.modules.user.mapper.SysUserMapper;
import com.geekmall.security.MerchantSecurityUtils;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 商家端售后管理服务实现。
 *
 * <p><b>退款时机</b>：审批通过只改状态，真正调渠道退款在事务<b>提交之后</b>
 * 由 {@link #resolve} 逐单触发 —— 渠道调用是 HTTP，放进事务会把行锁持有到网络超时，
 * 而且一旦事务回滚就会出现「钱退了、单没改」的对账黑洞。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantAftersaleServiceImpl implements MerchantAftersaleService {

    private final AfterSaleMapper afterSaleMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SysUserMapper sysUserMapper;
    private final MerchantStatsMapper statsMapper;
    private final AfterSaleService afterSaleService;
    /** 经代理调用本类的事务方法，避免自调用导致 {@code @Transactional} 失效。 */
    private final ObjectProvider<MerchantAftersaleServiceImpl> selfProvider;

    @Override
    public MerchantPageVO<MerchantAftersaleVO> page(MerchantAftersaleQueryDTO query) {
        Long shopId = MerchantSecurityUtils.getShopId();
        LambdaQueryWrapper<AfterSale> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AfterSale::getShopId, shopId);
        applyStatusFilter(wrapper, query.getStatus());
        if (StringUtils.hasText(query.getType()) && !"all".equals(query.getType())) {
            wrapper.eq(AfterSale::getType, query.getType());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword().trim();
            wrapper.and(w -> w.like(AfterSale::getOrderNo, kw)
                    .or()
                    .like(AfterSale::getReason, kw)
                    .or()
                    .apply("user_id IN (SELECT id FROM sys_user WHERE nickname LIKE CONCAT('%', {0}, '%'))", kw));
        }
        applySort(wrapper, query.getSort());

        Page<AfterSale> page = new Page<>(query.getPage(), query.getSize());
        IPage<AfterSale> result = afterSaleMapper.selectPage(page, wrapper);
        List<MerchantAftersaleVO> list = assemble(result.getRecords());

        MerchantPageVO<MerchantAftersaleVO> vo =
                new MerchantPageVO<>(list, result.getTotal(), result.getCurrent(), result.getSize());
        vo.put("tabs", buildTabs(shopId));
        vo.put("stats", buildStats(shopId));
        return vo;
    }

    @Override
    public MerchantAftersaleVO detail(String id) {
        Long shopId = MerchantSecurityUtils.getShopId();
        AfterSale item = afterSaleMapper.selectOne(new LambdaQueryWrapper<AfterSale>()
                .eq(AfterSale::getShopId, shopId)
                .eq(AfterSale::getId, MerchantAftersaleConverter.parseId(id))
                .last("limit 1"));
        if (item == null) {
            throw BizException.of(ResultCode.NOT_FOUND);
        }
        return assemble(List.of(item)).stream().findFirst().orElse(null);
    }

    @Override
    public int resolve(AftersaleResolveDTO dto) {
        List<Long> ids =
                dto.getIds().stream().map(MerchantAftersaleConverter::parseId).toList();
        // 1) 状态流转在事务内完成，要么全部生效要么一笔不动
        int affected = selfProvider.getObject().doResolve(dto, ids);
        // 2) 退款在事务提交之后：渠道调用是 HTTP，且必须建立在「状态已落库」之上。
        //    「同意」且类型为退款/退货 → 触发；退货退款要等商家确认收货（receive）才退。
        for (Long id : ids) {
            if (shouldRefund(dto.getAction(), id)) {
                triggerRefund(id);
            }
        }
        return affected;
    }

    /** 本次动作是否应触发退款：仅「同意」纯退款、「确认收货」退货退款两种情况。 */
    private boolean shouldRefund(String action, Long id) {
        if (!"approve".equals(action) && !"receive".equals(action)) {
            return false;
        }
        AfterSale item = afterSaleMapper.selectById(id);
        if (item == null) {
            return false;
        }
        // 退货退款在同意的节点只是「等待买家寄回」，钱要等商家确认收货后才退
        if ("approve".equals(action)) {
            return "refund".equals(item.getType());
        }
        return "return".equals(item.getType());
    }

    /**
     * 触发退款。
     *
     * <p>失败不回滚状态：此时「售后已完成」是事实，缺的是资金动作，
     * 需要对账/补偿处理（退款单已落库为失败态，渠道侧可重试）。
     * 若在此抛异常把状态回滚，商家会看到工单又跳回待处理，重复点同意会产生更多歧义。</p>
     */
    private void triggerRefund(Long afterSaleId) {
        try {
            afterSaleService.refundAfterSale(afterSaleId);
        } catch (Exception e) {
            log.error("售后单 {} 退款发起失败，工单状态已生效，需人工对账", afterSaleId, e);
        }
    }

    /** 批量处理的事务单元，仅供 {@link #resolve} 经代理调用。 */
    @Transactional(rollbackFor = Exception.class)
    public int doResolve(AftersaleResolveDTO dto, List<Long> ids) {
        Long shopId = MerchantSecurityUtils.getShopId();
        String action = dto.getAction();
        // 动作允许的前置状态，非法动作直接拒绝（同时起到「状态机」的作用）
        String expected = expectedStatusOf(action);

        Map<Long, AfterSale> byId = afterSaleMapper
                .selectList(new LambdaQueryWrapper<AfterSale>()
                        .eq(AfterSale::getShopId, shopId)
                        .in(AfterSale::getId, ids))
                .stream()
                .collect(Collectors.toMap(AfterSale::getId, Function.identity(), (a, b) -> a));
        if (byId.isEmpty()) {
            throw BizException.of(ResultCode.NOT_FOUND);
        }

        // 逐笔走 CAS：影响行数 0 说明状态在读取后被他人改过，或本就不满足该动作的前置状态。
        // 只读到的 type 用于判断「仅退款 vs 退货退款」，该字段不可变，不存在竞态。
        List<String> conflicts = new ArrayList<>();
        int affected = 0;
        for (Long id : ids) {
            AfterSale item = byId.get(id);
            if (item == null) {
                conflicts.add(MerchantAftersaleConverter.toId(id));
                continue;
            }
            boolean pureRefund = "refund".equals(item.getType());
            String target =
                    switch (action) {
                        case "approve" -> pureRefund
                                ? MerchantAftersaleStatus.DONE
                                : MerchantAftersaleStatus.WAIT_RETURN;
                        case "reject" -> MerchantAftersaleStatus.REJECTED;
                        case "receive" -> MerchantAftersaleStatus.DONE;
                        default -> MerchantAftersaleStatus.PENDING;
                    };
            Integer buyerStatus =
                    switch (action) {
                        case "approve" -> pureRefund ? 1 : 0;
                            // 拒绝时买家侧的 status 必须与「取消」区分开：
                            // 写 2 会让买家看到「你已取消」，而实际上是被商家拒绝。
                            // 这里保持 0（处理中）+ merchant_status=rejected，
                            // 买家侧状态由 BuyerAfterSaleStatus 优先读 merchant_status 解析。
                        case "reject" -> 0;
                        case "receive" -> 1;
                        default -> 0;
                    };
            int rows = afterSaleMapper.transferStatus(
                    id,
                    shopId,
                    expected,
                    target,
                    buyerStatus,
                    rejectReasonOf(dto, action),
                    // 仅「同意」时允许调整退款金额，其余动作不改金额
                    "approve".equals(action) ? dto.getRefundAmount() : null);
            if (rows == 0) {
                conflicts.add(MerchantAftersaleConverter.toId(id));
            } else {
                affected += rows;
            }
        }

        if (!conflicts.isEmpty()) {
            // 抛异常触发整体回滚：批量处理要么全部生效，要么一笔都不动，
            // 避免出现「一半工单已退款、一半没退」的中间态
            throw new BizException(ResultCode.BIZ_ERROR, "以下工单已被处理或当前状态不允许该操作，请刷新后重试：" + String.join("、", conflicts));
        }
        return affected;
    }

    /** 各动作允许的前置状态；非法动作在此抛出。 */
    private static String expectedStatusOf(String action) {
        return switch (action) {
            case "approve", "reject" -> MerchantAftersaleStatus.PENDING;
                // 退货退款的「确认收货」发生在买家寄回之后，前置状态是 wait_receive。
                // 原实现写成 wait_return，导致该动作永远命中不了合法前置状态（死路径）。
            case "receive" -> MerchantAftersaleStatus.WAIT_RECEIVE;
            case "reaudit" -> MerchantAftersaleStatus.REJECTED;
            default -> throw new BizException(ResultCode.PARAM_ERROR, "非法的处理动作：" + action);
        };
    }

    private static String rejectReasonOf(AftersaleResolveDTO dto, String action) {
        if (!"reject".equals(action)) {
            return "";
        }
        return StringUtils.hasText(dto.getRejectReason()) ? dto.getRejectReason() : "商家拒绝，理由未填写";
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    /**
     * 状态筛选。
     *
     * <p>用统一口径 {@link MerchantAftersaleStatus#STATUS_SQL} 直接比较，
     * 而不是逐状态写「merchant_status = X OR merchant_status IS NULL AND status = Y」——
     * 后者每加一个状态就要补一套分支，且极易与列表展示口径错开。
     * status 先经白名单校验，因此拼进 SQL 是安全的。</p>
     */
    private void applyStatusFilter(LambdaQueryWrapper<AfterSale> wrapper, String status) {
        if (!StringUtils.hasText(status) || "all".equals(status)) {
            return;
        }
        if (!MerchantAftersaleStatus.ALL.contains(status)) {
            throw new BizException(ResultCode.PARAM_ERROR, "非法的工单状态：" + status);
        }
        wrapper.apply(MerchantAftersaleStatus.STATUS_SQL + " = '" + status + "'");
    }

    private void applySort(LambdaQueryWrapper<AfterSale> wrapper, String sort) {
        String key = sort == null ? "" : sort;
        switch (key) {
            case "apply_asc" -> wrapper.orderByAsc(AfterSale::getCreateTime);
            case "amount_desc" -> wrapper.orderByDesc(AfterSale::getAmount);
                // 处理时限最紧迫的排前面：越早申请越紧急，用申请时间升序近似
            case "deadline" -> wrapper.orderByAsc(AfterSale::getCreateTime);
            default -> wrapper.orderByDesc(AfterSale::getCreateTime);
        }
    }

    private List<MerchantAftersaleVO> assemble(List<AfterSale> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<String> orderNos = items.stream()
                .map(AfterSale::getOrderNo)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, Order> orderMap = orderNos.isEmpty()
                ? Map.of()
                : orderMapper.selectList(new LambdaQueryWrapper<Order>().in(Order::getOrderNo, orderNos)).stream()
                        .collect(Collectors.toMap(Order::getOrderNo, Function.identity(), (a, b) -> a));
        Map<String, OrderItem> itemMap = orderNos.isEmpty()
                ? Map.of()
                : orderItemMapper
                        .selectList(new LambdaQueryWrapper<OrderItem>().in(OrderItem::getOrderNo, orderNos))
                        .stream()
                        .collect(Collectors.toMap(OrderItem::getOrderNo, Function.identity(), (a, b) -> a));
        Set<Long> userIds = items.stream()
                .map(AfterSale::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, SysUser> userMap = userIds.isEmpty()
                ? Map.of()
                : sysUserMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));

        return items.stream()
                .map(item -> MerchantAftersaleConverter.toVO(
                        item,
                        orderMap.get(item.getOrderNo()),
                        itemMap.get(item.getOrderNo()),
                        userMap.get(item.getUserId())))
                .toList();
    }

    private Map<String, Object> buildTabs(Long shopId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : statsMapper.aftersaleStatusCounts(shopId)) {
            counts.merge(String.valueOf(row.get("merchantStatus")), Numbers.l(row.get("cnt")), Long::sum);
        }
        Map<String, Object> tabs = new LinkedHashMap<>();
        long all = 0;
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            all += entry.getValue();
        }
        tabs.put("all", all);
        for (String key : MerchantAftersaleStatus.ALL) {
            tabs.put(key, counts.getOrDefault(key, 0L));
        }
        return tabs;
    }

    private Map<String, Object> buildStats(Long shopId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : statsMapper.aftersaleStatusCounts(shopId)) {
            counts.merge(String.valueOf(row.get("merchantStatus")), Numbers.l(row.get("cnt")), Long::sum);
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long done = counts.getOrDefault(MerchantAftersaleStatus.DONE, 0L);

        List<AfterSale> finished = afterSaleMapper.selectList(new LambdaQueryWrapper<AfterSale>()
                .eq(AfterSale::getShopId, shopId)
                .eq(AfterSale::getMerchantStatus, MerchantAftersaleStatus.DONE));
        BigDecimal refundTotal =
                afterSaleMapper
                        .selectList(new LambdaQueryWrapper<AfterSale>().eq(AfterSale::getShopId, shopId))
                        .stream()
                        .map(AfterSale::getAmount)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal doneAmount = finished.stream()
                .map(AfterSale::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long orderCount = Numbers.l(
                statsMapper.orderSummary(shopId) == null
                        ? null
                        : statsMapper.orderSummary(shopId).get("totalCount"));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pending", counts.getOrDefault(MerchantAftersaleStatus.PENDING, 0L));
        stats.put("waitReturn", counts.getOrDefault(MerchantAftersaleStatus.WAIT_RETURN, 0L));
        stats.put("waitReceive", counts.getOrDefault(MerchantAftersaleStatus.WAIT_RECEIVE, 0L));
        stats.put("done", done);
        stats.put("refundTotal", refundTotal);
        stats.put("refundRate", Numbers.round(Numbers.divide(total, orderCount), 4));
        stats.put("doneAmount", doneAmount);
        return stats;
    }
}
