package com.paymentgateway.domain.model;

import com.paymentgateway.domain.enums.TransactionState;
import com.paymentgateway.domain.enums.TransactionType;
import com.paymentgateway.domain.valueobject.Money;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class PaymentTransaction {
    UUID id;
    UUID paymentId;
    TransactionType transactionType;
    TransactionState transactionState;
    String gatewayTransactionId;
    String gatewayResponseCode;
    String gatewayResponseMsg;
    Money amount;
    UUID parentTransactionId;
    UUID traceId;
    Instant createdAt;

    public PaymentTransaction withState(TransactionState newState) {
        return PaymentTransaction.builder()
                .id(this.id)
                .paymentId(this.paymentId)
                .transactionType(this.transactionType)
                .transactionState(newState)
                .gatewayTransactionId(this.gatewayTransactionId)
                .gatewayResponseCode(this.gatewayResponseCode)
                .gatewayResponseMsg(this.gatewayResponseMsg)
                .amount(this.amount)
                .parentTransactionId(this.parentTransactionId)
                .traceId(this.traceId)
                .createdAt(this.createdAt)
                .build();
    }

    public PaymentTransaction withGatewayResponse(String gatewayTransactionId, String responseCode, String responseMsg) {
        return PaymentTransaction.builder()
                .id(this.id)
                .paymentId(this.paymentId)
                .transactionType(this.transactionType)
                .transactionState(this.transactionState)
                .gatewayTransactionId(gatewayTransactionId)
                .gatewayResponseCode(responseCode)
                .gatewayResponseMsg(responseMsg)
                .amount(this.amount)
                .parentTransactionId(this.parentTransactionId)
                .traceId(this.traceId)
                .createdAt(this.createdAt)
                .build();
    }
}

