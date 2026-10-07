package com.geekmall.config;

import com.geekmall.common.log.MdcTaskDecorator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置。
 *
 * <p>P1-3：{@code @Async} 若不指定线程池，会落到 Spring Boot 默认的
 * {@code applicationTaskExecutor}（core=8、max=Integer.MAX_VALUE、<b>队列无界</b>），
 * 流量一大就会无限堆积直至 OOM。这里为核心异步任务定义<b>有界</b>线程池，
 * 满载时走 {@code CallerRunsPolicy} 优雅降级（由调用线程执行，形成天然背压）。</p>
 *
 * <p>两个线程池都挂了 {@link MdcTaskDecorator}：{@code @Async} 在新线程里执行，
 * 不复制 MDC 的话日志中的 traceId 会变成 {@code -}，异步链路的日志将无法与主流程关联。</p>
 */
@Slf4j
@Configuration
public class AsyncConfig {

    /** 订单状态变更消息线程池：低延迟、有界、拒满降级。 */
    @Bean("messageExecutor")
    public ThreadPoolTaskExecutor messageExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        // 有界队列：超过容量触发拒绝策略，避免无界堆积
        executor.setQueueCapacity(2000);
        // 兜底降级：队列满时由调用线程执行，形成背压而不是丢弃
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 复制提交线程的 MDC（traceId 等），否则异步段日志断链
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setThreadNamePrefix("msg-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    /**
     * 秒杀抢购隔离线程池（舱壁）。
     *
     * <p>P0-5：秒杀是最容易被瞬时流量打满的接口，单独隔离后即使秒杀线程池耗尽，
     * 普通浏览 / 下单接口仍能正常响应（不共享 Tomcat 工作线程与公共池）。</p>
     */
    @Bean("seckillExecutor")
    public ThreadPoolTaskExecutor seckillExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(64);
        executor.setMaxPoolSize(128);
        // 队列有界：秒杀瞬时洪峰先排队，真正打满后立即拒绝（配合 DeferredResult 不占用 Tomcat 线程）
        executor.setQueueCapacity(2000);
        // 队列满立即失败，交由上层返回「繁忙」，绝不阻塞 Tomcat 线程
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 复制提交线程的 MDC（traceId 等），抢购请求的异步处理日志才能与入口请求串联
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setThreadNamePrefix("seckill-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }
}
