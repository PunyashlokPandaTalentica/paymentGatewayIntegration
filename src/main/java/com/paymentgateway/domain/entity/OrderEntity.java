package com.paymentgateway.domain.entity;

import com.paymentgateway.domain.enums.OrderStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders", indexes = {
    @Index(name = "idx_orders_merchant_id", columnList = "merchant_order_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEntity {
    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "merchant_order_id", unique = true, nullable = false, length = 100)
    private String merchantOrderId;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OrderStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

