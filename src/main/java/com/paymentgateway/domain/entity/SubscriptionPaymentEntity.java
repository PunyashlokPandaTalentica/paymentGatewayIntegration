package com.paymentgateway.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "subscription_payments", indexes = {
    @Index(name = "idx_subscription_payments_subscription_id", columnList = "subscription_id"),
    @Index(name = "idx_subscription_payments_payment_id", columnList = "payment_id"),
    @Index(name = "idx_subscription_payments_order_id", columnList = "order_id"),
    @Index(name = "idx_subscription_payments_billing_cycle", columnList = "subscription_id, billing_cycle")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionPaymentEntity {
    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "subscription_id", nullable = false, columnDefinition = "UUID")
    private UUID subscriptionId;

    @Column(name = "payment_id", nullable = false, columnDefinition = "UUID")
    private UUID paymentId;

    @Column(name = "order_id", nullable = false, columnDefinition = "UUID")
    private UUID orderId;

    @Column(name = "billing_cycle", nullable = false)
    private Integer billingCycle;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "scheduled_date", nullable = false)
    private Instant scheduledDate;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

