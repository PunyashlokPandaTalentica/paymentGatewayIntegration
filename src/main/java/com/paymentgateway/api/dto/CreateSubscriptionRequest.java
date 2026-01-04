package com.paymentgateway.api.dto;

import com.paymentgateway.domain.enums.Gateway;
import com.paymentgateway.domain.enums.RecurrenceInterval;
import com.paymentgateway.domain.valueobject.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Schema(description = "Request to create a subscription")
public class CreateSubscriptionRequest {
    @NotNull
    @Schema(description = "Customer ID", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
    UUID customerId;

    @NotBlank
    @Schema(description = "Merchant's unique subscription identifier", example = "SUB-12345", required = true)
    String merchantSubscriptionId;

    @NotNull
    @Schema(description = "Recurring payment amount", required = true)
    Money amount;

    @NotNull
    @Schema(description = "Recurrence interval", example = "MONTHLY", required = true)
    RecurrenceInterval interval;

    @Schema(description = "Number of intervals (e.g., every 2 months)", example = "1")
    Integer intervalCount;

    @NotBlank
    @Schema(description = "Tokenized payment method for recurring charges", example = "tok_visa_4242", required = true)
    String paymentMethodToken;

    @Schema(description = "Payment gateway", example = "AUTHORIZE_NET")
    Gateway gateway;

    @Schema(description = "Subscription description", example = "Monthly premium subscription")
    String description;

    @Schema(description = "Subscription start date (defaults to now)")
    Instant startDate;

    @Schema(description = "Subscription end date (optional, for fixed-term subscriptions)")
    Instant endDate;

    @Schema(description = "Maximum number of billing cycles (optional, for limited subscriptions)")
    Integer maxBillingCycles;
}

