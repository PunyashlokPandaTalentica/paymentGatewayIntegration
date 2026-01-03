package com.paymentgateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.api.dto.CreateOrderRequest;
import com.paymentgateway.domain.enums.OrderStatus;
import com.paymentgateway.domain.model.Customer;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.repository.OrderRepository;
import com.paymentgateway.service.PaymentOrchestratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Currency;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentOrchestratorService orchestratorService;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        // Clean up before each test (handled by @Transactional)
    }

    @Test
    void testCreateOrder_Integration() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(
                "ORD-TEST-001",
                new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD")),
                "Test order",
                new Customer("test@example.com", "+1234567890")
        );

        mockMvc.perform(post("/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").exists())
                .andExpect(jsonPath("$.status").value("CREATED"));

        // Verify order was persisted
        assertTrue(orderRepository.findByMerchantOrderId("ORD-TEST-001").isPresent());
    }

    @Test
    void testCreateOrder_DuplicateMerchantOrderId() throws Exception {
        // Create first order
        Order order = Order.builder()
                .id(java.util.UUID.randomUUID())
                .merchantOrderId("ORD-DUP-001")
                .amount(new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD")))
                .description("First order")
                .customer(new Customer("test@example.com", "+1234567890"))
                .status(OrderStatus.CREATED)
                .createdAt(java.time.Instant.now())
                .updatedAt(java.time.Instant.now())
                .build();

        orchestratorService.createOrder(order);

        // Try to create duplicate
        CreateOrderRequest request = new CreateOrderRequest(
                "ORD-DUP-001",
                new Money(BigDecimal.valueOf(200.00), Currency.getInstance("USD")),
                "Duplicate order",
                new Customer("test2@example.com", "+1234567891")
        );

        mockMvc.perform(post("/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testGetOrder_Integration() throws Exception {
        // Create order via service
        Order order = Order.builder()
                .id(java.util.UUID.randomUUID())
                .merchantOrderId("ORD-GET-001")
                .amount(new Money(BigDecimal.valueOf(150.00), Currency.getInstance("USD")))
                .description("Get test order")
                .customer(new Customer("get@example.com", "+1234567892"))
                .status(OrderStatus.CREATED)
                .createdAt(java.time.Instant.now())
                .updatedAt(java.time.Instant.now())
                .build();

        Order created = orchestratorService.createOrder(order);

        // Get order via API
        mockMvc.perform(get("/v1/orders/" + created.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(created.getId().toString()))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }
}

