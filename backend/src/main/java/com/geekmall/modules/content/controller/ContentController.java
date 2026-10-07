package com.geekmall.modules.content.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.content.service.ContentService;
import com.geekmall.modules.content.vo.AboutStatVO;
import com.geekmall.modules.content.vo.FaqVO;
import com.geekmall.modules.content.vo.PolicyVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内容接口，对应前端 AboutView / HelpView / PolicyView。全部为白名单接口。
 */
@Tag(name = "14-内容", description = "关于我们 / 帮助中心 / 政策条款")
@RestController
@RequestMapping("/api/v1/cms")
@RequiredArgsConstructor
public class ContentController {

    private final ContentService contentService;

    @Operation(summary = "关于我们数据统计")
    @GetMapping("/about")
    public Result<List<AboutStatVO>> about() {
        return Result.ok(contentService.about());
    }

    @Operation(summary = "帮助中心常见问题")
    @GetMapping("/faqs")
    public Result<List<FaqVO>> faqs() {
        return Result.ok(contentService.faqs());
    }

    @Operation(summary = "政策条款")
    @GetMapping("/policies")
    public Result<List<PolicyVO>> policies() {
        return Result.ok(contentService.policies());
    }
}
