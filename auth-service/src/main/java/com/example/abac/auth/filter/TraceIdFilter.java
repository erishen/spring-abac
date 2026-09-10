package com.example.abac.auth.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 轻量链路追踪：网关生成的 X-Trace-Id 沿请求头贯穿到本服务，
 * 放入 MDC（日志 pattern 里以 [traceId] 呈现，可 grep 串联整条链路），并回写响应头。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
        response.setHeader(TRACE_HEADER, traceId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("traceId", traceId)) {
            chain.doFilter(request, response);
        }
    }
}
