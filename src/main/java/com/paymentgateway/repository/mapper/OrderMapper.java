package com.paymentgateway.repository.mapper;

import com.paymentgateway.domain.entity.OrderEntity;
import com.paymentgateway.domain.model.Customer;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.domain.valueobject.Money;
import org.springframework.stereotype.Component;

@Component
public class OrderMapper {

    public OrderEntity toEntity(Order order) {
        return OrderEntity.builder()
                .id(order.getId())
                .merchantOrderId(order.getMerchantOrderId())
                .amountCents(order.getAmount().getAmountCents())
                .currency(order.getAmount().getCurrencyCode())
                .description(order.getDescription())
                .customerEmail(order.getCustomer() != null ? order.getCustomer().getEmail() : null)
                .customerPhone(order.getCustomer() != null ? order.getCustomer().getPhone() : null)
                .status(order.getStatus())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }

    public Order toDomain(OrderEntity entity) {
        Money amount = Money.fromCents(entity.getAmountCents(), entity.getCurrency());
        Customer customer = null;
        if (entity.getCustomerEmail() != null || entity.getCustomerPhone() != null) {
            customer = new Customer(entity.getCustomerEmail(), entity.getCustomerPhone());
        }

        return Order.builder()
                .id(entity.getId())
                .merchantOrderId(entity.getMerchantOrderId())
                .amount(amount)
                .description(entity.getDescription())
                .customer(customer)
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}

