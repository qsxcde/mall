package com.geekmall.common.log;

import org.slf4j.MDC;

/**
 * 链路上下文的统一读写入口。
 *
 * <p>MDC 基于 {@link ThreadLocal}，只在「当前线程」有效：<b>跨线程池、跨队列都不会自动传递</b>。
 * 因此这里定下一条纪律——所有写入/恢复 MDC 的地方都走本类，键名不散落在各处，
 * 避免「有的地方写 traceId、有的地方写 trace」导致链路对不上。</p>
 *
 * <p>三类场景的处理方式：</p>
 * <ul>
 *   <li><b>HTTP 入口</b>：{@code TraceIdFilter} 生成并绑定，由本类统一键名；</li>
 *   <li><b>线程池内</b>：无法自动传递，用 {@code MdcTaskDecorator} 在任务提交时复制；</li>
 *   <li><b>消息队列内</b>：连线程池都跨不过去（进程/时间都不同），必须把 traceId
 *       <b>编进消息体</b>，由消费端用 {@link #bindTraceId(String)} 恢复。</li>
 * </ul>
 */
public final class TraceContext {

    /**
     * 链路 ID 的 MDC 键名。
     *
     * <p>日志文本 pattern（{@code %X{traceId:-}}）与 JSON 编码器的 {@code <mdc/>} provider
     * 都依赖这个键名，改这里等于同时改两处输出格式，务必同步。</p>
     */
    public static final String TRACE_ID = "traceId";

    private TraceContext() {
    }

    /** 当前线程的链路 ID；后台线程或非 HTTP 入口下可能为 {@code null}。 */
    public static String currentTraceId() {
        return MDC.get(TRACE_ID);
    }

    /**
     * 把链路 ID 绑定到当前线程。
     *
     * <p>{@code null} 视为「无链路信息」而不是错误：老版本消息体里没有该字段、
     * 或消息来自没有请求上下文的后台任务，都属于正常情况。</p>
     */
    public static void bindTraceId(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(TRACE_ID, traceId);
        }
    }

    /** 清除当前线程的链路 ID；必须在 {@code finally} 中调用，防止线程复用造成上下文串味。 */
    public static void clearTraceId() {
        MDC.remove(TRACE_ID);
    }
}
