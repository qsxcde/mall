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
import com.geekmall.modules.inventory.enums.BucketDimension;
import com.geekmall.modules.inventory.enums.DeductPolicy;
import com.geekmall.modules.inventory.mapper.InvBucketLogMapper;
import com.geekmall.modules.inventory.mapper.InvBucketMapper;
import com.geekmall.modules.inventory.mapper.InvBucketOperationMapper;
import com.geekmall.modules.inventory.mapper.InvBucketRuleMapper;
import com.geekmall.modules.inventory.service.InventoryBucketService;
import com.geekmall.modules.inventory.support.BucketAllocator;
import com.geekmall.modules.inventory.support.BucketStockBatchDeductor;
import com.geekmall.modules.inventory.support.BucketStockDeductor;
import com.geekmall.modules.inventory.support.BucketStockMover;
import com.geekmall.modules.inventory.support.BucketStore;
import com.geekmall.modules.inventory.vo.BucketBalanceVO;
import com.geekmall.modules.inventory.vo.BucketDeductResultVO;
import com.geekmall.modules.inventory.vo.BucketLogVO;
import com.geekmall.modules.inventory.vo.BucketOperationVO;
import com.geekmall.modules.inventory.vo.BucketReportVO;
import com.geekmall.modules.inventory.vo.BucketRuleVO;
import com.geekmall.modules.inventory.vo.BucketVO;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.entity.Sku;
import com.geekmall.modules.product.service.ProductStockService;
import com.geekmall.modules.product.service.SkuStockService;
import com.geekmall.security.MerchantSecurityUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 库存分桶服务实现（门面）。
 *
 * <p>设计要点（与压测文档附录 C/D 对齐）：</p>
 * <ol>
 *   <li><b>桶是并发单元</b>：余量变更全部走 {@code UPDATE ... WHERE stock >= qty} 的 CAS，
 *       不做「先查再改」，也不用乐观锁，避免读改写竞态；</li>
 *   <li><b>出库分流</b>：按优先级挑选有货的桶逐个扣减，「桶空了」才换下一个桶；</li>
 *   <li><b>守恒</b>：任何桶余量变化都写审计流水；出库同时扣减 {@code pms_product.stock}，
 *       调拨 / 合并 / 分配不改变商品总库存，保证 {@code SUM(桶) == 商品总库存}；</li>
 *   <li><b>幂等</b>：出库 / 回滚以 {@code order_no} 为幂等键（唯一索引 + 先查后写）。</li>
 * </ol>
 *
 * <p>实现按职责拆给 {@code support} 下的协作类（事务边界统一在本门面的方法上）：
 * {@link BucketAllocator} 分配布局、{@link BucketStockMover} 调拨合并、
 * {@link BucketStockDeductor} 出库回滚、{@link BucketStore} 读写原语与守卫；
 * 门面保留规则管理、查询报告与编排。</p>
 */
@Service
@RequiredArgsConstructor
public class InventoryBucketServiceImpl implements InventoryBucketService {

    private final InvBucketRuleMapper ruleMapper;
    private final InvBucketMapper bucketMapper;
    private final InvBucketOperationMapper operationMapper;
    private final InvBucketLogMapper logMapper;
    private final ProductStockService productStockService;
    private final SkuStockService skuStockService;
    private final BucketStore store;
    private final BucketAllocator allocator;
    private final BucketStockMover mover;
    private final BucketStockDeductor deductor;
    private final BucketStockBatchDeductor batchDeductor;

    /* ============================== 分桶规则 ============================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveRule(BucketRuleSaveDTO dto) {
        Long shopId = MerchantSecurityUtils.getShopId();
        BucketDimension dimension = store.parseDimension(dto.getDimension());
        DeductPolicy policy = store.parsePolicy(dto.getDeductPolicy());

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

        InvBucketRule exist = store.requireRule(dto.getId(), shopId);
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
        LambdaQueryWrapper<InvBucketRule> wrapper =
                new LambdaQueryWrapper<InvBucketRule>().eq(InvBucketRule::getShopId, shopId);
        if (StringUtils.hasText(query.getDimension())) {
            wrapper.eq(
                    InvBucketRule::getDimension,
                    store.parseDimension(query.getDimension()).name());
        }
        if (query.getEnabled() != null) {
            wrapper.eq(InvBucketRule::getEnabled, query.getEnabled());
        }
        if (query.getProductId() != null) {
            wrapper.and(w ->
                    w.eq(InvBucketRule::getProductId, query.getProductId()).or().isNull(InvBucketRule::getProductId));
        }
        wrapper.orderByDesc(InvBucketRule::getId);
        return ruleMapper.selectList(wrapper).stream().map(store::toRuleVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateRuleStatus(Long ruleId, Integer enabled) {
        Long shopId = MerchantSecurityUtils.getShopId();
        InvBucketRule rule = store.requireRule(ruleId, shopId);
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
        Product product = store.requireProduct(dto.getProductId(), shopId);
        InvBucketRule rule = dto.getRuleId() == null ? null : store.requireRule(dto.getRuleId(), shopId);
        BucketDimension dimension =
                rule != null ? BucketDimension.of(rule.getDimension()) : store.parseDimension(dto.getDimension());

        Long skuId = store.resolveSkuId(dto.getProductId(), dto.getSkuId());
        int skuStock = skuStockOf(skuId, product);
        Map<String, BucketAllocateDTO.Item> merged = allocator.mergeItems(dto.getItems());
        allocator.relayout(
                product.getId(), skuId, shopId, dto.getRuleId(), dimension, merged, skuStock, dto.getOperator());
        return balance(product.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BucketBalanceVO autoSplit(Long productId, Long ruleId, List<String> dimensionValues) {
        Long shopId = MerchantSecurityUtils.getShopId();
        Product product = store.requireProduct(productId, shopId);
        store.requireRule(ruleId, shopId);
        if (dimensionValues == null || dimensionValues.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "维度值列表不能为空");
        }
        Long skuId = store.resolveSkuId(productId, null);
        int total = skuStockOf(skuId, product);
        int n = dimensionValues.size();
        int base = total / n;
        int remainder = total % n;

        BucketAllocateDTO dto = new BucketAllocateDTO();
        dto.setProductId(productId);
        dto.setSkuId(skuId);
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
        Product product = productStockService.findById(productId);
        if (product == null) {
            throw new BizException(ResultCode.NOT_FOUND, "商品不存在");
        }
        List<InvBucket> buckets = store.listBuckets(productId);
        int bucketTotal = buckets.stream()
                .mapToInt(b -> b.getStock() == null ? 0 : b.getStock())
                .sum();
        int productStock = product.getStock() == null ? 0 : product.getStock();

        BucketBalanceVO vo = new BucketBalanceVO();
        vo.setProductId(productId);
        vo.setProductStock(productStock);
        vo.setBucketStockTotal(bucketTotal);
        vo.setDelta(productStock - bucketTotal);
        vo.setConsistent(bucketTotal == productStock);
        vo.setBucketCount(buckets.size());
        vo.setBuckets(buckets.stream().map(store::toBucketVO).toList());
        return vo;
    }

    @Override
    public PageResult<BucketVO> pageBuckets(BucketQueryDTO query) {
        Long shopId = store.shopIdOrNull();
        LambdaQueryWrapper<InvBucket> wrapper = new LambdaQueryWrapper<>();
        if (shopId != null) {
            wrapper.eq(InvBucket::getShopId, shopId);
        }
        if (query.getProductId() != null) {
            wrapper.eq(InvBucket::getProductId, query.getProductId());
        }
        if (StringUtils.hasText(query.getDimension()) && !"all".equalsIgnoreCase(query.getDimension())) {
            wrapper.eq(
                    InvBucket::getDimension,
                    store.parseDimension(query.getDimension()).name());
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
        return PageResult.of(page, store::toBucketVO);
    }

    /* ============================== 调拨 / 合并 / 出库 / 回滚（委托执行器） ============================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String transfer(BucketTransferDTO dto) {
        return mover.transfer(dto);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String merge(BucketMergeDTO dto) {
        return mover.merge(dto);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BucketDeductResultVO deduct(BucketDeductDTO dto) {
        return deductor.deduct(dto);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deductBatch(List<BucketDeductDTO> deductions) {
        batchDeductor.deductBatch(deductions);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BucketDeductResultVO rollback(String orderNo) {
        return deductor.rollback(orderNo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int rollbackSku(String orderNo, Long skuId, Long productId) {
        return deductor.rollbackSku(orderNo, skuId, productId);
    }

    @Override
    public boolean isBucketed(Long productId, Long skuId) {
        return deductor.isBucketed(productId, skuId);
    }

    @Override
    public boolean hasOutbound(String orderNo, Long skuId, Long productId) {
        return deductor.hasOutbound(orderNo, skuId, productId);
    }

    /** 分配基数：{@code sku_id > 0} 取 SKU 库存，{@code sku_id = 0} 的历史商品取商品总库存。 */
    private int skuStockOf(Long skuId, Product product) {
        if (skuId == null || skuId <= 0) {
            return product.getStock() == null ? 0 : product.getStock();
        }
        Sku sku = skuStockService.findByIds(List.of(skuId)).get(skuId);
        return sku == null || sku.getStock() == null ? 0 : sku.getStock();
    }

    /* ============================== 报告 / 审计 ============================== */

    @Override
    public BucketReportVO report(Long productId) {
        Product product = productStockService.findById(productId);
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
            stat.setStock(BucketStore.toInt(row.get("stock")));
            stat.setBucketCount(BucketStore.toInt(row.get("bucketCount")));
            stats.add(stat);
        }
        vo.setDimensionStats(stats);
        vo.setBucketCount(stats.stream()
                .mapToInt(BucketReportVO.DimensionStat::getBucketCount)
                .sum());
        return vo;
    }

    @Override
    public PageResult<BucketLogVO> pageLogs(BucketLogQueryDTO query) {
        Long shopId = store.shopIdOrNull();
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
        return PageResult.of(page, store::toLogVO);
    }

    @Override
    public PageResult<BucketOperationVO> pageOperations(BucketQueryDTO query) {
        Long shopId = store.shopIdOrNull();
        LambdaQueryWrapper<InvBucketOperation> wrapper = new LambdaQueryWrapper<>();
        if (shopId != null) {
            wrapper.eq(InvBucketOperation::getShopId, shopId);
        }
        if (query.getProductId() != null) {
            wrapper.eq(InvBucketOperation::getProductId, query.getProductId());
        }
        wrapper.orderByDesc(InvBucketOperation::getCreateTime, InvBucketOperation::getId);
        Page<InvBucketOperation> page =
                operationMapper.selectPage(new Page<>(query.getPage(), query.getSize()), wrapper);
        return PageResult.of(page, store::toOperationVO);
    }
}
