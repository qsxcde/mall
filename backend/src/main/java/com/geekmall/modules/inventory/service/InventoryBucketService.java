package com.geekmall.modules.inventory.service;

import com.geekmall.common.result.PageResult;
import com.geekmall.modules.inventory.dto.BucketAllocateDTO;
import com.geekmall.modules.inventory.dto.BucketDeductDTO;
import com.geekmall.modules.inventory.dto.BucketLogQueryDTO;
import com.geekmall.modules.inventory.dto.BucketMergeDTO;
import com.geekmall.modules.inventory.dto.BucketQueryDTO;
import com.geekmall.modules.inventory.dto.BucketRuleQueryDTO;
import com.geekmall.modules.inventory.dto.BucketRuleSaveDTO;
import com.geekmall.modules.inventory.dto.BucketTransferDTO;
import com.geekmall.modules.inventory.vo.BucketBalanceVO;
import com.geekmall.modules.inventory.vo.BucketDeductResultVO;
import com.geekmall.modules.inventory.vo.BucketLogVO;
import com.geekmall.modules.inventory.vo.BucketOperationVO;
import com.geekmall.modules.inventory.vo.BucketReportVO;
import com.geekmall.modules.inventory.vo.BucketRuleVO;
import com.geekmall.modules.inventory.vo.BucketVO;

import java.util.List;

/**
 * 库存分桶服务。
 *
 * <p><b>粒度：</b>一个桶 = {@code (商品 × 维度值)}；同一商品同一时刻只使用一种维度。</p>
 * <p><b>不变量：</b>{@code SUM(inv_bucket.stock) == pms_product.stock}。</p>
 * <p><b>交互边界：</b>{@code pms_product.stock} 是权威总量；本服务只在「分桶出库」时与之联动扣减，
 * 调拨 / 合并 / 分配都不改变它。</p>
 */
public interface InventoryBucketService {

    /* ------------------------------ 分桶规则 ------------------------------ */

    /** 新增或编辑分桶规则，返回规则 ID。 */
    Long saveRule(BucketRuleSaveDTO dto);

    /** 规则列表（按维度 / 启用状态 / 商品过滤）。 */
    List<BucketRuleVO> listRules(BucketRuleQueryDTO query);

    /** 启用 / 停用规则。 */
    int updateRuleStatus(Long ruleId, Integer enabled);

    /* ------------------------------ 库存分配 ------------------------------ */

    /** 把商品现有库存按明细分配到各桶（不改商品总库存，差额自动落入「未分配」桶）。 */
    BucketBalanceVO allocate(BucketAllocateDTO dto);

    /** 按规则把商品现有库存均分到给定维度值上。 */
    BucketBalanceVO autoSplit(Long productId, Long ruleId, List<String> dimensionValues);

    /* ------------------------------ 余量查询 ------------------------------ */

    /** 某商品的分桶余量全景 + 守恒对账。 */
    BucketBalanceVO balance(Long productId);

    /** 分页查询库存桶。 */
    PageResult<BucketVO> pageBuckets(BucketQueryDTO query);

    /* ------------------------------ 调拨 / 合并 ------------------------------ */

    /** 跨桶调拨，返回操作单号。 */
    String transfer(BucketTransferDTO dto);

    /** 跨桶合并，返回操作单号。 */
    String merge(BucketMergeDTO dto);

    /* ------------------------------ 出库 / 回滚 ------------------------------ */

    /** 按优先级从各桶出库，并同步扣减商品总库存（orderNo + productId 幂等）。 */
    BucketDeductResultVO deduct(BucketDeductDTO dto);

    /** 把某出库单的量还回**原来那一桶**（整单，跨商品；幂等）。 */
    BucketDeductResultVO rollback(String orderNo);

    /** 把某出库单在**单个商品**上的量还回原桶（供交易域逐商品回滚调用，幂等）。 */
    int rollbackProduct(String orderNo, Long productId);

    /** 商品是否已分桶（决定交易域走分桶出库还是旧的单行扣减）。 */
    boolean isBucketed(Long productId);

    /** 某单号 + 商品是否存在桶级出库记录（决定回滚时需要走哪条路径）。 */
    boolean hasOutbound(String orderNo, Long productId);

    /* ------------------------------ 报告 / 审计 ------------------------------ */

    /** 分桶报告：按维度汇总 + 与商品总库存对账。 */
    BucketReportVO report(Long productId);

    /** 审计流水分页。 */
    PageResult<BucketLogVO> pageLogs(BucketLogQueryDTO query);

    /** 调拨 / 合并单分页。 */
    PageResult<BucketOperationVO> pageOperations(BucketQueryDTO query);
}
