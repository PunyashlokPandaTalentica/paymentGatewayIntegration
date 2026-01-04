package com.paymentgateway.domain.model;

import com.paymentgateway.domain.valueobject.Money;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a payment made as part of a subscription billing cycle.
 * Links a subscription to a payment/order.
 */
@Value
@Builder
public class SubscriptionPayment {
    UUID id;
    UUID subscriptionId;
    UUID paymentId;              // Reference to the payment created for this billing cycle
    UUID orderId;               // Reference to the order created for this billing cycle
    Integer billingCycle;        // Which billing cycle this payment is for
    Money amount;
    Instant scheduledDate;       // When this payment was scheduled
    Instant processedAt;        // When this payment was actually processed
    Instant createdAt;
}

