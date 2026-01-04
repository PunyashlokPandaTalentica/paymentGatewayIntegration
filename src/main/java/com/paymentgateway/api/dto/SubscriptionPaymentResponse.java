package com.paymentgateway.api.dto;

import com.paymentgateway.domain.valueobject.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
@Schema(description = "Subscription payment response")
public class SubscriptionPaymentResponse {
    @Schema(description = "Subscription payment ID", example = "550e8400-e29b-41d4-a716-446655440000")
    UUID id;

    @Schema(description = "Subscription ID", example = "550e8400-e29b-41d4-a716-446655440000")
    UUID subscriptionId;

    @Schema(description = "Payment ID", example = "550e8400-e29b-41d4-a716-446655440000")
    UUID paymentId;

    @Schema(description = "Order ID", example = "550e8400-e29b-41d4-a716-446655440000")
    UUID orderId;

    @Schema(description = "Billing cycle number", example = "1")
    Integer billingCycle;

    @Schema(description = "Payment amount")
    Money amount;

    @Schema(description = "Scheduled date", example = "2025-02-01T00:00:00Z")
    Instant scheduledDate;

    @Schema(description = "Processed timestamp", example = "2025-02-01T00:05:00Z")
    Instant processedAt;

    @Schema(description = "Creation timestamp", example = "2025-01-30T18:42:21Z")
    Instant createdAt;
}

