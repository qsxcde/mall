package com.geekmall.modules.cart.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.cart.dto.AddCartDTO;
import com.geekmall.modules.cart.dto.CartUpdateDTO;
import com.geekmall.modules.cart.service.CartService;
import com.geekmall.modules.cart.vo.CartItemVO;
import com.geekmall.modules.cart.vo.CartSummaryVO;
import com.geekmall.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 购物车接口，对应前端 CartView 与顶栏角标。
 */
@Tag(name = "06-购物车", description = "购物车增删改查 / 勾选 / 汇总")
@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @Operation(summary = "购物车列表")
    @GetMapping("/items")
    public Result<List<CartItemVO>> list() {
        return Result.ok(cartService.list(SecurityUtils.getUserId()));
    }

    @Operation(summary = "加入购物车")
    @PostMapping("/items")
    public Result<Long> add(@Validated @RequestBody AddCartDTO dto) {
        return Result.ok(cartService.add(SecurityUtils.getUserId(), dto));
    }

    @Operation(summary = "批量加入购物车（再次购买）")
    @PostMapping("/items/batch")
    public Result<Void> addBatch(@Validated @RequestBody List<AddCartDTO> items) {
        cartService.addBatch(SecurityUtils.getUserId(), items);
        return Result.ok();
    }

    @Operation(summary = "修改数量")
    @PutMapping("/items/{id}/qty")
    public Result<Void> updateQty(@PathVariable Long id, @Validated @RequestBody CartUpdateDTO dto) {
        cartService.updateQty(SecurityUtils.getUserId(), id, dto);
        return Result.ok();
    }

    @Operation(summary = "修改勾选状态")
    @PutMapping("/items/{id}/checked")
    public Result<Void> updateChecked(@PathVariable Long id, @Validated @RequestBody CartUpdateDTO dto) {
        cartService.updateChecked(SecurityUtils.getUserId(), id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除单项")
    @DeleteMapping("/items/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        cartService.remove(SecurityUtils.getUserId(), id);
        return Result.ok();
    }

    @Operation(summary = "清空购物车")
    @DeleteMapping("/items")
    public Result<Void> clear() {
        cartService.clear(SecurityUtils.getUserId());
        return Result.ok();
    }

    @Operation(summary = "全选 / 取消全选")
    @PutMapping("/checked")
    public Result<Void> checkAll(@RequestBody CartUpdateDTO dto) {
        cartService.checkAll(SecurityUtils.getUserId(), dto.getChecked());
        return Result.ok();
    }

    @Operation(summary = "删除已勾选商品")
    @DeleteMapping("/checked")
    public Result<Void> clearChecked() {
        cartService.clearChecked(SecurityUtils.getUserId());
        return Result.ok();
    }

    @Operation(summary = "购物车汇总（角标 / 结算栏）")
    @GetMapping("/summary")
    public Result<CartSummaryVO> summary() {
        return Result.ok(cartService.summary(SecurityUtils.getUserId()));
    }
}
