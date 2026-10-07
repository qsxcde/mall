package com.geekmall.modules.user.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.user.service.MemberService;
import com.geekmall.modules.user.vo.MemberInfoVO;
import com.geekmall.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会员中心接口，对应前端 MemberView。
 */
@Tag(name = "18-会员中心", description = "等级 / 成长进度 / 权益 / 签到状态")
@RestController
@RequestMapping("/api/v1/member")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @Operation(summary = "会员信息")
    @GetMapping("/info")
    public Result<MemberInfoVO> info() {
        return Result.ok(memberService.info(SecurityUtils.getUserId()));
    }
}
