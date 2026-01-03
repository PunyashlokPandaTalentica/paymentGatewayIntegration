package com.paymentgateway.api.exception;

import java.util.UUID;

/**
 * Exception thrown when a transaction operation fails or is invalid.
 */
public class TransactionException extends RuntimeException {
    private final UUID transactionId;
    private final String errorCode;

    public TransactionException(String message, UUID transactionId, String errorCode) {
        super(message);
        this.transactionId = transactionId;
        this.errorCode = errorCode;
    }

    public TransactionException(String message, UUID transactionId, String errorCode, Throwable cause) {
        super(message, cause);
        this.transactionId = transactionId;
        this.errorCode = errorCode;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

