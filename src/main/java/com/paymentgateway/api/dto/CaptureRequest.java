package com.paymentgateway.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.paymentgateway.domain.valueobject.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Value;

@Value
@Schema(description = "Request to capture an authorized transaction")
public class CaptureRequest {
    @NotNull
    @Valid
    @JsonProperty("amount")
    @Schema(description = "Amount to capture. Must be less than or equal to the authorized amount. Supports partial captures.", requiredMode = Schema.RequiredMode.REQUIRED)
    Money amount;
    
    @JsonCreator
    public CaptureRequest(@JsonProperty("amount") Money amount) {
        this.amount = amount;
    }
}

