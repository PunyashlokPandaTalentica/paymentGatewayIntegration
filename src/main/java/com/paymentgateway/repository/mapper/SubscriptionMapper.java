package com.paymentgateway.repository.mapper;

import com.paymentgateway.domain.entity.SubscriptionEntity;
import com.paymentgateway.domain.model.Subscription;
import com.paymentgateway.domain.valueobject.Money;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class SubscriptionMapper {

    public SubscriptionEntity toEntity(Subscription subscription) {
        return SubscriptionEntity.builder()
                .id(subscription.getId())
                .customerId(subscription.getCustomerId())
                .merchantSubscriptionId(subscription.getMerchantSubscriptionId())
                .amountCents(subscription.getAmount().getAmountCents())
                .currency(subscription.getAmount().getCurrencyCode())
                .recurrenceInterval(subscription.getInterval())
                .intervalCount(subscription.getIntervalCount())
                .status(subscription.getStatus())
                .gateway(subscription.getGateway())
                .paymentMethodToken(subscription.getPaymentMethodToken())
                .startDate(subscription.getStartDate())
                .nextBillingDate(subscription.getNextBillingDate())
                .endDate(subscription.getEndDate())
                .maxBillingCycles(subscription.getMaxBillingCycles())
                .currentBillingCycle(subscription.getCurrentBillingCycle())
                .description(subscription.getDescription())
                .idempotencyKey(subscription.getIdempotencyKey())
                .createdAt(subscription.getCreatedAt())
                .updatedAt(subscription.getUpdatedAt())
                .build();
    }

    public Subscription toDomain(SubscriptionEntity entity) {
        return Subscription.builder()
                .id(entity.getId())
                .customerId(entity.getCustomerId())
                .merchantSubscriptionId(entity.getMerchantSubscriptionId())
                .amount(Money.fromCents(entity.getAmountCents(), entity.getCurrency()))
                .interval(entity.getRecurrenceInterval())
                .intervalCount(entity.getIntervalCount())
                .status(entity.getStatus())
                .gateway(entity.getGateway())
                .paymentMethodToken(entity.getPaymentMethodToken())
                .startDate(entity.getStartDate())
                .nextBillingDate(entity.getNextBillingDate())
                .endDate(entity.getEndDate())
                .maxBillingCycles(entity.getMaxBillingCycles())
                .currentBillingCycle(entity.getCurrentBillingCycle())
                .description(entity.getDescription())
                .idempotencyKey(entity.getIdempotencyKey())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public List<Subscription> toDomainList(List<SubscriptionEntity> entities) {
        return entities.stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }
}

