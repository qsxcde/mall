package com.geekmall.modules.product.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.product.service.ProductService;
import com.geekmall.modules.product.vo.HomeFloorVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页接口，对应前端 HomeView。
 */
@Tag(name = "05-首页", description = "首页楼层聚合")
@RestController
@RequestMapping("/api/v1/home")
@RequiredArgsConstructor
public class HomeController {

    private final ProductService productService;

    @Operation(summary = "首页楼层（分类 + 热销 + 新品）")
    @GetMapping("/floors")
    public Result<HomeFloorVO> floors() {
        return Result.ok(productService.homeFloors());
    }
}
