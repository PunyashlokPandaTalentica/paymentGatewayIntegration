package com.paymentgateway.gateway;

import com.paymentgateway.api.exception.GatewayException;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Wrapper service that adds retry, circuit breaker, and timeout handling
 * to gateway operations for improved resilience.
 */
@Service
@Slf4j
public class RetryableGatewayService {

    private static final String GATEWAY_NAME = "AUTHORIZE_NET";
    private static final String CIRCUIT_BREAKER_NAME = "gatewayCircuitBreaker";
    private static final String RETRY_NAME = "gatewayRetry";

    @Autowired
    private PaymentGateway paymentGateway;

    /**
     * Executes a purchase operation with retry and circuit breaker protection.
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER_NAME, fallbackMethod = "purchaseFallback")
    @Retry(name = RETRY_NAME, fallbackMethod = "purchaseFallback")
    public PurchaseResponse purchase(PurchaseRequest request) {
        String traceId = getOrCreateTraceId();
        MDC.put("traceId", traceId);
        MDC.put("operation", "purchase");
        MDC.put("orderId", request.getOrderId());
        
        try {
            log.info("Executing purchase operation for order: {}", request.getOrderId());
            PurchaseResponse response = paymentGateway.purchase(request);
            
            if (!response.isSuccess()) {
                String errorCode = "GATEWAY_ERROR_" + response.getResponseCode();
                throw GatewayException.nonRetryable(
                    "Purchase failed: " + response.getResponseMessage(),
                    errorCode,
                    GATEWAY_NAME
                );
            }
            
            log.info("Purchase operation completed successfully for order: {}", request.getOrderId());
            return response;
        } catch (GatewayException e) {
            // Re-throw gateway exceptions as-is
            throw e;
        } catch (Exception e) {
            // Wrap unexpected exceptions
            log.error("Unexpected error during purchase operation", e);
            throw GatewayException.retryable(
                "Gateway operation failed: " + e.getMessage(),
                "GATEWAY_OPERATION_ERROR",
                GATEWAY_NAME,
                e
            );
        } finally {
            MDC.clear();
        }
    }

    /**
     * Executes an authorize operation with retry and circuit breaker protection.
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER_NAME, fallbackMethod = "authorizeFallback")
    @Retry(name = RETRY_NAME, fallbackMethod = "authorizeFallback")
    public AuthResponse authorize(PurchaseRequest request) {
        String traceId = getOrCreateTraceId();
        MDC.put("traceId", traceId);
        MDC.put("operation", "authorize");
        MDC.put("orderId", request.getOrderId());
        
        try {
            log.info("Executing authorize operation for order: {}", request.getOrderId());
            AuthResponse response = paymentGateway.authorize(request);
            
            if (!response.isSuccess()) {
                String errorCode = "GATEWAY_ERROR_" + response.getResponseCode();
                throw GatewayException.nonRetryable(
                    "Authorize failed: " + response.getResponseMessage(),
                    errorCode,
                    GATEWAY_NAME
                );
            }
            
            log.info("Authorize operation completed successfully for order: {}", request.getOrderId());
            return response;
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during authorize operation", e);
            throw GatewayException.retryable(
                "Gateway operation failed: " + e.getMessage(),
                "GATEWAY_OPERATION_ERROR",
                GATEWAY_NAME,
                e
            );
        } finally {
            MDC.clear();
        }
    }

    /**
     * Executes a capture operation with retry and circuit breaker protection.
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER_NAME, fallbackMethod = "captureFallback")
    @Retry(name = RETRY_NAME, fallbackMethod = "captureFallback")
    public CaptureResponse capture(String transactionId, long amountCents, String currency) {
        String traceId = getOrCreateTraceId();
        MDC.put("traceId", traceId);
        MDC.put("operation", "capture");
        MDC.put("transactionId", transactionId);
        
        try {
            log.info("Executing capture operation for transaction: {}", transactionId);
            CaptureResponse response = paymentGateway.capture(transactionId, amountCents, currency);
            
            if (!response.isSuccess()) {
                String errorCode = "GATEWAY_ERROR_" + response.getResponseCode();
                throw GatewayException.nonRetryable(
                    "Capture failed: " + response.getResponseMessage(),
                    errorCode,
                    GATEWAY_NAME
                );
            }
            
            log.info("Capture operation completed successfully for transaction: {}", transactionId);
            return response;
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during capture operation", e);
            throw GatewayException.retryable(
                "Gateway operation failed: " + e.getMessage(),
                "GATEWAY_OPERATION_ERROR",
                GATEWAY_NAME,
                e
            );
        } finally {
            MDC.clear();
        }
    }

    // Fallback methods
    private PurchaseResponse purchaseFallback(PurchaseRequest request, Exception e) {
        log.error("Purchase fallback triggered for order: {}", request.getOrderId(), e);
        throw GatewayException.retryable(
            "Gateway circuit breaker open or retry exhausted: " + e.getMessage(),
            "CIRCUIT_BREAKER_OPEN",
            GATEWAY_NAME,
            e
        );
    }

    private AuthResponse authorizeFallback(PurchaseRequest request, Exception e) {
        log.error("Authorize fallback triggered for order: {}", request.getOrderId(), e);
        throw GatewayException.retryable(
            "Gateway circuit breaker open or retry exhausted: " + e.getMessage(),
            "CIRCUIT_BREAKER_OPEN",
            GATEWAY_NAME,
            e
        );
    }

    private CaptureResponse captureFallback(String transactionId, long amountCents, String currency, Exception e) {
        log.error("Capture fallback triggered for transaction: {}", transactionId, e);
        throw GatewayException.retryable(
            "Gateway circuit breaker open or retry exhausted: " + e.getMessage(),
            "CIRCUIT_BREAKER_OPEN",
            GATEWAY_NAME,
            e
        );
    }

    /**
     * Determines if an error is retryable based on response code and message.
     * Transient errors like timeouts, network issues, and temporary service unavailability are retryable.
     */
    private boolean isRetryableError(String responseCode, String responseMessage) {
        if (responseCode == null || responseMessage == null) {
            return true; // Unknown errors are considered retryable
        }

        String messageUpper = responseMessage.toUpperCase();
        
        // Non-retryable errors (permanent failures)
        if (messageUpper.contains("DECLINED") || 
            messageUpper.contains("INVALID") ||
            messageUpper.contains("AUTHENTICATION") ||
            messageUpper.contains("CREDENTIAL") ||
            messageUpper.contains("DUPLICATE")) {
            return false;
        }

        // Retryable errors (transient failures)
        if (messageUpper.contains("TIMEOUT") ||
            messageUpper.contains("UNAVAILABLE") ||
            messageUpper.contains("TEMPORARY") ||
            messageUpper.contains("NETWORK") ||
            messageUpper.contains("CONNECTION")) {
            return true;
        }

        // Default: non-retryable for business logic errors
        return false;
    }

    /**
     * Determines if an exception is retryable.
     */
    private boolean isRetryableException(Exception e) {
        if (e == null) {
            return false;
        }

        String className = e.getClass().getName();
        String message = e.getMessage() != null ? e.getMessage().toUpperCase() : "";

        // Network and timeout exceptions are retryable
        if (e instanceof java.net.SocketTimeoutException ||
            e instanceof java.net.ConnectException ||
            e instanceof java.util.concurrent.TimeoutException ||
            className.contains("Timeout") ||
            className.contains("Connection") ||
            message.contains("TIMEOUT") ||
            message.contains("CONNECTION")) {
            return true;
        }

        // IllegalArgumentException and similar are not retryable
        if (e instanceof IllegalArgumentException) {
            return false;
        }

        // Default: consider retryable for unknown exceptions
        return true;
    }

    private String getOrCreateTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString();
        }
        return traceId;
    }
}

