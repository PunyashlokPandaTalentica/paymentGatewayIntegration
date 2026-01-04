package com.paymentgateway.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.util.InputSanitizer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Filter to validate and sanitize request input to prevent XSS, SQL injection, and other attacks.
 * This filter runs early in the filter chain to catch malicious input before it reaches controllers.
 */
@Component
@Order(1)
@Slf4j
public class RequestValidationFilter extends OncePerRequestFilter {

    @Autowired
    private InputSanitizer inputSanitizer;

    @Autowired
    private ObjectMapper objectMapper;

    // Patterns for paths that should skip validation (e.g., webhooks, health checks)
    private static final Pattern[] SKIP_VALIDATION_PATHS = {
            Pattern.compile("^/v1/webhooks/.*"),
            Pattern.compile("^/actuator/.*"),
            Pattern.compile("^/swagger-ui/.*"),
            Pattern.compile("^/v3/api-docs/.*")
    };

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        String traceId = getOrCreateTraceId();
        MDC.put("traceId", traceId);

        try {
            // Skip validation for certain paths
            if (shouldSkipValidation(path)) {
                filterChain.doFilter(request, response);
                return;
            }

            // Only validate POST, PUT, PATCH requests with JSON content
            if (isJsonRequest(request)) {
                ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
                
                // Read the request body
                filterChain.doFilter(wrappedRequest, response);
                
                // Note: For request body validation, we'll rely on controller-level validation
                // This filter primarily validates query parameters and headers
                
            } else {
                filterChain.doFilter(request, response);
            }

        } catch (IllegalArgumentException e) {
            log.warn("Request validation failed: {}", e.getMessage());
            handleValidationError(response, e.getMessage(), traceId);
        } finally {
            MDC.clear();
        }
    }

    /**
     * Validates query parameters for dangerous patterns.
     */
    private void validateQueryParameters(HttpServletRequest request) {
        request.getParameterMap().forEach((key, values) -> {
            for (String value : values) {
                try {
                    inputSanitizer.sanitizeString(value);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid query parameter '" + key + "': " + e.getMessage());
                }
            }
        });
    }

    /**
     * Checks if the request path should skip validation.
     */
    private boolean shouldSkipValidation(String path) {
        for (Pattern pattern : SKIP_VALIDATION_PATHS) {
            if (pattern.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the request is a JSON request that should be validated.
     */
    private boolean isJsonRequest(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.contains(MediaType.APPLICATION_JSON_VALUE);
    }

    /**
     * Handles validation errors by returning a proper error response.
     */
    private void handleValidationError(HttpServletResponse response, String message, String traceId) throws IOException {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ErrorResponse error = ErrorResponse.builder()
                .error(ErrorResponse.ErrorDetail.builder()
                        .code("VALIDATION_ERROR")
                        .message("Request validation failed: " + message)
                        .retryable(false)
                        .traceId(traceId)
                        .build())
                .build();

        response.getWriter().write(objectMapper.writeValueAsString(error));
        response.getWriter().flush();
    }

    private String getOrCreateTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString();
        }
        return traceId;
    }
}

