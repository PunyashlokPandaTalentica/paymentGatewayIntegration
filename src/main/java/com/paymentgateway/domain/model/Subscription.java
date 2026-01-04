package com.paymentgateway.domain.model;

import com.paymentgateway.domain.enums.Gateway;
import com.paymentgateway.domain.enums.RecurrenceInterval;
import com.paymentgateway.domain.enums.SubscriptionStatus;
import com.paymentgateway.domain.valueobject.Money;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a subscription for recurring payments.
 */
@Value
@Builder
public class Subscription {
    UUID id;
    String customerId;              // Reference to customer (can be email or external ID)
    String merchantSubscriptionId; // Merchant's unique subscription identifier
    Money amount;                  // Recurring payment amount
    RecurrenceInterval interval;   // How often to charge
    Integer intervalCount;         // Number of intervals (e.g., every 2 months)
    SubscriptionStatus status;
    Gateway gateway;
    String customerProfileId;     // Authorize.Net Customer Profile ID for recurring charges
    String paymentProfileId;     // Authorize.Net Customer Payment Profile ID for recurring charges
    Instant startDate;            // When subscription starts
    Instant nextBillingDate;      // Next scheduled billing date
    Instant endDate;              // Optional end date (null for indefinite)
    Integer maxBillingCycles;     // Optional max number of billing cycles (null for unlimited)
    Integer currentBillingCycle;   // Current billing cycle number
    String description;
    String idempotencyKey;        // For idempotent subscription creation
    Instant createdAt;
    Instant updatedAt;

    public Subscription withStatus(SubscriptionStatus newStatus) {
        return Subscription.builder()
                .id(this.id)
                .customerId(this.customerId)
                .merchantSubscriptionId(this.merchantSubscriptionId)
                .amount(this.amount)
                .interval(this.interval)
                .intervalCount(this.intervalCount)
                .status(newStatus)
                .gateway(this.gateway)
                .customerProfileId(this.customerProfileId)
                .paymentProfileId(this.paymentProfileId)
                .startDate(this.startDate)
                .nextBillingDate(this.nextBillingDate)
                .endDate(this.endDate)
                .maxBillingCycles(this.maxBillingCycles)
                .currentBillingCycle(this.currentBillingCycle)
                .description(this.description)
                .idempotencyKey(this.idempotencyKey)
                .createdAt(this.createdAt)
                .updatedAt(Instant.now())
                .build();
    }

    public Subscription withNextBillingDate(Instant nextBillingDate) {
        return Subscription.builder()
                .id(this.id)
                .customerId(this.customerId)
                .merchantSubscriptionId(this.merchantSubscriptionId)
                .amount(this.amount)
                .interval(this.interval)
                .intervalCount(this.intervalCount)
                .status(this.status)
                .gateway(this.gateway)
                .customerProfileId(this.customerProfileId)
                .paymentProfileId(this.paymentProfileId)
                .startDate(this.startDate)
                .nextBillingDate(nextBillingDate)
                .endDate(this.endDate)
                .maxBillingCycles(this.maxBillingCycles)
                .currentBillingCycle(this.currentBillingCycle)
                .description(this.description)
                .idempotencyKey(this.idempotencyKey)
                .createdAt(this.createdAt)
                .updatedAt(Instant.now())
                .build();
    }

    public Subscription incrementBillingCycle() {
        return Subscription.builder()
                .id(this.id)
                .customerId(this.customerId)
                .merchantSubscriptionId(this.merchantSubscriptionId)
                .amount(this.amount)
                .interval(this.interval)
                .intervalCount(this.intervalCount)
                .status(this.status)
                .gateway(this.gateway)
                .customerProfileId(this.customerProfileId)
                .paymentProfileId(this.paymentProfileId)
                .startDate(this.startDate)
                .nextBillingDate(this.nextBillingDate)
                .endDate(this.endDate)
                .maxBillingCycles(this.maxBillingCycles)
                .currentBillingCycle(this.currentBillingCycle != null ? this.currentBillingCycle + 1 : 1)
                .description(this.description)
                .idempotencyKey(this.idempotencyKey)
                .createdAt(this.createdAt)
                .updatedAt(Instant.now())
                .build();
    }
}

