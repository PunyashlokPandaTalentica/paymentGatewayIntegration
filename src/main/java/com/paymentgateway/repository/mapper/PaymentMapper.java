package com.paymentgateway.repository.mapper;

import com.paymentgateway.domain.entity.PaymentEntity;
import com.paymentgateway.domain.model.Payment;
import com.paymentgateway.domain.valueobject.Money;
import org.springframework.stereotype.Component;

@Component
public class PaymentMapper {

    public PaymentEntity toEntity(Payment payment) {
        return PaymentEntity.builder()
                .id(payment.getId())
                .orderId(payment.getOrderId())
                .paymentType(payment.getPaymentType())
                .status(payment.getStatus())
                .amountCents(payment.getAmount().getAmountCents())
                .currency(payment.getAmount().getCurrencyCode())
                .gateway(payment.getGateway())
                .idempotencyKey(payment.getIdempotencyKey())
                .createdAt(payment.getCreatedAt())
                .updatedAt(payment.getUpdatedAt())
                .build();
    }

    public Payment toDomain(PaymentEntity entity) {
        Money amount = Money.fromCents(entity.getAmountCents(), entity.getCurrency());

        return Payment.builder()
                .id(entity.getId())
                .orderId(entity.getOrderId())
                .paymentType(entity.getPaymentType())
                .status(entity.getStatus())
                .amount(amount)
                .gateway(entity.getGateway())
                .idempotencyKey(entity.getIdempotencyKey())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}




