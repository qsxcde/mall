package com.geekmall.modules.inventory.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
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
import com.geekmall.modules.inventory.vo.BucketLogVO;
import com.geekmall.modules.inventory.vo.BucketOperationVO;
import com.geekmall.modules.inventory.vo.BucketRuleVO;
import com.geekmall.modules.inventory.vo.BucketVO;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.service.ProductStockService;
import com.geekmall.security.MerchantSecurityUtils;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 分桶域的读写原语与守卫（被分配 / 调拨 / 出库 / 查询各协作类共用的底座）。
 *
 * <p>从 {@code InventoryBucketServiceImpl} 拆出：这些方法被所有上层协作类复用，
 * 放在门面里会迫使协作类反向依赖门面形成循环。这里只放「怎么读写桶与流水、
 * 谁有权操作」这类无编排逻辑的原语，业务编排仍留在各执行器与门面中。</p>
 */
@Component
@RequiredArgsConstructor
public class BucketStore {

    /** 出库 CAS 冲突时的最大重试轮数（重新选桶）。 */
    static final int MAX_DEDUCT_ROUNDS = 5;

    /** 未参与到显式分配中的余量，统一落入该「未分配」桶，避免库存凭空消失。 */
    public static final String UNALLOCATED = "未分配";

    private static final DateTimeFormatter OP_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final InvBucketMapper bucketMapper;
    private final InvBucketLogMapper logMapper;
    private final InvBucketOperationMapper operationMapper;
    private final InvBucketRuleMapper ruleMapper;
    private final ProductStockService productStockService;

    /* ============================== 桶读写原语 ============================== */

    public InvBucket findBucket(Long productId, String dimension, String dimensionValue) {
        return bucketMapper.selectOne(new LambdaQueryWrapper<InvBucket>()
                .eq(InvBucket::getProductId, productId)
                .eq(InvBucket::getDimension, dimension)
                .eq(InvBucket::getDimensionValue, dimensionValue)
                .last("limit 1"));
    }

    public List<InvBucket> listBuckets(Long productId) {
        return bucketMapper.selectList(new LambdaQueryWrapper<InvBucket>()
                .eq(InvBucket::getProductId, productId)
                .orderByAsc(InvBucket::getDimension, InvBucket::getDimensionValue));
    }

    public int stockOf(Long bucketId) {
        InvBucket bucket = bucketMapper.selectById(bucketId);
        return bucket == null || bucket.getStock() == null ? 0 : bucket.getStock();
    }

    /** 绝对数量写入（不清 total），用于分配前清零。 */
    public void writeStock(InvBucket bucket, int stock) {
        InvBucket update = new InvBucket();
        update.setId(bucket.getId());
        update.setStock(stock);
        update.setLastSyncTime(LocalDateTime.now());
        bucketMapper.updateById(update);
    }

    /** 把某个桶的余量一次性全部挪走（CAS），返回实际挪动量。 */
    public int moveAll(Long bucketId) {
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

    /** 按 CAS 从桶里扣减，返回是否成功（桶是并发单元，失败即可重试）。 */
    public boolean deductStock(Long bucketId, int qty) {
        return bucketMapper.deductStock(bucketId, qty) == 1;
    }

    /** 给桶加回余量（出库回滚 / 调拨入库）。 */
    public void addStock(Long bucketId, int qty) {
        bucketMapper.addStock(bucketId, qty);
    }

    /** 合并入库：余量与总量一起增加（调拨/合并不改变商品总库存口径外的总量）。 */
    public void addStockAndTotal(Long bucketId, int qty) {
        bucketMapper.addStockAndTotal(bucketId, qty);
    }

    public InvBucket findOrCreateBucket(
            Long productId, Long shopId, String dimension, String value, InvBucket template) {
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

    /* ============================== 审计流水 / 操作单 ============================== */

    public void log(
            Long bucketId,
            Long productId,
            Long shopId,
            BucketBizType type,
            int changeQty,
            int beforeStock,
            int afterStock,
            String orderNo,
            String bizId,
            String bucketKey,
            String operator,
            String remark) {
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

    public void saveOperation(
            BucketOpType type,
            Long productId,
            Long shopId,
            Long fromId,
            Long toId,
            int qty,
            String operator,
            String remark,
            String opNo) {
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

    public String newOpNo(String prefix) {
        int suffix = ThreadLocalRandom.current().nextInt(1000, 9999);
        return prefix + LocalDateTime.now().format(OP_NO_FMT) + suffix;
    }

    /* ============================== 权限守卫 ============================== */

    public Product requireProduct(Long productId, Long shopId) {
        Product product = productStockService.findById(productId);
        if (product == null) {
            throw new BizException(ResultCode.NOT_FOUND, "商品不存在");
        }
        if (shopId != null && !shopId.equals(product.getShopId())) {
            throw new BizException(ResultCode.FORBIDDEN, "无权操作该商品库存");
        }
        return product;
    }

    public InvBucketRule requireRule(Long ruleId, Long shopId) {
        InvBucketRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BizException(ResultCode.NOT_FOUND, "分桶规则不存在");
        }
        if (!shopId.equals(rule.getShopId())) {
            throw new BizException(ResultCode.FORBIDDEN, "无权操作该规则");
        }
        return rule;
    }

    public InvBucket requireBucket(Long bucketId, Long shopId) {
        InvBucket bucket = bucketMapper.selectById(bucketId);
        if (bucket == null) {
            throw new BizException(ResultCode.NOT_FOUND, "库存桶不存在");
        }
        if (shopId != null && !shopId.equals(bucket.getShopId())) {
            throw new BizException(ResultCode.FORBIDDEN, "无权操作该库存桶");
        }
        return bucket;
    }

    /** 商家上下文可选：交易域系统调用没有商家身份，此时不过滤店铺。 */
    public Long shopIdOrNull() {
        try {
            return MerchantSecurityUtils.getShopId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /* ============================== 枚举解析 / 转换 ============================== */

    public BucketDimension parseDimension(String name) {
        try {
            return BucketDimension.of(name);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_ERROR, e.getMessage());
        }
    }

    public DeductPolicy parsePolicy(String name) {
        try {
            return DeductPolicy.of(name);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_ERROR, e.getMessage());
        }
    }

    public static int toInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    public BucketVO toBucketVO(InvBucket bucket) {
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

    public BucketRuleVO toRuleVO(InvBucketRule rule) {
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

    public BucketLogVO toLogVO(InvBucketLog entity) {
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

    public BucketOperationVO toOperationVO(InvBucketOperation operation) {
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
