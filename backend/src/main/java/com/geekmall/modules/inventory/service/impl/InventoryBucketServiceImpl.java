package com.geekmall.modules.inventory.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.PageResult;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.inventory.dto.BucketAllocateDTO;
import com.geekmall.modules.inventory.dto.BucketDeductDTO;
import com.geekmall.modules.inventory.dto.BucketLogQueryDTO;
import com.geekmall.modules.inventory.dto.BucketMergeDTO;
import com.geekmall.modules.inventory.dto.BucketQueryDTO;
import com.geekmall.modules.inventory.dto.BucketRuleQueryDTO;
import com.geekmall.modules.inventory.dto.BucketRuleSaveDTO;
import com.geekmall.modules.inventory.dto.BucketTransferDTO;
import com.geekmall.modules.inventory.entity.InvBucket;
import com.geekmall.modules.inventory.entity.InvBucketLog;
import com.geekmall.modules.inventory.entity.InvBucketOperation;
import com.geekmall.modules.inventory.entity.InvBucketRule;
import com.geekmall.modules.inventory.enums.BucketBizType;
import com.geekmall.modules.inventory.enums.BucketDimension;
import com.geekmall.modules.inventory.enums.BucketOpType;
import com.geekmall.modules.inventory.enums.DeductPolicy;
import com.geekmall.modules.inventory.mapper.InvBucketLogMapper;
import com.geekmall.modules.inventory.mapper.InvBucketMapper;
import com.geekmall.modules.inventory.mapper.InvBucketOperationMapper;
import com.geekmall.modules.inventory.mapper.InvBucketRuleMapper;
import com.geekmall.modules.inventory.service.InventoryBucketService;
import com.geekmall.modules.inventory.vo.BucketBalanceVO;
import com.geekmall.modules.inventory.vo.BucketDeductResultVO;
import com.geekmall.modules.inventory.vo.BucketLogVO;
import com.geekmall.modules.inventory.vo.BucketOperationVO;
import com.geekmall.modules.inventory.vo.BucketReportVO;
import com.geekmall.modules.inventory.vo.BucketRuleVO;
import com.geekmall.modules.inventory.vo.BucketVO;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.security.MerchantSecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 库存分桶服务实现。
 *
 * <p>设计要点（与压测文档附录 C/D 对齐）：</p>
 * <ol>
 *   <li><b>桶是并发单元</b>：余量变更全部走 {@code UPDATE ... WHERE stock >= qty} 的 CAS，
 *       不做「先查再改」，也不用乐观锁，避免读改写竞态；</li>
 *   <li><b>出库分流</b>：按优先级挑选有货的桶逐个扣减，「桶空了」才换下一个桶，
 *       「桶被锁住」只会在该桶上排队（或在 CAS 失败后重试），不跳行；</li>
 *   <li><b>守恒</b>：任何桶余量变化都写审计流水；出库同时扣减 {@code pms_product.stock}，
 *       调拨 / 合并 / 分配不改变商品总库存，保证 {@code SUM(桶) == 商品总库存}；</li>
 *   <li><b>幂等</b>：出库 / 回滚以 {@code order_no} 为幂等键（唯一索引 + 先查后写）。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class InventoryBucketServiceImpl implements InventoryBucketService {

    /** 未参与到显式分配中的余量，统一落入该「未分配」桶，避免库存凭空消失。 */
    private static final String UNALLOCATED = "未分配";

    /** 出库 CAS 冲突时的最大重试轮数（重新选桶）。 */
    private static final int MAX_DEDUCT_ROUNDS = 5;

    private static final DateTimeFormatter OP_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final InvBucketRuleMapper ruleMapper;
    private final InvBucketMapper bucketMapper;
    private final InvBucketOperationMapper operationMapper;
    private final InvBucketLogMapper logMapper;
    private final ProductMapper productMapper;

    /* ============================== 分桶规则 ============================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveRule(BucketRuleSaveDTO dto) {
        Long shopId = MerchantSecurityUtils.getShopId();
        BucketDimension dimension = parseDimension(dto.getDimension());
        DeductPolicy policy = parsePolicy(dto.getDeductPolicy());

        if (dto.getId() == null) {
            InvBucketRule rule = new InvBucketRule();
            rule.setShopId(shopId);
            rule.setRuleName(dto.getRuleName());
            rule.setDimension(dimension.name());
            rule.setGranularity(StringUtils.hasText(dto.getGranularity()) ? dto.getGranularity() : "SKU");
            rule.setDeductPolicy(policy.name());
            rule.setProductId(dto.getProductId());
            rule.setEnabled(dto.getEnabled() == null ? 1 : dto.getEnabled());
            rule.setRemark(dto.getRemark());
            ruleMapper.insert(rule);
            return rule.getId();
        }

        InvBucketRule exist = requireRule(dto.getId(), shopId);
        exist.setRuleName(dto.getRuleName());
        exist.setDimension(dimension.name());
        exist.setGranularity(StringUtils.hasText(dto.getGranularity()) ? dto.getGranularity() : exist.getGranularity());
        exist.setDeductPolicy(policy.name());
        exist.setProductId(dto.getProductId());
        if (dto.getEnabled() != null) {
            exist.setEnabled(dto.getEnabled());
        }
        exist.setRemark(dto.getRemark());
        ruleMapper.updateById(exist);
        return exist.getId();
    }

    @Override
    public List<BucketRuleVO> listRules(BucketRuleQueryDTO query) {
        Long shopId = MerchantSecurityUtils.getShopId();
        LambdaQueryWrapper<InvBucketRule> wrapper = new LambdaQueryWrapper<InvBucketRule>()
                .eq(InvBucketRule::getShopId, shopId);
        if (StringUtils.hasText(query.getDimension())) {
            wrapper.eq(InvBucketRule::getDimension, parseDimension(query.getDimension()).name());
        }
        if (query.getEnabled() != null) {
            wrapper.eq(InvBucketRule::getEnabled, query.getEnabled());
        }
        if (query.getProductId() != null) {
            wrapper.and(w -> w.eq(InvBucketRule::getProductId, query.getProductId())
                    .or().isNull(InvBucketRule::getProductId));
        }
        wrapper.orderByDesc(InvBucketRule::getId);
        return ruleMapper.selectList(wrapper).stream().map(this::toRuleVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateRuleStatus(Long ruleId, Integer enabled) {
        Long shopId = MerchantSecurityUtils.getShopId();
        InvBucketRule rule = requireRule(ruleId, shopId);
        if (enabled == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "启用状态不能为空");
        }
        rule.setEnabled(enabled == 0 ? 0 : 1);
        return ruleMapper.updateById(rule);
    }

    /* ============================== 库存分配 ============================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BucketBalanceVO allocate(BucketAllocateDTO dto) {
        Long shopId = MerchantSecurityUtils.getShopId();
        Product product = requireProduct(dto.getProductId(), shopId);
        InvBucketRule rule = dto.getRuleId() == null ? null : requireRule(dto.getRuleId(), shopId);
        BucketDimension dimension = rule != null
                ? BucketDimension.of(rule.getDimension())
                : parseDimension(dto.getDimension());

        int productStock = product.getStock() == null ? 0 : product.getStock();
        Map<String, BucketAllocateDTO.Item> merged = mergeItems(dto.getItems());
        int placed = merged.values().stream().mapToInt(i -> i.getQty() == null ? 0 : i.getQty()).sum();
        if (placed > productStock) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "分配总量 " + placed + " 超过商品库存 " + productStock);
        }

        // 1) 重新布局：先把该商品已有桶的余量清零（留痕为 ADJUST），再把明细逐桶写入。
        for (InvBucket exist : listBuckets(product.getId())) {
            int before = exist.getStock() == null ? 0 : exist.getStock();
            if (before > 0) {
                writeStock(exist, 0);
                log(exist.getId(), product.getId(), shopId, BucketBizType.ADJUST, -before,
                        before, 0, null, null, exist.getDimensionValue(), dto.getOperator(), "重新分配前清零");
            }
        }

        // 2) 写入显式分配的桶
        for (BucketAllocateDTO.Item item : merged.values()) {
            int qty = item.getQty() == null ? 0 : item.getQty();
            upsertBucket(product.getId(), shopId, dto.getRuleId(), dimension, item, qty, dto.getOperator());
        }

        // 3) 未被显式分配的余量落入「未分配」桶，保证守恒
        int remainder = productStock - placed;
        if (remainder > 0) {
            BucketAllocateDTO.Item rest = new BucketAllocateDTO.Item();
            rest.setDimensionValue(UNALLOCATED);
            upsertBucket(product.getId(), shopId, dto.getRuleId(), dimension, rest, remainder, dto.getOperator());
        }
        return balance(product.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BucketBalanceVO autoSplit(Long productId, Long ruleId, List<String> dimensionValues) {
        Long shopId = MerchantSecurityUtils.getShopId();
        Product product = requireProduct(productId, shopId);
        requireRule(ruleId, shopId);
        if (dimensionValues == null || dimensionValues.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "维度值列表不能为空");
        }
        int total = product.getStock() == null ? 0 : product.getStock();
        int n = dimensionValues.size();
        int base = total / n;
        int remainder = total % n;

        BucketAllocateDTO dto = new BucketAllocateDTO();
        dto.setProductId(productId);
        dto.setRuleId(ruleId);
        dto.setOperator("autoSplit");
        List<BucketAllocateDTO.Item> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BucketAllocateDTO.Item item = new BucketAllocateDTO.Item();
            item.setDimensionValue(dimensionValues.get(i));
            item.setQty(i == 0 ? base + remainder : base);
            items.add(item);
        }
        dto.setItems(items);
        return allocate(dto);
    }

    /* ============================== 余量查询 ============================== */

    @Override
    public BucketBalanceVO balance(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BizException(ResultCode.NOT_FOUND, "商品不存在");
        }
        List<InvBucket> buckets = listBuckets(productId);
        int bucketTotal = buckets.stream().mapToInt(b -> b.getStock() == null ? 0 : b.getStock()).sum();
        int productStock = product.getStock() == null ? 0 : product.getStock();

        BucketBalanceVO vo = new BucketBalanceVO();
        vo.setProductId(productId);
        vo.setProductStock(productStock);
        vo.setBucketStockTotal(bucketTotal);
        vo.setDelta(productStock - bucketTotal);
        vo.setConsistent(bucketTotal == productStock);
        vo.setBucketCount(buckets.size());
        vo.setBuckets(buckets.stream().map(this::toBucketVO).toList());
        return vo;
    }

    @Override
    public PageResult<BucketVO> pageBuckets(BucketQueryDTO query) {
        Long shopId = shopIdOrNull();
        LambdaQueryWrapper<InvBucket> wrapper = new LambdaQueryWrapper<>();
        if (shopId != null) {
            wrapper.eq(InvBucket::getShopId, shopId);
        }
        if (query.getProductId() != null) {
            wrapper.eq(InvBucket::getProductId, query.getProductId());
        }
        if (StringUtils.hasText(query.getDimension()) && !"all".equalsIgnoreCase(query.getDimension())) {
            wrapper.eq(InvBucket::getDimension, parseDimension(query.getDimension()).name());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like(InvBucket::getDimensionValue, query.getKeyword());
        }
        if (Boolean.TRUE.equals(query.getOnlyPositive())) {
            wrapper.gt(InvBucket::getStock, 0);
        }
        if (query.getStatus() != null) {
            wrapper.eq(InvBucket::getStatus, query.getStatus());
        }
        wrapper.orderByAsc(InvBucket::getProductId, InvBucket::getDimension, InvBucket::getDimensionValue);
        Page<InvBucket> page = bucketMapper.selectPage(new Page<>(query.getPage(), query.getSize()), wrapper);
        return PageResult.of(page, this::toBucketVO);
    }

    /* ============================== 调拨 / 合并 ============================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String transfer(BucketTransferDTO dto) {
        Long shopId = MerchantSecurityUtils.getShopId();
        InvBucket from = requireBucket(dto.getFromBucketId(), shopId);
        InvBucket to = requireBucket(dto.getToBucketId(), shopId);
        if (!from.getProductId().equals(to.getProductId())) {
            throw new BizException(ResultCode.PARAM_ERROR, "来源桶与目标桶必须属于同一商品");
        }
        int qty = dto.getQty();
        if (bucketMapper.deductStock(from.getId(), qty) == 0) {
            throw new BizException(ResultCode.OUT_OF_STOCK, "来源桶余量不足");
        }
        bucketMapper.addStock(to.getId(), qty);

        String opNo = newOpNo("TRF");
        int fromAfter = stockOf(from.getId());
        log(from.getId(), from.getProductId(), shopId, BucketBizType.TRANSFER_OUT, -qty,
                fromAfter + qty, fromAfter, opNo, opNo, from.getDimensionValue(), dto.getOperator(), dto.getRemark());
        int toAfter = stockOf(to.getId());
        log(to.getId(), to.getProductId(), shopId, BucketBizType.TRANSFER_IN, qty,
                toAfter - qty, toAfter, opNo, opNo, to.getDimensionValue(), dto.getOperator(), dto.getRemark());

        saveOperation(BucketOpType.TRANSFER, from.getProductId(), shopId,
                from.getId(), to.getId(), qty, dto.getOperator(), dto.getRemark(), opNo);
        return opNo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String merge(BucketMergeDTO dto) {
        Long shopId = MerchantSecurityUtils.getShopId();
        Product product = requireProduct(dto.getProductId(), shopId);
        List<InvBucket> sources = new ArrayList<>();
        for (Long id : dto.getSourceBucketIds()) {
            InvBucket bucket = requireBucket(id, shopId);
            if (!bucket.getProductId().equals(dto.getProductId())) {
                throw new BizException(ResultCode.PARAM_ERROR, "来源桶与目标商品不一致");
            }
            sources.add(bucket);
        }
        InvBucket first = sources.get(0);
        String dimension = first.getDimension();
        String targetValue = StringUtils.hasText(dto.getTargetDimensionValue())
                ? dto.getTargetDimensionValue() : first.getDimensionValue();

        InvBucket target = dto.getTargetBucketId() != null
                ? requireBucket(dto.getTargetBucketId(), shopId)
                : findOrCreateBucket(product.getId(), shopId, dimension, targetValue, first);

        String opNo = newOpNo("MRG");
        int moved = 0;
        for (InvBucket source : sources) {
            if (source.getId().equals(target.getId())) {
                continue;
            }
            int amount = moveAll(source.getId());
            if (amount <= 0) {
                continue;
            }
            bucketMapper.addStockAndTotal(target.getId(), amount);
            moved += amount;
            log(source.getId(), product.getId(), shopId, BucketBizType.MERGE_OUT, -amount,
                    amount, 0, opNo, opNo, source.getDimensionValue(), dto.getOperator(), dto.getRemark());
            int after = stockOf(target.getId());
            log(target.getId(), product.getId(), shopId, BucketBizType.MERGE_IN, amount,
                    after - amount, after, opNo, opNo, target.getDimensionValue(), dto.getOperator(), dto.getRemark());
        }
        saveOperation(BucketOpType.MERGE, product.getId(), shopId,
                null, target.getId(), moved, dto.getOperator(), dto.getRemark(), opNo);
        return opNo;
    }

    /* ============================== 出库 / 回滚 ============================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BucketDeductResultVO deduct(BucketDeductDTO dto) {
        Long shopId = shopIdOrNull();
        Product product = requireProduct(dto.getProductId(), shopId);
        // 系统调用（交易域）没有商家上下文，审计流水仍应落在商品所属店铺，否则商家端查不到
        Long effectiveShopId = shopId != null ? shopId : product.getShopId();
        int qty = dto.getQty();

        // 幂等：同一单号 + 同一商品重复调用，直接返回既有结果，不重复扣减
        if (StringUtils.hasText(dto.getOrderNo()) && logMapper.countByOrderProduct(
                BucketBizType.OUTBOUND.name(), dto.getOrderNo(), dto.getProductId()) > 0) {
            return resultFromLogs(dto.getOrderNo(), dto.getProductId());
        }

        long bucketCount = bucketMapper.selectCount(new LambdaQueryWrapper<InvBucket>()
                .eq(InvBucket::getProductId, dto.getProductId()));

        // 交互边界：商品从未分桶时退化为「仅商品级扣减」，保持与旧链路兼容
        if (bucketCount == 0) {
            if (productMapper.deductStock(dto.getProductId(), qty) == 0) {
                throw new BizException(ResultCode.OUT_OF_STOCK);
            }
            BucketDeductResultVO result = new BucketDeductResultVO();
            result.setOrderNo(dto.getOrderNo());
            result.setProductId(dto.getProductId());
            result.setRequestedQty(qty);
            result.setDeductedQty(qty);
            result.setDetails(List.of());
            return result;
        }

        DeductPolicy policy = resolvePolicy(dto.getProductId(), effectiveShopId, dto.getPolicy());

        // 按优先级逐桶扣减：累加每个桶的扣减量（同一桶多轮命中时合并记账）
        Map<Long, BucketDeductResultVO.Detail> hit = new LinkedHashMap<>();
        int remaining = qty;
        for (int round = 0; round < MAX_DEDUCT_ROUNDS && remaining > 0; round++) {
            List<InvBucket> candidates = bucketMapper.selectDeductCandidates(dto.getProductId());
            if (candidates.isEmpty()) {
                break;
            }
            sortCandidates(candidates, policy);
            boolean progressed = false;
            for (InvBucket candidate : candidates) {
                if (remaining <= 0) {
                    break;
                }
                int take = Math.min(remaining, candidate.getStock() == null ? 0 : candidate.getStock());
                if (take <= 0) {
                    continue;
                }
                if (bucketMapper.deductStock(candidate.getId(), take) == 1) {
                    remaining -= take;
                    progressed = true;
                    accumulate(hit, candidate, take);
                }
            }
            if (!progressed) {
                break;
            }
        }
        if (remaining > 0) {
            // 桶余量不足：整个事务回滚，桶上的扣减一并撤销
            throw new BizException(ResultCode.OUT_OF_STOCK);
        }

        // 商品总库存同步扣减（保持 SUM(桶) == 商品总库存）
        if (productMapper.deductStock(dto.getProductId(), qty) == 0) {
            throw new BizException(ResultCode.OUT_OF_STOCK);
        }

        // 写审计流水（幂等键 = 单号 + 商品）
        for (BucketDeductResultVO.Detail detail : hit.values()) {
            int after = detail.getStockAfter();
            log(detail.getBucketId(), dto.getProductId(), effectiveShopId, BucketBizType.OUTBOUND, -detail.getQty(),
                    after + detail.getQty(), after, dto.getOrderNo(), dto.getOrderNo(), detail.getDimensionValue(),
                    dto.getOperator(), dto.getRemark());
        }

        BucketDeductResultVO result = new BucketDeductResultVO();
        result.setOrderNo(dto.getOrderNo());
        result.setProductId(dto.getProductId());
        result.setRequestedQty(qty);
        result.setDeductedQty(qty);
        result.setDetails(new ArrayList<>(hit.values()));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BucketDeductResultVO rollback(String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            throw new BizException(ResultCode.PARAM_ERROR, "单号不能为空");
        }
        List<InvBucketLog> outbound = logMapper.selectOutboundByOrder(orderNo);
        if (outbound.isEmpty()) {
            return resultFromLogs(orderNo, null);
        }
        // 一张订单可能跨多个商品：逐个商品回滚，各商品的幂等互相独立
        Map<Long, Long> products = new LinkedHashMap<>();
        for (InvBucketLog item : outbound) {
            products.putIfAbsent(item.getProductId(), item.getShopId());
        }
        List<BucketDeductResultVO.Detail> details = new ArrayList<>();
        for (Long productId : products.keySet()) {
            details.addAll(doRollbackProduct(orderNo, productId));
        }
        int total = details.stream().mapToInt(BucketDeductResultVO.Detail::getQty).sum();

        BucketDeductResultVO result = new BucketDeductResultVO();
        result.setOrderNo(orderNo);
        result.setProductId(products.keySet().stream().findFirst().orElse(null));
        result.setRequestedQty(total);
        result.setDeductedQty(total);
        result.setDetails(details);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int rollbackProduct(String orderNo, Long productId) {
        if (!StringUtils.hasText(orderNo) || productId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "单号与商品 ID 不能为空");
        }
        return doRollbackProduct(orderNo, productId).stream()
                .mapToInt(BucketDeductResultVO.Detail::getQty)
                .sum();
    }

    @Override
    public boolean isBucketed(Long productId) {
        if (productId == null) {
            return false;
        }
        return bucketMapper.selectCount(new LambdaQueryWrapper<InvBucket>()
                .eq(InvBucket::getProductId, productId)) > 0;
    }

    @Override
    public boolean hasOutbound(String orderNo, Long productId) {
        if (!StringUtils.hasText(orderNo) || productId == null) {
            return false;
        }
        return logMapper.countByOrderProduct(BucketBizType.OUTBOUND.name(), orderNo, productId) > 0;
    }

    /**
     * 把某单号在某商品上的出库量还回**原来那些桶**，并回补商品总库存。
     *
     * <p>幂等：已存在该 (单号, 商品) 的 ROLLBACK 流水时直接跳过，
     * 因此交易域的状态机重试、任务重跑都不会重复回补。</p>
     */
    private List<BucketDeductResultVO.Detail> doRollbackProduct(String orderNo, Long productId) {
        List<InvBucketLog> outbound = logMapper.selectOutboundByOrderProduct(orderNo, productId);
        if (outbound.isEmpty() || logMapper.countByOrderProduct(
                BucketBizType.ROLLBACK.name(), orderNo, productId) > 0) {
            return List.of();
        }
        Long shopId = outbound.get(0).getShopId();
        int total = 0;
        List<BucketDeductResultVO.Detail> details = new ArrayList<>();
        for (InvBucketLog item : outbound) {
            int qty = Math.abs(item.getChangeQty());
            bucketMapper.addStock(item.getBucketId(), qty);
            total += qty;
            int after = stockOf(item.getBucketId());
            log(item.getBucketId(), productId, shopId, BucketBizType.ROLLBACK, qty,
                    after - qty, after, orderNo, orderNo, item.getBucketKey(), "system", "出库回滚");
            BucketDeductResultVO.Detail detail = new BucketDeductResultVO.Detail();
            detail.setBucketId(item.getBucketId());
            detail.setDimensionValue(item.getBucketKey());
            detail.setQty(qty);
            detail.setStockAfter(after);
            details.add(detail);
        }
        if (total > 0) {
            productMapper.restoreStock(productId, total);
        }
        return details;
    }

    /* ============================== 报告 / 审计 ============================== */

    @Override
    public BucketReportVO report(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BizException(ResultCode.NOT_FOUND, "商品不存在");
        }
        int productStock = product.getStock() == null ? 0 : product.getStock();
        int bucketTotal = bucketMapper.sumStockByProduct(productId);
        List<Map<String, Object>> grouped = bucketMapper.sumGroupByDimension(productId);

        BucketReportVO vo = new BucketReportVO();
        vo.setProductId(productId);
        vo.setProductStock(productStock);
        vo.setBucketStockTotal(bucketTotal);
        vo.setDelta(productStock - bucketTotal);
        vo.setConsistent(bucketTotal == productStock);
        vo.setGeneratedAt(LocalDateTime.now());
        List<BucketReportVO.DimensionStat> stats = new ArrayList<>();
        for (Map<String, Object> row : grouped) {
            BucketReportVO.DimensionStat stat = new BucketReportVO.DimensionStat();
            stat.setDimension(String.valueOf(row.get("dimension")));
            stat.setStock(toInt(row.get("stock")));
            stat.setBucketCount(toInt(row.get("bucketCount")));
            stats.add(stat);
        }
        vo.setDimensionStats(stats);
        vo.setBucketCount(stats.stream().mapToInt(BucketReportVO.DimensionStat::getBucketCount).sum());
        return vo;
    }

    @Override
    public PageResult<BucketLogVO> pageLogs(BucketLogQueryDTO query) {
        Long shopId = shopIdOrNull();
        LambdaQueryWrapper<InvBucketLog> wrapper = new LambdaQueryWrapper<>();
        if (shopId != null) {
            wrapper.eq(InvBucketLog::getShopId, shopId);
        }
        if (query.getProductId() != null) {
            wrapper.eq(InvBucketLog::getProductId, query.getProductId());
        }
        if (query.getBucketId() != null) {
            wrapper.eq(InvBucketLog::getBucketId, query.getBucketId());
        }
        if (StringUtils.hasText(query.getBizType()) && !"all".equalsIgnoreCase(query.getBizType())) {
            wrapper.eq(InvBucketLog::getBizType, query.getBizType().trim().toUpperCase());
        }
        if (StringUtils.hasText(query.getOrderNo())) {
            wrapper.eq(InvBucketLog::getOrderNo, query.getOrderNo());
        }
        wrapper.orderByDesc(InvBucketLog::getCreateTime, InvBucketLog::getId);
        Page<InvBucketLog> page = logMapper.selectPage(new Page<>(query.getPage(), query.getSize()), wrapper);
        return PageResult.of(page, this::toLogVO);
    }

    @Override
    public PageResult<BucketOperationVO> pageOperations(BucketQueryDTO query) {
        Long shopId = shopIdOrNull();
        LambdaQueryWrapper<InvBucketOperation> wrapper = new LambdaQueryWrapper<>();
        if (shopId != null) {
            wrapper.eq(InvBucketOperation::getShopId, shopId);
        }
        if (query.getProductId() != null) {
            wrapper.eq(InvBucketOperation::getProductId, query.getProductId());
        }
        wrapper.orderByDesc(InvBucketOperation::getCreateTime, InvBucketOperation::getId);
        Page<InvBucketOperation> page = operationMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()), wrapper);
        return PageResult.of(page, this::toOperationVO);
    }

    /* ============================== 内部工具 ============================== */

    /** 把明细按维度值合并，避免同一桶重复分配导致守恒被破坏。 */
    private Map<String, BucketAllocateDTO.Item> mergeItems(List<BucketAllocateDTO.Item> items) {
        Map<String, BucketAllocateDTO.Item> merged = new LinkedHashMap<>();
        for (BucketAllocateDTO.Item item : items) {
            String key = item.getDimensionValue().trim();
            BucketAllocateDTO.Item exist = merged.get(key);
            if (exist == null) {
                item.setDimensionValue(key);
                item.setQty(item.getQty() == null ? 0 : item.getQty());
                merged.put(key, item);
            } else {
                exist.setQty(exist.getQty() + (item.getQty() == null ? 0 : item.getQty()));
                if (item.getPriority() != null) {
                    exist.setPriority(item.getPriority());
                }
                if (item.getExpireDate() != null) {
                    exist.setExpireDate(item.getExpireDate());
                }
            }
        }
        return merged;
    }

    /** 写入 / 更新一个桶到指定数量（分配语义：stock 与 total 都置为 qty）。 */
    private void upsertBucket(Long productId, Long shopId, Long ruleId, BucketDimension dimension,
                              BucketAllocateDTO.Item item, int qty, String operator) {
        InvBucket exist = findBucket(productId, dimension.name(), item.getDimensionValue());
        if (exist == null) {
            InvBucket bucket = new InvBucket();
            bucket.setProductId(productId);
            bucket.setShopId(shopId);
            bucket.setRuleId(ruleId);
            bucket.setDimension(dimension.name());
            bucket.setDimensionValue(item.getDimensionValue());
            bucket.setWarehouse(item.getWarehouse());
            bucket.setBatchNo(item.getBatchNo());
            bucket.setExpireDate(item.getExpireDate());
            bucket.setRegion(item.getRegion());
            bucket.setStock(qty);
            bucket.setTotal(qty);
            bucket.setPriority(item.getPriority() == null ? 0 : item.getPriority());
            bucket.setStatus(1);
            bucket.setLastSyncTime(LocalDateTime.now());
            bucketMapper.insert(bucket);
            log(bucket.getId(), productId, shopId, BucketBizType.ALLOCATE, qty, 0, qty,
                    null, null, item.getDimensionValue(), operator, "分配入桶");
            return;
        }
        int before = exist.getStock() == null ? 0 : exist.getStock();
        InvBucket update = new InvBucket();
        update.setId(exist.getId());
        update.setRuleId(ruleId);
        update.setWarehouse(item.getWarehouse());
        update.setBatchNo(item.getBatchNo());
        update.setExpireDate(item.getExpireDate());
        update.setRegion(item.getRegion());
        update.setStock(qty);
        update.setTotal(qty);
        update.setPriority(item.getPriority() == null ? 0 : item.getPriority());
        update.setLastSyncTime(LocalDateTime.now());
        bucketMapper.updateById(update);
        if (qty != before) {
            log(exist.getId(), productId, shopId, BucketBizType.ALLOCATE, qty - before, before, qty,
                    null, null, item.getDimensionValue(), operator, "分配调整");
        }
    }

    /** 绝对数量写入（不清 total），用于分配前清零。 */
    private void writeStock(InvBucket bucket, int stock) {
        InvBucket update = new InvBucket();
        update.setId(bucket.getId());
        update.setStock(stock);
        update.setLastSyncTime(LocalDateTime.now());
        bucketMapper.updateById(update);
    }

    /** 把某个桶的余量一次性全部挪走（CAS），返回实际挪动量。 */
    private int moveAll(Long bucketId) {
        for (int i = 0; i < 3; i++) {
            int current = stockOf(bucketId);
            if (current <= 0) {
                return 0;
            }
            if (bucketMapper.deductStock(bucketId, current) == 1) {
                return current;
            }
        }
        return 0;
    }

    private InvBucket findOrCreateBucket(Long productId, Long shopId, String dimension, String value, InvBucket template) {
        InvBucket exist = findBucket(productId, dimension, value);
        if (exist != null) {
            return exist;
        }
        InvBucket bucket = new InvBucket();
        bucket.setProductId(productId);
        bucket.setShopId(shopId);
        bucket.setDimension(dimension);
        bucket.setDimensionValue(value);
        bucket.setWarehouse(template.getWarehouse());
        bucket.setBatchNo(template.getBatchNo());
        bucket.setExpireDate(template.getExpireDate());
        bucket.setRegion(template.getRegion());
        bucket.setStock(0);
        bucket.setTotal(0);
        bucket.setPriority(template.getPriority() == null ? 0 : template.getPriority());
        bucket.setStatus(1);
        bucket.setLastSyncTime(LocalDateTime.now());
        bucketMapper.insert(bucket);
        return bucket;
    }

    private InvBucket findBucket(Long productId, String dimension, String dimensionValue) {
        return bucketMapper.selectOne(new LambdaQueryWrapper<InvBucket>()
                .eq(InvBucket::getProductId, productId)
                .eq(InvBucket::getDimension, dimension)
                .eq(InvBucket::getDimensionValue, dimensionValue)
                .last("limit 1"));
    }

    private List<InvBucket> listBuckets(Long productId) {
        return bucketMapper.selectList(new LambdaQueryWrapper<InvBucket>()
                .eq(InvBucket::getProductId, productId)
                .orderByAsc(InvBucket::getDimension, InvBucket::getDimensionValue));
    }

    private int stockOf(Long bucketId) {
        InvBucket bucket = bucketMapper.selectById(bucketId);
        return bucket == null || bucket.getStock() == null ? 0 : bucket.getStock();
    }

    private void sortCandidates(List<InvBucket> candidates, DeductPolicy policy) {
        Comparator<InvBucket> comparator = switch (policy) {
            case EXPIRY_FIRST -> Comparator
                    .comparing((InvBucket b) -> b.getExpireDate(), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(InvBucket::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(InvBucket::getId);
            case MANUAL -> Comparator
                    .comparingInt((InvBucket b) -> b.getPriority() == null ? 0 : b.getPriority())
                    .thenComparing(InvBucket::getId);
            case FIFO -> Comparator
                    .comparing(InvBucket::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(InvBucket::getId);
        };
        candidates.sort(comparator);
    }

    private void accumulate(Map<Long, BucketDeductResultVO.Detail> hit, InvBucket bucket, int qty) {
        BucketDeductResultVO.Detail detail = hit.get(bucket.getId());
        if (detail == null) {
            detail = new BucketDeductResultVO.Detail();
            detail.setBucketId(bucket.getId());
            detail.setDimension(bucket.getDimension());
            detail.setDimensionValue(bucket.getDimensionValue());
            detail.setQty(qty);
            detail.setStockAfter(stockOf(bucket.getId()));
            hit.put(bucket.getId(), detail);
        } else {
            detail.setQty(detail.getQty() + qty);
            detail.setStockAfter(stockOf(bucket.getId()));
        }
    }

    private DeductPolicy resolvePolicy(Long productId, Long shopId, String explicit) {
        if (StringUtils.hasText(explicit)) {
            return parsePolicy(explicit);
        }
        InvBucketRule rule = ruleMapper.selectOne(new LambdaQueryWrapper<InvBucketRule>()
                .eq(InvBucketRule::getEnabled, 1)
                .eq(shopId != null, InvBucketRule::getShopId, shopId)
                .and(w -> w.eq(InvBucketRule::getProductId, productId).or().isNull(InvBucketRule::getProductId))
                .orderByDesc(InvBucketRule::getProductId)
                .last("limit 1"));
        return rule == null ? DeductPolicy.FIFO : parsePolicy(rule.getDeductPolicy());
    }

    /** 从既有出库流水还原结果；productId 为 null 时取该单号的全部商品。 */
    private BucketDeductResultVO resultFromLogs(String orderNo, Long productId) {
        BucketDeductResultVO result = new BucketDeductResultVO();
        result.setOrderNo(orderNo);
        result.setProductId(productId);
        if (!StringUtils.hasText(orderNo)) {
            result.setDeductedQty(0);
            result.setDetails(List.of());
            return result;
        }
        List<InvBucketLog> outbound = productId == null
                ? logMapper.selectOutboundByOrder(orderNo)
                : logMapper.selectOutboundByOrderProduct(orderNo, productId);
        List<BucketDeductResultVO.Detail> details = new ArrayList<>();
        int total = 0;
        for (InvBucketLog item : outbound) {
            BucketDeductResultVO.Detail detail = new BucketDeductResultVO.Detail();
            detail.setBucketId(item.getBucketId());
            detail.setDimensionValue(item.getBucketKey());
            detail.setQty(Math.abs(item.getChangeQty()));
            detail.setStockAfter(item.getAfterStock());
            details.add(detail);
            total += Math.abs(item.getChangeQty());
        }
        if (productId == null && !outbound.isEmpty()) {
            result.setProductId(outbound.get(0).getProductId());
        }
        result.setRequestedQty(total);
        result.setDeductedQty(total);
        result.setDetails(details);
        return result;
    }

    private void saveOperation(BucketOpType type, Long productId, Long shopId, Long fromId,
                               Long toId, int qty, String operator, String remark, String opNo) {
        InvBucketOperation operation = new InvBucketOperation();
        operation.setOpNo(opNo);
        operation.setOpType(type.name());
        operation.setProductId(productId);
        operation.setShopId(shopId);
        operation.setFromBucketId(fromId);
        operation.setToBucketId(toId);
        operation.setQty(qty);
        operation.setStatus(1);
        operation.setOperator(operator);
        operation.setRemark(remark);
        operationMapper.insert(operation);
    }

    private void log(Long bucketId, Long productId, Long shopId, BucketBizType type, int changeQty,
                     int beforeStock, int afterStock, String orderNo, String bizId, String bucketKey,
                     String operator, String remark) {
        InvBucketLog entity = new InvBucketLog();
        entity.setBucketId(bucketId);
        entity.setProductId(productId);
        entity.setShopId(shopId == null ? 0L : shopId);
        entity.setBizType(type.name());
        entity.setChangeQty(changeQty);
        entity.setBeforeStock(beforeStock);
        entity.setAfterStock(afterStock);
        entity.setOrderNo(orderNo);
        entity.setBizId(bizId);
        entity.setBucketKey(bucketKey);
        entity.setOperator(operator);
        entity.setRemark(remark);
        logMapper.insert(entity);
    }

    private Product requireProduct(Long productId, Long shopId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BizException(ResultCode.NOT_FOUND, "商品不存在");
        }
        if (shopId != null && !shopId.equals(product.getShopId())) {
            throw new BizException(ResultCode.FORBIDDEN, "无权操作该商品库存");
        }
        return product;
    }

    private InvBucketRule requireRule(Long ruleId, Long shopId) {
        InvBucketRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BizException(ResultCode.NOT_FOUND, "分桶规则不存在");
        }
        if (!shopId.equals(rule.getShopId())) {
            throw new BizException(ResultCode.FORBIDDEN, "无权操作该规则");
        }
        return rule;
    }

    private InvBucket requireBucket(Long bucketId, Long shopId) {
        InvBucket bucket = bucketMapper.selectById(bucketId);
        if (bucket == null) {
            throw new BizException(ResultCode.NOT_FOUND, "库存桶不存在");
        }
        if (shopId != null && !shopId.equals(bucket.getShopId())) {
            throw new BizException(ResultCode.FORBIDDEN, "无权操作该库存桶");
        }
        return bucket;
    }

    private Long shopIdOrNull() {
        try {
            return MerchantSecurityUtils.getShopId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private BucketDimension parseDimension(String name) {
        try {
            return BucketDimension.of(name);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_ERROR, e.getMessage());
        }
    }

    private DeductPolicy parsePolicy(String name) {
        try {
            return DeductPolicy.of(name);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_ERROR, e.getMessage());
        }
    }

    private String newOpNo(String prefix) {
        int suffix = ThreadLocalRandom.current().nextInt(1000, 9999);
        return prefix + LocalDateTime.now().format(OP_NO_FMT) + suffix;
    }

    private int toInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private BucketVO toBucketVO(InvBucket bucket) {
        BucketVO vo = new BucketVO();
        vo.setId(bucket.getId());
        vo.setProductId(bucket.getProductId());
        vo.setRuleId(bucket.getRuleId());
        vo.setDimension(bucket.getDimension());
        vo.setDimensionValue(bucket.getDimensionValue());
        vo.setWarehouse(bucket.getWarehouse());
        vo.setBatchNo(bucket.getBatchNo());
        vo.setExpireDate(bucket.getExpireDate());
        vo.setRegion(bucket.getRegion());
        vo.setStock(bucket.getStock());
        vo.setTotal(bucket.getTotal());
        vo.setPriority(bucket.getPriority());
        vo.setStatus(bucket.getStatus());
        vo.setLastSyncTime(bucket.getLastSyncTime());
        vo.setCreateTime(bucket.getCreateTime());
        return vo;
    }

    private BucketRuleVO toRuleVO(InvBucketRule rule) {
        BucketRuleVO vo = new BucketRuleVO();
        vo.setId(rule.getId());
        vo.setRuleName(rule.getRuleName());
        vo.setDimension(rule.getDimension());
        vo.setGranularity(rule.getGranularity());
        vo.setDeductPolicy(rule.getDeductPolicy());
        vo.setProductId(rule.getProductId());
        vo.setEnabled(rule.getEnabled());
        vo.setRemark(rule.getRemark());
        vo.setCreateTime(rule.getCreateTime());
        vo.setUpdateTime(rule.getUpdateTime());
        return vo;
    }

    private BucketLogVO toLogVO(InvBucketLog entity) {
        BucketLogVO vo = new BucketLogVO();
        vo.setId(entity.getId());
        vo.setBucketId(entity.getBucketId());
        vo.setProductId(entity.getProductId());
        vo.setBizType(entity.getBizType());
        vo.setChangeQty(entity.getChangeQty());
        vo.setBeforeStock(entity.getBeforeStock());
        vo.setAfterStock(entity.getAfterStock());
        vo.setOrderNo(entity.getOrderNo());
        vo.setBucketKey(entity.getBucketKey());
        vo.setOperator(entity.getOperator());
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }

    private BucketOperationVO toOperationVO(InvBucketOperation operation) {
        BucketOperationVO vo = new BucketOperationVO();
        vo.setId(operation.getId());
        vo.setOpNo(operation.getOpNo());
        vo.setOpType(operation.getOpType());
        vo.setProductId(operation.getProductId());
        vo.setFromBucketId(operation.getFromBucketId());
        vo.setToBucketId(operation.getToBucketId());
        vo.setQty(operation.getQty());
        vo.setOperator(operation.getOperator());
        vo.setRemark(operation.getRemark());
        vo.setCreateTime(operation.getCreateTime());
        return vo;
    }
}
