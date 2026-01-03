package com.paymentgateway.integration;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.Customer;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.domain.model.Payment;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.repository.PaymentRepository;
import com.paymentgateway.service.PaymentOrchestratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PaymentIntegrationTest {

    @Autowired
    private PaymentOrchestratorService orchestratorService;

    @Autowired
    private PaymentRepository paymentRepository;

    private Order testOrder;

    @BeforeEach
    void setUp() {
        testOrder = Order.builder()
                .id(UUID.randomUUID())
                .merchantOrderId("ORD-PAY-TEST-" + UUID.randomUUID())
                .amount(new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD")))
                .description("Payment test order")
                .customer(new Customer("payment@example.com", "+1234567890"))
                .status(OrderStatus.CREATED)
                .createdAt(java.time.Instant.now())
                .updatedAt(java.time.Instant.now())
                .build();

        testOrder = orchestratorService.createOrder(testOrder);
    }

    @Test
    void testCreatePayment_Integration() {
        String idempotencyKey = UUID.randomUUID().toString();

        Payment payment = orchestratorService.createPayment(
                testOrder.getId(),
                PaymentType.PURCHASE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        assertNotNull(payment);
        assertEquals(testOrder.getId(), payment.getOrderId());
        assertEquals(PaymentStatus.INITIATED, payment.getStatus());
        assertEquals(PaymentType.PURCHASE, payment.getPaymentType());

        // Verify persisted
        assertTrue(paymentRepository.findById(payment.getId()).isPresent());
        assertEquals(idempotencyKey, payment.getIdempotencyKey());
    }

    @Test
    void testCreatePayment_Idempotency() {
        String idempotencyKey = UUID.randomUUID().toString();

        Payment payment1 = orchestratorService.createPayment(
                testOrder.getId(),
                PaymentType.PURCHASE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        Payment payment2 = orchestratorService.createPayment(
                testOrder.getId(),
                PaymentType.PURCHASE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        // Should return the same payment
        assertEquals(payment1.getId(), payment2.getId());
    }

    @Test
    void testCreatePayment_OnePaymentPerOrder() {
        String idempotencyKey1 = UUID.randomUUID().toString();
        String idempotencyKey2 = UUID.randomUUID().toString();

        orchestratorService.createPayment(
                testOrder.getId(),
                PaymentType.PURCHASE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey1
        );

        // Second payment for same order should fail
        assertThrows(IllegalStateException.class, () ->
                orchestratorService.createPayment(
                        testOrder.getId(),
                        PaymentType.AUTH_CAPTURE,
                        Gateway.AUTHORIZE_NET,
                        idempotencyKey2
                )
        );
    }
}

