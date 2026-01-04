package com.paymentgateway.api.dto;

import com.paymentgateway.domain.enums.RecurrenceInterval;
import com.paymentgateway.domain.enums.SubscriptionStatus;
import com.paymentgateway.domain.valueobject.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
@Schema(description = "Subscription response")
public class SubscriptionResponse {
    @Schema(description = "Subscription ID", example = "550e8400-e29b-41d4-a716-446655440000")
    UUID subscriptionId;

    @Schema(description = "Merchant's subscription identifier", example = "SUB-12345")
    String merchantSubscriptionId;

    @Schema(description = "Customer ID", example = "550e8400-e29b-41d4-a716-446655440000")
    UUID customerId;

    @Schema(description = "Recurring payment amount")
    Money amount;

    @Schema(description = "Recurrence interval", example = "MONTHLY")
    RecurrenceInterval interval;

    @Schema(description = "Number of intervals", example = "1")
    Integer intervalCount;

    @Schema(description = "Subscription status", example = "ACTIVE")
    SubscriptionStatus status;

    @Schema(description = "Next billing date", example = "2025-02-01T00:00:00Z")
    Instant nextBillingDate;

    @Schema(description = "Current billing cycle number", example = "3")
    Integer currentBillingCycle;

    @Schema(description = "Subscription creation timestamp", example = "2025-01-30T18:42:21Z")
    Instant createdAt;
}

