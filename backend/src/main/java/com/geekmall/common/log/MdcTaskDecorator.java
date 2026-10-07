package com.geekmall.common.log;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 把「提交任务时的 MDC 上下文」复制到「执行任务的线程」。
 *
 * <p>不挂这个装饰器时，{@code @Async} 任务在新线程里拿到的 MDC 是空的——日志里的
 * {@code traceId} 会变成 {@code -}，于是「下单主流程」与「订单状态消息」两条日志
 * 再也串不起来。这是自研 traceId 方案（而非链路追踪 SDK）最典型的断链点。</p>
 *
 * <p>两点刻意设计：</p>
 * <ol>
 *   <li><b>提交时快照</b>：在 {@code decorate()} 里就取好上下文，而不是在任务真正运行时再取。
 *       提交线程与执行线程不是同一个，后者取到的只会是自己的空上下文。</li>
 *   <li><b>执行后还原</b>：线程池会复用线程，任务结束后必须恢复现场（含清空），
 *       否则上一个请求的 traceId 会「串」到下一个无关任务上——比丢失更难排查。</li>
 * </ol>
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> submitterContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (submitterContext != null) {
                MDC.setContextMap(submitterContext);
            } else {
                MDC.clear();
            }
            try {
                runnable.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
