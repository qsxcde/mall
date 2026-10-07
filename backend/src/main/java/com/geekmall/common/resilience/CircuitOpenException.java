package com.geekmall.common.resilience;

/**
 * 熔断器打开、并发已达上限且没有降级路径时抛出。
 *
 * <p>与 {@link com.geekmall.common.exception.BizException} 的区别：这里表达的是
 * 「依赖当前不可用，且该场景不允许降级」，属于系统级瞬时故障，
 * 调用方（或全局异常处理器）应返回「系统繁忙，请稍后重试」这类可重试提示，
 * 而不是把它当作业务规则拒绝。</p>
 */
public class CircuitOpenException extends RuntimeException {

    private final String resource;

    public CircuitOpenException(String resource, String reason) {
        super("依赖不可用[" + resource + "]：" + reason);
        this.resource = resource;
    }

    public CircuitOpenException(String resource, String reason, Throwable cause) {
        super("依赖不可用[" + resource + "]：" + reason, cause);
        this.resource = resource;
    }

    public String getResource() {
        return resource;
    }
}
