package com.geekmall.modules.inventory.controller;

import com.geekmall.common.result.PageResult;
import com.geekmall.common.result.Result;
import com.geekmall.modules.inventory.dto.BucketAllocateDTO;
import com.geekmall.modules.inventory.dto.BucketDeductDTO;
import com.geekmall.modules.inventory.dto.BucketLogQueryDTO;
import com.geekmall.modules.inventory.dto.BucketMergeDTO;
import com.geekmall.modules.inventory.dto.BucketQueryDTO;
import com.geekmall.modules.inventory.dto.BucketRuleQueryDTO;
import com.geekmall.modules.inventory.dto.BucketRuleSaveDTO;
import com.geekmall.modules.inventory.dto.BucketTransferDTO;
import com.geekmall.modules.inventory.service.InventoryBucketService;
import com.geekmall.modules.inventory.vo.BucketBalanceVO;
import com.geekmall.modules.inventory.vo.BucketDeductResultVO;
import com.geekmall.modules.inventory.vo.BucketLogVO;
import com.geekmall.modules.inventory.vo.BucketOperationVO;
import com.geekmall.modules.inventory.vo.BucketReportVO;
import com.geekmall.modules.inventory.vo.BucketRuleVO;
import com.geekmall.modules.inventory.vo.BucketVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端库存分桶接口。
 *
 * <p>粒度、更新频率与交互边界见 {@code docs/} 下的分桶设计说明；核心不变量为
 * {@code SUM(inv_bucket.stock) == pms_product.stock}。</p>
 */
@Tag(name = "20-库存分桶", description = "分桶规则 / 库存分配 / 余量查询 / 调拨合并 / 出库 / 报告审计")
@RestController
@RequestMapping("/api/v1/merchant/inventory/bucket")
@RequiredArgsConstructor
public class InventoryBucketController {

    private final InventoryBucketService inventoryBucketService;

    /* ---------------- 分桶规则 ---------------- */

    @Operation(summary = "保存分桶规则")
    @PostMapping("/rule/save")
    public Result<Long> saveRule(@Valid @RequestBody BucketRuleSaveDTO dto) {
        return Result.ok(inventoryBucketService.saveRule(dto));
    }

    @Operation(summary = "分桶规则列表")
    @GetMapping("/rule/list")
    public Result<List<BucketRuleVO>> listRules(@Valid @ModelAttribute BucketRuleQueryDTO query) {
        return Result.ok(inventoryBucketService.listRules(query));
    }

    @Operation(summary = "启用/停用分桶规则")
    @PostMapping("/rule/status")
    public Result<Integer> updateRuleStatus(@RequestParam Long ruleId, @RequestParam Integer enabled) {
        return Result.ok(inventoryBucketService.updateRuleStatus(ruleId, enabled));
    }

    /* ---------------- 库存分配 ---------------- */

    @Operation(summary = "按明细把现有库存分配到各桶")
    @PostMapping("/allocate")
    public Result<BucketBalanceVO> allocate(@Valid @RequestBody BucketAllocateDTO dto) {
        return Result.ok(inventoryBucketService.allocate(dto));
    }

    @Operation(summary = "按规则把现有库存均分到多个维度值")
    @PostMapping("/auto-split")
    public Result<BucketBalanceVO> autoSplit(@RequestParam Long productId,
                                             @RequestParam Long ruleId,
                                             @RequestParam List<String> values) {
        return Result.ok(inventoryBucketService.autoSplit(productId, ruleId, values));
    }

    /* ---------------- 余量查询 ---------------- */

    @Operation(summary = "某商品分桶余量全景（含守恒对账）")
    @GetMapping("/balance")
    public Result<BucketBalanceVO> balance(@RequestParam Long productId) {
        return Result.ok(inventoryBucketService.balance(productId));
    }

    @Operation(summary = "库存桶分页")
    @GetMapping("/page")
    public Result<PageResult<BucketVO>> page(@Valid @ModelAttribute BucketQueryDTO query) {
        return Result.ok(inventoryBucketService.pageBuckets(query));
    }

    /* ---------------- 调拨 / 合并 ---------------- */

    @Operation(summary = "跨桶调拨")
    @PostMapping("/transfer")
    public Result<String> transfer(@Valid @RequestBody BucketTransferDTO dto) {
        return Result.ok(inventoryBucketService.transfer(dto));
    }

    @Operation(summary = "跨桶合并")
    @PostMapping("/merge")
    public Result<String> merge(@Valid @RequestBody BucketMergeDTO dto) {
        return Result.ok(inventoryBucketService.merge(dto));
    }

    /* ---------------- 出库 / 回滚 ---------------- */

    @Operation(summary = "按优先级从各桶出库")
    @PostMapping("/deduct")
    public Result<BucketDeductResultVO> deduct(@Valid @RequestBody BucketDeductDTO dto) {
        return Result.ok(inventoryBucketService.deduct(dto));
    }

    @Operation(summary = "按单号把出库量还回原桶")
    @PostMapping("/rollback")
    public Result<BucketDeductResultVO> rollback(@RequestParam String orderNo) {
        return Result.ok(inventoryBucketService.rollback(orderNo));
    }

    /* ---------------- 报告 / 审计 ---------------- */

    @Operation(summary = "分桶报告（按维度汇总 + 对账）")
    @GetMapping("/report")
    public Result<BucketReportVO> report(@RequestParam Long productId) {
        return Result.ok(inventoryBucketService.report(productId));
    }

    @Operation(summary = "审计流水分页")
    @GetMapping("/logs")
    public Result<PageResult<BucketLogVO>> logs(@Valid @ModelAttribute BucketLogQueryDTO query) {
        return Result.ok(inventoryBucketService.pageLogs(query));
    }

    @Operation(summary = "调拨/合并单分页")
    @GetMapping("/operations")
    public Result<PageResult<BucketOperationVO>> operations(@Valid @ModelAttribute BucketQueryDTO query) {
        return Result.ok(inventoryBucketService.pageOperations(query));
    }
}
