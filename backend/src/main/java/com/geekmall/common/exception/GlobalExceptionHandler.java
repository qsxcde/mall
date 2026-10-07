package com.geekmall.common.exception;

import com.geekmall.common.log.RepeatLogThrottler;
import com.geekmall.common.ratelimit.RateLimitException;
import com.geekmall.common.result.Result;
import com.geekmall.common.result.ResultCode;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：把各类异常统一收敛为 {@link Result} 结构。
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    /** 重复异常日志节流：见 {@link RepeatLogThrottler} 的说明。 */
    private final RepeatLogThrottler repeatLogThrottler;

    /**
     * 接口限流：快速失败，返回 429，避免请求继续堆积。
     *
     * <p>同时下发 {@code Retry-After}，让客户端知道该退避多久；
     * 没有这个头时调用方通常会立刻重试，反而把限流窗口继续打满。</p>
     */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Result<Void>> handleRateLimit(RateLimitException e) {
        log.warn("[接口限流] {}（建议 {}s 后重试）", e.getMessage(), e.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .body(Result.fail(ResultCode.TOO_MANY_REQUESTS));
    }

    /** 业务异常：预期内的失败，打 warn，不打堆栈。 */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        log.warn("[业务异常] code={}, msg={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** @RequestBody 参数校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("[参数校验失败] {}", msg);
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), msg.isEmpty() ? ResultCode.PARAM_ERROR.getMsg() : msg);
    }

    /** 表单绑定校验失败。 */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("[参数绑定失败] {}", msg);
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), msg);
    }

    /** @RequestParam / @PathVariable 上的约束校验失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("[参数约束失败] {}", msg);
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), msg);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, HttpMessageNotReadableException.class})
    public Result<Void> handleBadRequest(Exception e) {
        log.warn("[请求格式错误] {}", e.getMessage());
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), ResultCode.PARAM_ERROR.getMsg());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("[请求方法不支持] {}", e.getMessage());
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), "请求方法不支持：" + e.getMethod());
    }

    /**
     * 接口不存在。必须单独处理：否则会被兜底的 Exception 处理器吞成「系统繁忙」，
     * 导致前端把 404 误判为服务异常。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public Result<Void> handleNotFound(Exception e) {
        log.warn("[接口不存在] {}", e.getMessage());
        return Result.fail(ResultCode.NOT_FOUND);
    }

    /** 鉴权通过但权限不足（@PreAuthorize 拒绝等）。 */
    @ExceptionHandler(AccessDeniedException.class)
    public Result<Void> handleAccessDenied(AccessDeniedException e) {
        log.warn("[权限不足] {}", e.getMessage());
        return Result.fail(ResultCode.FORBIDDEN);
    }

    /**
     * 兜底：非预期异常。
     *
     * <p>这里是全项目最大的日志放大点——原本对<b>每一个</b>非预期异常都打完整堆栈，
     * 接口被刷或下游持续抖断时，日志会被重复堆栈淹没，真正有用的那条反而找不到。
     * 现在按「异常类型 + 崩溃点」节流：窗口内首次打完整堆栈，其后只记一行摘要 +
     * 累计次数（堆栈本身在 JSON 输出里还会被截断）。</p>
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        RepeatLogThrottler.Decision decision = repeatLogThrottler.record(fingerprint(e));
        if (decision.firstInWindow()) {
            log.error("[系统异常] ", e);
        } else {
            log.warn("[系统异常-重复] count={} type={} message={}",
                    decision.count(), e.getClass().getSimpleName(), e.getMessage());
        }
        return Result.fail(ResultCode.SYSTEM_ERROR);
    }

    /**
     * 错误指纹：异常类型 + 最上层业务栈帧。
     *
     * <p>不用异常 message 参与：message 里常带 id / 金额等易变内容，
     * 会让每一次异常都变成「新的指纹」，节流直接失效。</p>
     */
    private static String fingerprint(Exception e) {
        StackTraceElement[] stack = e.getStackTrace();
        String origin = stack.length > 0
                ? stack[0].getClassName() + "#" + stack[0].getMethodName()
                : e.getClass().getName();
        return e.getClass().getName() + "@" + origin;
    }
}
