package com.geekmall.common.resilience;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 业务级熔断降级的统一入口：把「调用下游 → 记录成败 → 熔断判定 → 降级回退」
 * 收敛到一处，业务代码只需声明「资源名 + 主逻辑 +（可选）降级逻辑」。
 *
 * <pre>{@code
 * // 无降级：熔断打开时直接失败（适合"凭证必须可靠落库"这类场景）
 * return guard.execute("minio-upload", () -> client.putObject(...));
 *
 * // 有降级：主路径不可用时自动改走备用实现
 * return guard.execute("minio-upload", () -> uploadToMinio(file), () -> saveToLocalDisk(file));
 * }</pre>
 *
 * <p><b>调用边界的约定</b>：交给 {@code execute} 的必须是「依赖调用」本身。
 * 参数校验、权限判断等业务前置逻辑要放在外面 —— 否则用户传错一个参数也会被计成
 * 依赖失败，既污染熔断统计，又可能把「参数错误」变成「降级到备用存储」。</p>
 *
 * <p>熔断器按资源名集中注册（{@link #breaker}），缓存层与业务层因此共用同一套状态表，
 * 不会出现「同一个依赖两套熔断计数」的混乱。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResilienceGuard {

    private final ResilienceProperties properties;
    private final ResilienceMetrics metrics;

    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    /**
     * 取（或创建）资源对应的熔断器。
     *
     * <p>缓存层也通过它获取熔断器，从而与业务层共用同一个状态机实例。</p>
     */
    public CircuitBreaker breaker(String resource) {
        return breakers.computeIfAbsent(resource, name -> {
            CircuitBreaker created = new CircuitBreaker(name, properties.forResource(name),
                    () -> metrics.recordOpened(name));
            metrics.registerStateGauge(name, created);
            return created;
        });
    }

    /** 无降级路径：熔断打开或并发超限时抛 {@link CircuitOpenException}。 */
    public <T> T execute(String resource, Action<T> action) {
        return execute(resource, action, null);
    }

    /**
     * 带降级路径的执行。
     *
     * @param resource 资源名，用于取配置、打指标、记日志
     * @param action   主逻辑（依赖调用）
     * @param fallback 降级逻辑；为 null 表示该场景不允许降级
     */
    public <T> T execute(String resource, Action<T> action, Action<T> fallback) {
        if (!properties.isEnabled()) {
            return runDirect(action);
        }

        CircuitBreaker circuitBreaker = breaker(resource);
        if (!circuitBreaker.tryAcquire()) {
            metrics.recordRejected(resource);
            return degrade(resource, fallback, "熔断器已打开");
        }

        Semaphore semaphore = semaphore(resource);
        boolean acquired = semaphore == null || semaphore.tryAcquire();
        if (!acquired) {
            metrics.recordRejected(resource);
            return degrade(resource, fallback,
                    "并发已达上限(" + properties.forResource(resource).getMaxConcurrentCalls() + ")");
        }

        long startNanos = System.nanoTime();
        try {
            T result = action.run();
            circuitBreaker.recordSuccess(Duration.ofNanos(System.nanoTime() - startNanos));
            return result;
        } catch (Exception ex) {
            circuitBreaker.recordFailure(Duration.ofNanos(System.nanoTime() - startNanos));
            metrics.recordFailure(resource, ex);
            log.warn("[熔断] {} 调用失败：{}", resource, ex.toString());
            if (fallback == null) {
                throw propagate(ex);
            }
            return degrade(resource, fallback, "主路径失败：" + ex.getMessage());
        } finally {
            if (acquired && semaphore != null) {
                semaphore.release();
            }
        }
    }

    private <T> T degrade(String resource, Action<T> fallback, String reason) {
        if (fallback == null) {
            // 无降级路径的场景：明确失败，让用户重试，而不是静默丢数据
            throw new CircuitOpenException(resource, reason);
        }
        log.warn("[熔断降级] {} 转入降级路径（原因：{}）", resource, reason);
        metrics.recordDegraded(resource);
        try {
            return fallback.run();
        } catch (Exception ex) {
            throw new CircuitOpenException(resource, reason + "；且降级路径同样失败", ex);
        }
    }

    /**
     * 并发上限信号量。
     *
     * <p>按资源首次使用时创建，之后不再随配置变化 —— 运行期改变并发上限会让
     * 已经持有的许可数量与新上限不一致，属可接受但需知晓的取舍。</p>
     */
    private Semaphore semaphore(String resource) {
        int limit = properties.forResource(resource).getMaxConcurrentCalls();
        if (limit <= 0) {
            return null;
        }
        return semaphores.computeIfAbsent(resource, name -> new Semaphore(limit, true));
    }

    private static <T> T runDirect(Action<T> action) {
        try {
            return action.run();
        } catch (Exception ex) {
            throw propagate(ex);
        }
    }

    private static RuntimeException propagate(Exception ex) {
        if (ex instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException("依赖调用抛出受检异常", ex);
    }

    /**
     * 允许抛出受检异常的执行体。
     *
     * <p>刻意不用 {@link java.util.function.Supplier}：下游 SDK（如 MinIO 客户端）
     * 的方法普遍声明 {@code throws Exception}，若用 Supplier，每个调用点都要写
     * 一层无意义的 try-catch 包装，反而掩盖了「这里在调下游」这个关键信号。</p>
     */
    @FunctionalInterface
    public interface Action<T> {
        T run() throws Exception;
    }
}
