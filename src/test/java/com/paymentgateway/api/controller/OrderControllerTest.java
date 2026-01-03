package com.paymentgateway.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.api.dto.CreateOrderRequest;
import com.paymentgateway.domain.enums.OrderStatus;
import com.paymentgateway.domain.model.Customer;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.service.PaymentOrchestratorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaymentOrchestratorService orchestratorService;

    @Test
    void testCreateOrder_Success() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(
                "ORD-123",
                new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD")),
                "Test order",
                new Customer("test@example.com", "+1234567890")
        );

        Order order = Order.builder()
                .id(UUID.randomUUID())
                .merchantOrderId("ORD-123")
                .amount(request.getAmount())
                .description("Test order")
                .customer(request.getCustomer())
                .status(OrderStatus.CREATED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(orchestratorService.createOrder(any(Order.class))).thenReturn(order);

        mockMvc.perform(post("/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").exists())
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void testGetOrder_Success() throws Exception {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .merchantOrderId("ORD-123")
                .amount(new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD")))
                .description("Test order")
                .customer(new Customer("test@example.com", "+1234567890"))
                .status(OrderStatus.CREATED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(orchestratorService.getOrderById(orderId)).thenReturn(order);

        mockMvc.perform(get("/v1/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void testGetOrder_NotFound() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orchestratorService.getOrderById(orderId))
                .thenThrow(new IllegalArgumentException("Order not found: " + orderId));

        mockMvc.perform(get("/v1/orders/" + orderId))
                .andExpect(status().isNotFound());
    }
}

