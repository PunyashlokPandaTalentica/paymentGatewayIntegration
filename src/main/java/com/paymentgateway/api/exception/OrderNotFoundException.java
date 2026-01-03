package com.paymentgateway.api.exception;

import java.util.UUID;

/**
 * Exception thrown when an order is not found.
 */
public class OrderNotFoundException extends RuntimeException {
    private final UUID orderId;

    public OrderNotFoundException(UUID orderId) {
        super("Order not found: " + orderId);
        this.orderId = orderId;
    }

    public UUID getOrderId() {
        return orderId;
    }
}

