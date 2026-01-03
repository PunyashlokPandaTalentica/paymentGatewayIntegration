package com.paymentgateway.domain.model;

import com.paymentgateway.domain.enums.Gateway;
import com.paymentgateway.domain.enums.PaymentStatus;
import com.paymentgateway.domain.enums.PaymentType;
import com.paymentgateway.domain.valueobject.Money;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class Payment {
    UUID id;
    UUID orderId;
    PaymentType paymentType;
    PaymentStatus status;
    Money amount;
    Gateway gateway;
    String idempotencyKey;
    Instant createdAt;
    Instant updatedAt;

    public Payment withStatus(PaymentStatus newStatus) {
        return Payment.builder()
                .id(this.id)
                .orderId(this.orderId)
                .paymentType(this.paymentType)
                .status(newStatus)
                .amount(this.amount)
                .gateway(this.gateway)
                .idempotencyKey(this.idempotencyKey)
                .createdAt(this.createdAt)
                .updatedAt(Instant.now())
                .build();
    }
}

