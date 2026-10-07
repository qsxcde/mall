package com.geekmall.modules.marketing.controller;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.Result;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.marketing.dto.GrabSeckillDTO;
import com.geekmall.modules.marketing.service.SeckillService;
import com.geekmall.modules.marketing.vo.SeckillItemVO;
import com.geekmall.modules.marketing.vo.SeckillSessionVO;
import com.geekmall.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * 秒杀接口，对应前端 SeckillView。
 *
 * <p>抢购接口做两级防护：</p>
 * <ol>
 *   <li>限流快速拒绝（规则集中维护在 {@link com.geekmall.common.ratelimit.RateLimitPolicies}），
 *       超限直接 429，不让无效请求进入业务逻辑；</li>
 *   <li>真正的抢购逻辑提交到独立的 {@code seckillExecutor}（舱壁），队列满立即失败，
 *       且通过 {@link DeferredResult} 释放 Tomcat 工作线程，避免秒杀把整站线程池打满。</li>
 * </ol>
 */
@Slf4j
@Tag(name = "11-秒杀", description = "场次 / 秒杀商品 / 抢购")
@RestController
@RequestMapping("/api/v1/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private static final long GRAB_TIMEOUT_MS = 10_000L;

    private final SeckillService seckillService;

    /** 秒杀专用线程池，避免与普通请求争抢 Tomcat 线程。 */
    @Resource(name = "seckillExecutor")
    private ThreadPoolTaskExecutor seckillExecutor;

    @Operation(summary = "秒杀场次列表")
    @GetMapping("/sessions")
    public Result<List<SeckillSessionVO>> sessions() {
        return Result.ok(seckillService.sessions());
    }

    @Operation(summary = "场次秒杀商品")
    @GetMapping("/items")
    public Result<List<SeckillItemVO>> items(@RequestParam(required = false) Long sessionId) {
        return Result.ok(seckillService.items(sessionId));
    }

    @Operation(summary = "立即抢购",
            description = "Redis Lua 原子预扣 + 一人一单 + 防超卖；成功返回待付款订单号（需登录）")
    @PostMapping("/{itemId}/order")
    public DeferredResult<Result<String>> grab(@PathVariable Long itemId,
                                               @Validated @RequestBody GrabSeckillDTO dto) {
        // 必须在进入异步线程前取出登录态（SecurityContext 是 ThreadLocal，不跨线程）
        Long userId = SecurityUtils.getUserId();
        Long addressId = dto.getAddressId();

        DeferredResult<Result<String>> deferred = new DeferredResult<>(GRAB_TIMEOUT_MS);
        try {
            seckillExecutor.execute(() -> {
                try {
                    deferred.setResult(Result.ok(seckillService.grab(userId, itemId, addressId)));
                } catch (BizException e) {
                    // 库存不足 / 重复抢购等属于预期内的失败，返回业务码而非 5xx
                    deferred.setResult(Result.fail(e.getCode(), e.getMessage()));
                } catch (Exception e) {
                    log.error("秒杀处理异常：userId={}, itemId={}", userId, itemId, e);
                    deferred.setResult(Result.fail(ResultCode.SYSTEM_ERROR.getCode(),
                            ResultCode.SYSTEM_ERROR.getMsg()));
                }
            });
        } catch (RejectedExecutionException e) {
            // 舱壁已满：快速失败，绝不排队到拖垮系统
            log.warn("[秒杀舱壁] 线程池已满，快速拒绝：userId={}, itemId={}", userId, itemId);
            deferred.setResult(Result.fail(ResultCode.TOO_MANY_REQUESTS.getCode(),
                    "当前抢购人数过多，请稍后再试"));
        }
        return deferred;
    }
}
