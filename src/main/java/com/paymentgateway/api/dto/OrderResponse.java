package com.paymentgateway.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.paymentgateway.domain.enums.OrderStatus;
import com.paymentgateway.domain.valueobject.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
@Schema(description = "Order information response")
public class OrderResponse {
    @JsonProperty("orderId")
    @Schema(description = "Unique identifier of the order", example = "123e4567-e89b-12d3-a456-426614174000")
    String orderId;

    @JsonProperty("status")
    @Schema(description = "Current status of the order", example = "CREATED")
    OrderStatus status;

    @JsonProperty("amount")
    @Schema(description = "Order amount with currency")
    Money amount;

    @JsonProperty("createdAt")
    @Schema(description = "Timestamp when the order was created")
    Instant createdAt;
}

