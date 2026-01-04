package com.paymentgateway.domain.entity;

import com.paymentgateway.domain.enums.Gateway;
import com.paymentgateway.domain.enums.RecurrenceInterval;
import com.paymentgateway.domain.enums.SubscriptionStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "subscriptions", indexes = {
    @Index(name = "idx_subscriptions_customer_id", columnList = "customer_id"),
    @Index(name = "idx_subscriptions_merchant_id", columnList = "merchant_subscription_id"),
    @Index(name = "idx_subscription_idempotency", columnList = "idempotency_key", unique = true),
    @Index(name = "idx_subscriptions_next_billing_date", columnList = "next_billing_date"),
    @Index(name = "idx_subscriptions_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionEntity {
    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "customer_id", nullable = false, columnDefinition = "UUID")
    private UUID customerId;

    @Column(name = "merchant_subscription_id", unique = true, nullable = false, length = 100)
    private String merchantSubscriptionId;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence_interval", nullable = false, length = 20)
    private RecurrenceInterval recurrenceInterval;

    @Column(name = "interval_count", nullable = false)
    private Integer intervalCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private SubscriptionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "gateway", nullable = false, length = 30)
    private Gateway gateway;

    @Column(name = "payment_method_token", nullable = false, length = 255)
    private String paymentMethodToken;

    @Column(name = "start_date", nullable = false)
    private Instant startDate;

    @Column(name = "next_billing_date", nullable = false)
    private Instant nextBillingDate;

    @Column(name = "end_date")
    private Instant endDate;

    @Column(name = "max_billing_cycles")
    private Integer maxBillingCycles;

    @Column(name = "current_billing_cycle")
    private Integer currentBillingCycle;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "idempotency_key", unique = true, nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

