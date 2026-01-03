package com.paymentgateway.gateway.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CaptureResponse {
    boolean success;
    String gatewayTransactionId;
    String responseCode;
    String responseMessage;
}

