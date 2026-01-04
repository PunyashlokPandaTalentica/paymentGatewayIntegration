package com.paymentgateway.repository.mapper;

import com.paymentgateway.domain.entity.SubscriptionPaymentEntity;
import com.paymentgateway.domain.model.SubscriptionPayment;
import com.paymentgateway.domain.valueobject.Money;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class SubscriptionPaymentMapper {

    public SubscriptionPaymentEntity toEntity(SubscriptionPayment subscriptionPayment) {
        return SubscriptionPaymentEntity.builder()
                .id(subscriptionPayment.getId())
                .subscriptionId(subscriptionPayment.getSubscriptionId())
                .paymentId(subscriptionPayment.getPaymentId())
                .orderId(subscriptionPayment.getOrderId())
                .billingCycle(subscriptionPayment.getBillingCycle())
                .amountCents(subscriptionPayment.getAmount().getAmountCents())
                .currency(subscriptionPayment.getAmount().getCurrencyCode())
                .scheduledDate(subscriptionPayment.getScheduledDate())
                .processedAt(subscriptionPayment.getProcessedAt())
                .createdAt(subscriptionPayment.getCreatedAt())
                .build();
    }

    public SubscriptionPayment toDomain(SubscriptionPaymentEntity entity) {
        return SubscriptionPayment.builder()
                .id(entity.getId())
                .subscriptionId(entity.getSubscriptionId())
                .paymentId(entity.getPaymentId())
                .orderId(entity.getOrderId())
                .billingCycle(entity.getBillingCycle())
                .amount(Money.fromCents(entity.getAmountCents(), entity.getCurrency()))
                .scheduledDate(entity.getScheduledDate())
                .processedAt(entity.getProcessedAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public List<SubscriptionPayment> toDomainList(List<SubscriptionPaymentEntity> entities) {
        return entities.stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }
}

