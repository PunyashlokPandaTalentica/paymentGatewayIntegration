package com.paymentgateway.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.paymentgateway.domain.enums.PaymentStatus;
import com.paymentgateway.domain.enums.PaymentType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Payment information response")
public class PaymentResponse {
    @JsonProperty("paymentId")
    @Schema(description = "Unique identifier of the payment", example = "123e4567-e89b-12d3-a456-426614174000")
    String paymentId;

    @JsonProperty("orderId")
    @Schema(description = "Unique identifier of the associated order", example = "223e4567-e89b-12d3-a456-426614174000")
    String orderId;

    @JsonProperty("status")
    @Schema(description = "Current status of the payment", example = "CREATED")
    PaymentStatus status;

    @JsonProperty("flow")
    @Schema(description = "Payment flow type", example = "PURCHASE")
    PaymentType flow;
}

