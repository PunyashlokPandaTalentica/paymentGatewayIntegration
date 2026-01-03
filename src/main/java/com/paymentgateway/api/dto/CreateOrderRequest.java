package com.paymentgateway.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.paymentgateway.domain.model.Customer;
import com.paymentgateway.domain.valueobject.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Value;

@Value
@Schema(description = "Request to create a new order")
public class CreateOrderRequest {
    @NotBlank
    @JsonProperty("merchantOrderId")
    @Schema(description = "Merchant's unique identifier for this order", requiredMode = Schema.RequiredMode.REQUIRED, example = "ORD-12345")
    String merchantOrderId;

    @NotNull
    @Valid
    @JsonProperty("amount")
    @Schema(description = "Order amount with currency", requiredMode = Schema.RequiredMode.REQUIRED)
    Money amount;

    @JsonProperty("description")
    @Schema(description = "Optional description of the order", example = "Purchase of goods and services")
    String description;

    @Valid
    @JsonProperty("customer")
    @Schema(description = "Customer information associated with the order")
    Customer customer;
    
    @JsonCreator
    public CreateOrderRequest(
            @JsonProperty("merchantOrderId") String merchantOrderId,
            @JsonProperty("amount") Money amount,
            @JsonProperty("description") String description,
            @JsonProperty("customer") Customer customer) {
        this.merchantOrderId = merchantOrderId;
        this.amount = amount;
        this.description = description;
        this.customer = customer;
    }
}

