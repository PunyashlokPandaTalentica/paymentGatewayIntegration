package com.paymentgateway.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotBlank;
import lombok.Value;

@Value
@Schema(description = "Request to process a purchase or authorization transaction")
public class PurchaseTransactionRequest {
    @NotBlank
    @JsonProperty("paymentMethodToken")
    @Schema(description = "Tokenized payment method identifier from the payment gateway", requiredMode = Schema.RequiredMode.REQUIRED, example = "token_abc123xyz")
    String paymentMethodToken;

    @JsonCreator
    public PurchaseTransactionRequest(@JsonProperty("paymentMethodToken") String paymentMethodToken) {
        this.paymentMethodToken = paymentMethodToken;
    }
}

