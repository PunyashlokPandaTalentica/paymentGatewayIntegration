package com.paymentgateway.api.exception;

import java.util.UUID;

/**
 * Exception thrown when a payment is not found.
 */
public class PaymentNotFoundException extends RuntimeException {
    private final UUID paymentId;

    public PaymentNotFoundException(UUID paymentId) {
        super("Payment not found: " + paymentId);
        this.paymentId = paymentId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }
}

