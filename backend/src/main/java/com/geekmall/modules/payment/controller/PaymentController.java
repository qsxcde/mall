package com.geekmall.modules.payment.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.payment.dto.CreatePaymentDTO;
import com.geekmall.modules.payment.service.PaymentService;
import com.geekmall.modules.payment.vo.PaymentVO;
import com.geekmall.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付接口，对应前端 PaymentView。
 */
@Tag(name = "09-支付", description = "创建支付 / 查询状态 / 模拟支付 / 渠道回调")
@RestController
@RequestMapping("/api/v1/pay")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "创建支付单")
    @PostMapping("/create")
    public Result<PaymentVO> create(@Validated @RequestBody CreatePaymentDTO dto) {
        return Result.ok(paymentService.create(SecurityUtils.getUserId(), dto));
    }

    @Operation(summary = "查询支付状态", description = "前端轮询此接口等待支付结果")
    @GetMapping("/{tradeNo}/status")
    public Result<PaymentVO> status(@PathVariable String tradeNo) {
        return Result.ok(paymentService.query(SecurityUtils.getUserId(), tradeNo));
    }

    @Operation(summary = "模拟支付 / 刷新状态", description = "本地渠道：签一份支付成功通知投递给自己；支付宝渠道：主动查单刷新")
    @PostMapping("/{tradeNo}/mock-pay")
    public Result<Void> mockPay(@PathVariable String tradeNo) {
        paymentService.mockPay(SecurityUtils.getUserId(), tradeNo);
        return Result.ok();
    }

    /**
     * 支付渠道异步回调。
     *
     * <p><b>两个刻意的约定</b>：</p>
     * <ol>
     *     <li>入参是<b>表单</b>（支付宝异步通知为 {@code application/x-www-form-urlencoded}），
     *         用 {@code @RequestParam Map} 接收 —— 不能反序列化成 DTO，否则验签所需的
     *         原始参数集合会丢字段。</li>
     *     <li>应答是<b>裸文本 {@code success} / {@code failure}</b>：支付宝只认这个，
     *         返回 Result JSON 会被判为失败并反复重试（因此必须声明 {@code produces}）。</li>
     * </ol>
     */
    @Operation(summary = "支付渠道异步回调", description = "免登录白名单接口；表单参数；验签 → 幂等 → 金额校验；应答必须是裸文本 success/failure")
    @PostMapping(value = "/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String callback(@RequestParam Map<String, String> params) {
        return paymentService.handleNotify(params);
    }
}
