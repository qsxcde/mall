package com.geekmall.modules.marketing.controller;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.Result;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.marketing.config.SeckillProperties;
import com.geekmall.modules.marketing.dto.GrabSeckillDTO;
import com.geekmall.modules.marketing.queue.SeckillGrabResult;
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
import java.util.function.Supplier;

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
 *
 * <p><b>削峰模式（{@code mall.seckill.async.enabled=true}）</b>下的行为差异：抢购接口不再
 * 当场落库，而是预扣成功即入队、立即返回「抢购请求号」，由后台消费者异步落库，
 * 前端改为轮询 {@code GET /api/v1/seckill/result/{requestId}} 取最终结果。</p>
 */
@Slf4j
@Tag(name = "11-秒杀", description = "场次 / 秒杀商品 / 抢购")
@RestController
@RequestMapping("/api/v1/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private static final long GRAB_TIMEOUT_MS = 10_000L;

    private final SeckillService seckillService;
    private final SeckillProperties seckillProperties;

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
            description = "Redis Lua 原子预扣 + 一人一单 + 防超卖。"
                    + "默认返回待付款订单号；开启 mall.seckill.async.enabled 后返回抢购请求号，"
                    + "需轮询 /seckill/result/{requestId} 获取订单号（需登录）")
    @PostMapping("/{itemId}/order")
    public DeferredResult<Result<String>> grab(@PathVariable Long itemId,
                                               @Validated @RequestBody GrabSeckillDTO dto) {
        // 必须在进入异步线程前取出登录态（SecurityContext 是 ThreadLocal，不跨线程）
        Long userId = SecurityUtils.getUserId();
        Long addressId = dto.getAddressId();

        DeferredResult<Result<String>> deferred = new DeferredResult<>(GRAB_TIMEOUT_MS);

        if (seckillProperties.getAsync().isEnabled()) {
            // 削峰模式：预扣 + 入队都是毫秒级，无需占用舱壁线程，也就不必再进线程池排队
            setResult(deferred, () -> seckillService.grabAsync(userId, itemId, addressId));
            return deferred;
        }

        try {
            seckillExecutor.execute(() -> setResult(deferred,
                    () -> seckillService.grab(userId, itemId, addressId)));
        } catch (RejectedExecutionException e) {
            // 舱壁已满：快速失败，绝不排队到拖垮系统
            log.warn("[秒杀舱壁] 线程池已满，快速拒绝：userId={}, itemId={}", userId, itemId);
            deferred.setResult(Result.fail(ResultCode.TOO_MANY_REQUESTS.getCode(),
                    "当前抢购人数过多，请稍后再试"));
        }
        return deferred;
    }

    @Operation(summary = "查询抢购结果",
            description = "削峰模式下轮询此接口获取订单号；记录不存在 / 已过期 / 非本人一律返回资源不存在（需登录）")
    @GetMapping("/result/{requestId}")
    public Result<SeckillGrabResult> result(@PathVariable String requestId) {
        return Result.ok(seckillService.grabResult(SecurityUtils.getUserId(), requestId));
    }

    /**
     * 统一「执行 → 成功 / 业务失败 / 系统异常」三段式。
     *
     * <p>抽出来是为了让同步与削峰两条路径对异常的响应完全一致 ——
     * 两条路径的差异只应在「何时落库」，不应在错误码上。</p>
     */
    private void setResult(DeferredResult<Result<String>> deferred, Supplier<String> action) {
        try {
            deferred.setResult(Result.ok(action.get()));
        } catch (BizException e) {
            // 库存不足 / 重复抢购等属于预期内的失败，返回业务码而非 5xx
            deferred.setResult(Result.fail(e.getCode(), e.getMessage()));
        } catch (Exception e) {
            log.error("秒杀处理异常", e);
            deferred.setResult(Result.fail(ResultCode.SYSTEM_ERROR.getCode(),
                    ResultCode.SYSTEM_ERROR.getMsg()));
        }
    }
}
