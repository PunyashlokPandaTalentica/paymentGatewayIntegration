package com.paymentgateway.domain.enums;

public enum PaymentType {
    PURCHASE,      // Auth + Capture in one step
    AUTH_CAPTURE   // Auth then Capture (2-step)
}

