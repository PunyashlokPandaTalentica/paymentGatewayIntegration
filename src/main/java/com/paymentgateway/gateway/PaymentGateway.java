package com.paymentgateway.gateway;

import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;

public interface PaymentGateway {
    PurchaseResponse purchase(PurchaseRequest request);
    AuthResponse authorize(PurchaseRequest request);
    CaptureResponse capture(String transactionId, long amountCents, String currency);
}

