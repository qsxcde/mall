package com.geekmall.modules.aftersale.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.aftersale.dto.ApplyAfterSaleDTO;
import com.geekmall.modules.aftersale.service.AfterSaleService;
import com.geekmall.modules.aftersale.vo.AfterSaleVO;
import com.geekmall.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 售后接口，对应前端 aftersale/ApplyView、aftersale/DetailView 与个人中心售后面板。
 */
@Tag(name = "17-售后", description = "申请售后 / 售后列表 / 详情 / 取消")
@RestController
@RequestMapping("/api/v1/aftersales")
@RequiredArgsConstructor
public class AfterSaleController {

    private final AfterSaleService afterSaleService;

    @Operation(summary = "申请售后", description = "仅「待评价 / 已完成」且无处理中售后的订单可申请")
    @PostMapping
    public Result<Long> apply(@Validated @RequestBody ApplyAfterSaleDTO dto) {
        return Result.ok(afterSaleService.apply(SecurityUtils.getUserId(), dto));
    }

    @Operation(summary = "我的售后列表", description = "status：0 处理中 / 1 已完成 / 2 已取消，不传返回全部")
    @GetMapping
    public Result<List<AfterSaleVO>> list(@RequestParam(required = false) Integer status) {
        return Result.ok(afterSaleService.list(SecurityUtils.getUserId(), status));
    }

    @Operation(summary = "售后详情", description = "含处理进度时间轴")
    @GetMapping("/{id}")
    public Result<AfterSaleVO> detail(@PathVariable Long id) {
        return Result.ok(afterSaleService.detail(SecurityUtils.getUserId(), id));
    }

    @Operation(summary = "取消售后申请")
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        afterSaleService.cancel(SecurityUtils.getUserId(), id);
        return Result.ok();
    }
}
