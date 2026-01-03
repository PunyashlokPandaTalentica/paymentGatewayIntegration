package com.paymentgateway.api.exception;

import lombok.Getter;

/**
 * Exception thrown when a payment gateway operation fails.
 * This exception indicates a transient or permanent failure from the gateway.
 */
@Getter
public class GatewayException extends RuntimeException {
    private final String errorCode;
    private final boolean retryable;
    private final String gatewayName;

    public GatewayException(String message, String errorCode, boolean retryable, String gatewayName) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.gatewayName = gatewayName;
    }

    public GatewayException(String message, String errorCode, boolean retryable, String gatewayName, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.gatewayName = gatewayName;
    }

    /**
     * Creates a retryable gateway exception (e.g., network timeout, temporary service unavailable)
     */
    public static GatewayException retryable(String message, String errorCode, String gatewayName) {
        return new GatewayException(message, errorCode, true, gatewayName);
    }

    /**
     * Creates a retryable gateway exception with cause
     */
    public static GatewayException retryable(String message, String errorCode, String gatewayName, Throwable cause) {
        return new GatewayException(message, errorCode, true, gatewayName, cause);
    }

    /**
     * Creates a non-retryable gateway exception (e.g., invalid credentials, declined payment)
     */
    public static GatewayException nonRetryable(String message, String errorCode, String gatewayName) {
        return new GatewayException(message, errorCode, false, gatewayName);
    }

    /**
     * Creates a non-retryable gateway exception with cause
     */
    public static GatewayException nonRetryable(String message, String errorCode, String gatewayName, Throwable cause) {
        return new GatewayException(message, errorCode, false, gatewayName, cause);
    }
}

