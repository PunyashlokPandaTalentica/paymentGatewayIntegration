package com.paymentgateway.domain.model;

import com.paymentgateway.domain.enums.OrderStatus;
import com.paymentgateway.domain.valueobject.Money;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class Order {
    UUID id;
    String merchantOrderId;
    Money amount;
    String description;
    Customer customer;
    OrderStatus status;
    Instant createdAt;
    Instant updatedAt;

    public Order withStatus(OrderStatus newStatus) {
        return Order.builder()
                .id(this.id)
                .merchantOrderId(this.merchantOrderId)
                .amount(this.amount)
                .description(this.description)
                .customer(this.customer)
                .status(newStatus)
                .createdAt(this.createdAt)
                .updatedAt(Instant.now())
                .build();
    }
}

