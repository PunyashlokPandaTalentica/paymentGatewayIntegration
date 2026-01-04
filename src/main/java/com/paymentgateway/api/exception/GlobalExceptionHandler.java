package com.paymentgateway.api.exception;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.paymentgateway.api.dto.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
        String traceId = getOrCreateTraceId();
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + ", " + b)
                .orElse("Validation failed");

        log.warn("Validation error: {}", message);

        ErrorResponse error = ErrorResponse.builder()
                .error(ErrorResponse.ErrorDetail.builder()
                        .code("VALIDATION_ERROR")
                        .message(message)
                        .retryable(false)
                        .traceId(traceId)
                        .build())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestHeaderException(MissingRequestHeaderException e) {
        String traceId = getOrCreateTraceId();
        log.warn("Missing required header: {}", e.getHeaderName());

        ErrorResponse error = ErrorResponse.builder()
                .error(ErrorResponse.ErrorDetail.builder()
                        .code("MISSING_HEADER")
                        .message("Missing required header: " + e.getHeaderName())
                        .retryable(false)
                        .traceId(traceId)
                        .build())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handleGatewayException(GatewayException e) {
        String traceId = getOrCreateTraceId();
        HttpStatus status = e.isRetryable() 
                ? HttpStatus.SERVICE_UNAVAILABLE 
                : HttpStatus.BAD_REQUEST;

        log.error("Gateway error [retryable={}, code={}, gateway={}]: {}", 
                e.isRetryable(), e.getErrorCode(), e.getGatewayName(), e.getMessage(), e);

        ErrorResponse error = ErrorResponse.builder()
                .error(ErrorResponse.ErrorDetail.builder()
                        .code("GATEWAY_ERROR")
                        .message(e.getMessage())
                        .retryable(e.isRetryable())
                        .traceId(traceId)
                        .errorCode(e.getErrorCode())
                        .gatewayName(e.getGatewayName())
                        .details(e.getCause() != null ? e.getCause().getMessage() : null)
                        .build())
                .build();

        return ResponseEntity.status(status).body(error);
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePaymentNotFoundException(PaymentNotFoundException e) {
        String traceId = getOrCreateTraceId();
        log.warn("Payment not found: {}", e.getPaymentId());

        ErrorResponse error = ErrorResponse.builder()
                .error(ErrorResponse.ErrorDetail.builder()
                        .code("PAYMENT_NOT_FOUND")
                        .message(e.getMessage())
                        .retryable(false)
                        .traceId(traceId)
                        .details("Payment ID: " + e.getPaymentId())
                        .build())
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOrderNotFoundException(OrderNotFoundException e) {
        String traceId = getOrCreateTraceId();
        log.warn("Order not found: {}", e.getOrderId());

        ErrorResponse error = ErrorResponse.builder()
                .error(ErrorResponse.ErrorDetail.builder()
                        .code("ORDER_NOT_FOUND")
                        .message(e.getMessage())
                        .retryable(false)
                        .traceId(traceId)
                        .details("Order ID: " + e.getOrderId())
                        .build())
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(TransactionException.class)
    public ResponseEntity<ErrorResponse> handleTransactionException(TransactionException e) {
        String traceId = getOrCreateTraceId();
        log.error("Transaction error [code={}]: {}", e.getErrorCode(), e.getMessage(), e);

        ErrorResponse error = ErrorResponse.builder()
                .error(ErrorResponse.ErrorDetail.builder()
                        .code("TRANSACTION_ERROR")
                        .message(e.getMessage())
                        .retryable(false)
                        .traceId(traceId)
                        .errorCode(e.getErrorCode())
                        .details("Transaction ID: " + e.getTransactionId())
                        .build())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(WebhookProcessingException.class)
    public ResponseEntity<ErrorResponse> handleWebhookProcessingException(WebhookProcessingException e) {
        String traceId = getOrCreateTraceId();
        HttpStatus status = e.isRetryable() 
                ? HttpStatus.SERVICE_UNAVAILABLE 
                : HttpStatus.BAD_REQUEST;

        log.error("Webhook processing error [retryable={}, webhookId={}, eventId={}]: {}", 
                e.isRetryable(), e.getWebhookId(), e.getGatewayEventId(), e.getMessage(), e);

        ErrorResponse error = ErrorResponse.builder()
                .error(ErrorResponse.ErrorDetail.builder()
                        .code("WEBHOOK_PROCESSING_ERROR")
                        .message(e.getMessage())
                        .retryable(e.isRetryable())
                        .traceId(traceId)
                        .details("Webhook ID: " + e.getWebhookId() + ", Event ID: " + e.getGatewayEventId())
                        .build())
                .build();

        return ResponseEntity.status(status).body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException e) {
        String traceId = getOrCreateTraceId();
        log.warn("Illegal argument: {}", e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
                .error(ErrorResponse.ErrorDetail.builder()
                        .code("INVALID_REQUEST")
                        .message(e.getMessage())
                        .retryable(false)
                        .traceId(traceId)
                        .build())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalStateException(IllegalStateException e) {
        String traceId = getOrCreateTraceId();
        log.warn("Illegal state: {}", e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
                .error(ErrorResponse.ErrorDetail.builder()
                        .code("INVALID_STATE")
                        .message(e.getMessage())
                        .retryable(false)
                        .traceId(traceId)
                        .build())
                .build();

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception e) {
        String traceId = getOrCreateTraceId();
        log.error("Unexpected error [traceId={}]", traceId, e);

        ErrorResponse error = ErrorResponse.builder()
                .error(ErrorResponse.ErrorDetail.builder()
                        .code("INTERNAL_ERROR")
                        .message("An unexpected error occurred")
                        .retryable(true)
                        .traceId(traceId)
                        .details(e.getClass().getName() + ": " + e.getMessage())
                        .build())
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void handleStaticNotFound() {
        // IMPORTANT: do nothing
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(AuthenticationException e) {
        String traceId = getOrCreateTraceId();
        log.warn("Authentication failed: {}", e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
                .error(ErrorResponse.ErrorDetail.builder()
                        .code("UNAUTHORIZED")
                        .message("Authentication required. Please provide a valid OAuth2 token.")
                        .retryable(false)
                        .traceId(traceId)
                        .details(e.getMessage())
                        .build())
                .build();

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    @ExceptionHandler(InvalidBearerTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBearerTokenException(InvalidBearerTokenException e) {
        String traceId = getOrCreateTraceId();
        log.warn("Invalid bearer token: {}", e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
                .error(ErrorResponse.ErrorDetail.builder()
                        .code("INVALID_TOKEN")
                        .message("Invalid or expired OAuth2 token")
                        .retryable(false)
                        .traceId(traceId)
                        .details(e.getMessage())
                        .build())
                .build();

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    @ExceptionHandler(JwtException.class)
    public ResponseEntity<ErrorResponse> handleJwtException(JwtException e) {
        String traceId = getOrCreateTraceId();
        log.warn("JWT validation failed: {}", e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
                .error(ErrorResponse.ErrorDetail.builder()
                        .code("INVALID_TOKEN")
                        .message("JWT token validation failed")
                        .retryable(false)
                        .traceId(traceId)
                        .details(e.getMessage())
                        .build())
                .build();

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException e) {
        String traceId = getOrCreateTraceId();
        log.warn("Access denied: {}", e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
                .error(ErrorResponse.ErrorDetail.builder()
                        .code("FORBIDDEN")
                        .message("Access denied. Insufficient permissions.")
                        .retryable(false)
                        .traceId(traceId)
                        .details(e.getMessage())
                        .build())
                .build();

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationCredentialsNotFoundException(AuthenticationCredentialsNotFoundException e) {
        String traceId = getOrCreateTraceId();
        log.warn("Authentication credentials not found: {}", e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
                .error(ErrorResponse.ErrorDetail.builder()
                        .code("UNAUTHORIZED")
                        .message("Authentication credentials not found")
                        .retryable(false)
                        .traceId(traceId)
                        .details(e.getMessage())
                        .build())
                .build();

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    private String getOrCreateTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString();
            MDC.put("traceId", traceId);
        }
        return traceId;
    }
}

