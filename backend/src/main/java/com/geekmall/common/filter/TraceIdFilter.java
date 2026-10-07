package com.geekmall.common.filter;

import com.geekmall.common.constant.SecurityConstants;
import com.geekmall.common.log.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * TraceId 过滤器：为每个请求生成/透传链路 ID，写入 MDC 与响应头。
 *
 * <p>这是可观测性中性价比最高的一步：日志带上 traceId 后即可串起整条调用链。</p>
 *
 * <p><b>它的边界只在当前请求线程内</b>。跨线程池由 {@code MdcTaskDecorator} 承担，
 * 跨消息队列由「traceId 编进消息体 + 消费端恢复」承担——三者缺任何一环，
 * 链路都会在对应边界处断掉（秒杀削峰正是在这两个边界上各断一次）。</p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(SecurityConstants.TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        TraceContext.bindTraceId(traceId);
        response.setHeader(SecurityConstants.TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clearTraceId();
        }
    }
}
