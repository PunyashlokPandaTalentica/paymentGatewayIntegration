package com.paymentgateway.api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Filter to add trace ID to MDC for all incoming requests.
 * This enables comprehensive logging with trace IDs across the application.
 */
@Component
@Slf4j
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String TRACE_ID_MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            // Get trace ID from header or generate new one
            String traceId = request.getHeader(TRACE_ID_HEADER);
            if (traceId == null || traceId.isEmpty()) {
                traceId = UUID.randomUUID().toString();
            }

            // Add trace ID to MDC
            MDC.put(TRACE_ID_MDC_KEY, traceId);

            // Add trace ID to response header
            response.setHeader(TRACE_ID_HEADER, traceId);

            // Add request path and method to MDC for better logging
            MDC.put("requestPath", request.getRequestURI());
            MDC.put("requestMethod", request.getMethod());

            filterChain.doFilter(request, response);
        } finally {
            // Clear MDC after request processing
            MDC.clear();
        }
    }
}

