package com.paymentgateway.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.paymentgateway.domain.enums.Gateway;
import com.paymentgateway.domain.enums.PaymentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Value;

@Value
@Schema(description = "Request to create a payment for an order")
public class CreatePaymentRequest {
    @NotBlank
    @JsonProperty("method")
    @Schema(description = "Payment method identifier", requiredMode = Schema.RequiredMode.REQUIRED, example = "credit_card")
    String method;

    @NotNull
    @JsonProperty("flow")
    @Schema(description = "Payment flow type - PURCHASE (authorize and capture) or AUTHORIZE (authorize only)", requiredMode = Schema.RequiredMode.REQUIRED, example = "PURCHASE")
    PaymentType flow;

    @NotNull
    @JsonProperty("gateway")
    @Schema(description = "Payment gateway to use for processing", requiredMode = Schema.RequiredMode.REQUIRED, example = "AUTHORIZE_NET")
    Gateway gateway;
    
    @JsonCreator
    public CreatePaymentRequest(
            @JsonProperty("method") String method,
            @JsonProperty("flow") PaymentType flow,
            @JsonProperty("gateway") Gateway gateway) {
        this.method = method;
        this.flow = flow;
        this.gateway = gateway;
    }
}

