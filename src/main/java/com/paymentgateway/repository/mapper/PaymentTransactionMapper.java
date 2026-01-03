package com.paymentgateway.repository.mapper;

import com.paymentgateway.domain.entity.PaymentTransactionEntity;
import com.paymentgateway.domain.model.PaymentTransaction;
import com.paymentgateway.domain.valueobject.Money;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class PaymentTransactionMapper {

    public PaymentTransactionEntity toEntity(PaymentTransaction transaction) {
        return PaymentTransactionEntity.builder()
                .id(transaction.getId())
                .paymentId(transaction.getPaymentId())
                .transactionType(transaction.getTransactionType())
                .transactionState(transaction.getTransactionState())
                .gatewayTransactionId(transaction.getGatewayTransactionId())
                .gatewayResponseCode(transaction.getGatewayResponseCode())
                .gatewayResponseMsg(transaction.getGatewayResponseMsg())
                .amountCents(transaction.getAmount().getAmountCents())
                .currency(transaction.getAmount().getCurrencyCode())
                .parentTransactionId(transaction.getParentTransactionId())
                .traceId(transaction.getTraceId())
                .createdAt(transaction.getCreatedAt())
                .build();
    }

    public PaymentTransaction toDomain(PaymentTransactionEntity entity) {
        Money amount = Money.fromCents(entity.getAmountCents(), entity.getCurrency());

        return PaymentTransaction.builder()
                .id(entity.getId())
                .paymentId(entity.getPaymentId())
                .transactionType(entity.getTransactionType())
                .transactionState(entity.getTransactionState())
                .gatewayTransactionId(entity.getGatewayTransactionId())
                .gatewayResponseCode(entity.getGatewayResponseCode())
                .gatewayResponseMsg(entity.getGatewayResponseMsg())
                .amount(amount)
                .parentTransactionId(entity.getParentTransactionId())
                .traceId(entity.getTraceId())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public List<PaymentTransaction> toDomainList(List<PaymentTransactionEntity> entities) {
        return entities.stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }
}




