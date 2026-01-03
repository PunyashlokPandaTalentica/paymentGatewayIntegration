package com.paymentgateway.gateway.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PurchaseResponse {
    boolean success;
    String gatewayTransactionId;
    String responseCode;
    String responseMessage;
    String authCode;
}

