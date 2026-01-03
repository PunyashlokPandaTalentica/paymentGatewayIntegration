package com.paymentgateway.domain.entity;

import com.paymentgateway.domain.enums.TransactionState;
import com.paymentgateway.domain.enums.TransactionType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_transactions", indexes = {
    @Index(name = "idx_tx_payment_id", columnList = "payment_id"),
    @Index(name = "idx_tx_gateway_id", columnList = "gateway_transaction_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentTransactionEntity {
    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "payment_id", nullable = false, columnDefinition = "UUID")
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20)
    private TransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_state", nullable = false, length = 30)
    private TransactionState transactionState;

    @Column(name = "gateway_transaction_id", length = 100)
    private String gatewayTransactionId;

    @Column(name = "gateway_response_code", length = 50)
    private String gatewayResponseCode;

    @Column(name = "gateway_response_msg", columnDefinition = "TEXT")
    private String gatewayResponseMsg;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "parent_transaction_id", columnDefinition = "UUID")
    private UUID parentTransactionId;

    @Column(name = "trace_id", nullable = false, columnDefinition = "UUID")
    private UUID traceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

