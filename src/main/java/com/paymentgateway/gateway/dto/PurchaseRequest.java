package com.paymentgateway.gateway.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.paymentgateway.domain.valueobject.Money;
import lombok.Value;

@Value
public class PurchaseRequest {
    String paymentMethodToken;
    Money amount;
    String orderId;
    String merchantOrderId;
    String description;
    
    @JsonCreator
    public PurchaseRequest(
            @JsonProperty("paymentMethodToken") String paymentMethodToken,
            @JsonProperty("amount") Money amount,
            @JsonProperty("orderId") String orderId,
            @JsonProperty("merchantOrderId") String merchantOrderId,
            @JsonProperty("description") String description) {
        this.paymentMethodToken = paymentMethodToken;
        this.amount = amount;
        this.orderId = orderId;
        this.merchantOrderId = merchantOrderId;
        this.description = description;
    }
}

