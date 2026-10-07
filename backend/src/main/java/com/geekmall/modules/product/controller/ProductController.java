package com.geekmall.modules.product.controller;

import com.geekmall.common.result.PageResult;
import com.geekmall.common.result.Result;
import com.geekmall.modules.product.dto.ProductQueryDTO;
import com.geekmall.modules.product.service.ProductService;
import com.geekmall.modules.product.vo.ProductCardVO;
import com.geekmall.modules.product.vo.ProductDetailVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品接口，对应前端 CategoryView / SearchView / ProductView。
 */
@Tag(name = "03-商品", description = "商品列表 / 搜索 / 详情 / 推荐")
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @Operation(summary = "商品列表（分类筛选 / 排序 / 分页）")
    @GetMapping
    public Result<PageResult<ProductCardVO>> list(@Valid @ModelAttribute ProductQueryDTO query) {
        return Result.ok(productService.page(query));
    }

    @Operation(summary = "商品搜索（关键词 + 筛选，复用列表逻辑）")
    @GetMapping("/search")
    public Result<PageResult<ProductCardVO>> search(@Valid @ModelAttribute ProductQueryDTO query) {
        return Result.ok(productService.page(query));
    }

    @Operation(summary = "商品详情")
    @GetMapping("/{id}")
    public Result<ProductDetailVO> detail(@PathVariable Long id) {
        return Result.ok(productService.detail(id));
    }

    @Operation(summary = "看了又看推荐")
    @GetMapping("/{id}/recommend")
    public Result<List<ProductCardVO>> recommend(@PathVariable Long id,
                                                 @RequestParam(defaultValue = "6") int limit) {
        return Result.ok(productService.recommend(id, limit));
    }
}
