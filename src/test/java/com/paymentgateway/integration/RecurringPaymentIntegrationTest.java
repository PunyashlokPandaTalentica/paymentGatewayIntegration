package com.paymentgateway.integration;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.gateway.RetryableGatewayService;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.OrderRepository;
import com.paymentgateway.repository.PaymentRepository;
import com.paymentgateway.repository.SubscriptionPaymentRepository;
import com.paymentgateway.repository.SubscriptionRepository;
import com.paymentgateway.service.PaymentOrchestratorService;
import com.paymentgateway.service.RecurringPaymentService;
import com.paymentgateway.service.SubscriptionService;
import com.paymentgateway.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for recurring payment processing.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RecurringPaymentIntegrationTest {

    @Autowired
    private RecurringPaymentService recurringPaymentService;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private SubscriptionPaymentRepository subscriptionPaymentRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @MockBean
    private RetryableGatewayService retryableGatewayService;

    private String testCustomerId;
    private Money testAmount;
    private String testPaymentMethodToken;

    @BeforeEach
    void setUp() {
        testCustomerId = UUID.randomUUID().toString();
        testAmount = new Money(BigDecimal.valueOf(29.99), Currency.getInstance("USD"));
        testPaymentMethodToken = "tok_visa_4242";

        // Reset mocks
        reset(retryableGatewayService);
    }

    @Test
    void testProcessSubscriptionBilling_Success() {
        // Mock gateway response
        PurchaseResponse mockResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway_txn_" + UUID.randomUUID())
                .responseCode("1")
                .responseMessage("Transaction approved")
                .authCode("AUTH123")
                .build();

        when(retryableGatewayService.purchase(any())).thenReturn(mockResponse);

        // Create subscription
        String merchantSubscriptionId = "SUB-BILLING-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription subscription = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Billing test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        // Process billing
        SubscriptionPayment payment = recurringPaymentService.processSubscriptionBilling(subscription);

        assertNotNull(payment);
        assertEquals(subscription.getId(), payment.getSubscriptionId());
        assertEquals(1, payment.getBillingCycle());
        assertNotNull(payment.getPaymentId());
        assertNotNull(payment.getOrderId());
        assertNotNull(payment.getProcessedAt());

        // Verify order was created
        assertTrue(orderRepository.findById(payment.getOrderId()).isPresent());

        // Verify payment was created
        assertTrue(paymentRepository.findById(payment.getPaymentId()).isPresent());

        // Verify subscription payment was saved
        assertTrue(subscriptionPaymentRepository.findById(payment.getId()).isPresent());

        // Verify subscription was updated
        Subscription updated = subscriptionService.getSubscriptionById(subscription.getId());
        assertEquals(1, updated.getCurrentBillingCycle());
        assertTrue(updated.getNextBillingDate().isAfter(subscription.getNextBillingDate()));
    }

    @Test
    void testProcessSubscriptionBilling_MaxBillingCycles() {
        // Mock gateway response
        PurchaseResponse mockResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway_txn_" + UUID.randomUUID())
                .responseCode("1")
                .responseMessage("Transaction approved")
                .authCode("AUTH123")
                .build();

        when(retryableGatewayService.purchase(any())).thenReturn(mockResponse);

        // Create subscription with max billing cycles
        String merchantSubscriptionId = "SUB-MAX-CYCLES-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription subscription = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Max cycles test subscription",
                idempotencyKey,
                null,
                null,
                2 // Max 2 billing cycles
        );

        // Process first billing
        SubscriptionPayment payment1 = recurringPaymentService.processSubscriptionBilling(subscription);
        assertEquals(1, payment1.getBillingCycle());

        // Process second billing
        Subscription updated = subscriptionService.getSubscriptionById(subscription.getId());
        SubscriptionPayment payment2 = recurringPaymentService.processSubscriptionBilling(updated);
        assertEquals(2, payment2.getBillingCycle());

        // Try to process third billing - should fail
        Subscription updated2 = subscriptionService.getSubscriptionById(subscription.getId());
        assertThrows(IllegalStateException.class, () ->
                recurringPaymentService.processSubscriptionBilling(updated2)
        );

        // Verify subscription was cancelled
        Subscription finalSubscription = subscriptionService.getSubscriptionById(subscription.getId());
        assertEquals(SubscriptionStatus.CANCELLED, finalSubscription.getStatus());
    }

    @Test
    void testProcessSubscriptionBilling_Expired() {
        // Create subscription with end date in the past
        String merchantSubscriptionId = "SUB-EXPIRED-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription subscription = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Expired test subscription",
                idempotencyKey,
                null,
                Instant.now().minusSeconds(86400), // End date in the past
                null
        );

        // Try to process billing - should fail
        assertThrows(IllegalStateException.class, () ->
                recurringPaymentService.processSubscriptionBilling(subscription)
        );

        // Verify subscription was marked as expired
        Subscription expired = subscriptionService.getSubscriptionById(subscription.getId());
        assertEquals(SubscriptionStatus.EXPIRED, expired.getStatus());
    }

    @Test
    void testProcessSubscriptionBilling_NotActive() {
        String merchantSubscriptionId = "SUB-INACTIVE-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription subscription = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Inactive test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        // Cancel subscription
        subscriptionService.cancelSubscription(subscription.getId());

        // Try to process billing - should fail
        Subscription cancelled = subscriptionService.getSubscriptionById(subscription.getId());
        assertThrows(IllegalStateException.class, () ->
                recurringPaymentService.processSubscriptionBilling(cancelled)
        );
    }

    @Test
    void testTriggerBilling_Manual() {
        // Mock gateway response
        PurchaseResponse mockResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway_txn_" + UUID.randomUUID())
                .responseCode("1")
                .responseMessage("Transaction approved")
                .authCode("AUTH123")
                .build();

        when(retryableGatewayService.purchase(any())).thenReturn(mockResponse);

        String merchantSubscriptionId = "SUB-MANUAL-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription subscription = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Manual billing test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        // Trigger billing manually
        SubscriptionPayment payment = recurringPaymentService.triggerBilling(subscription.getId());

        assertNotNull(payment);
        assertEquals(1, payment.getBillingCycle());
        assertNotNull(payment.getPaymentId());
    }

    @Test
    void testTriggerBilling_NotActive() {
        String merchantSubscriptionId = "SUB-TRIGGER-INACTIVE-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription subscription = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Inactive trigger test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        // Pause subscription
        subscriptionService.pauseSubscription(subscription.getId());

        // Try to trigger billing - should fail
        assertThrows(IllegalStateException.class, () ->
                recurringPaymentService.triggerBilling(subscription.getId())
        );
    }

    @Test
    void testMultipleBillingCycles() {
        // Mock gateway response
        PurchaseResponse mockResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway_txn_" + UUID.randomUUID())
                .responseCode("1")
                .responseMessage("Transaction approved")
                .authCode("AUTH123")
                .build();

        when(retryableGatewayService.purchase(any())).thenReturn(mockResponse);

        String merchantSubscriptionId = "SUB-MULTIPLE-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription subscription = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Multiple cycles test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        // Process multiple billing cycles
        for (int i = 1; i <= 3; i++) {
            Subscription current = subscriptionService.getSubscriptionById(subscription.getId());
            SubscriptionPayment payment = recurringPaymentService.processSubscriptionBilling(current);

            assertEquals(i, payment.getBillingCycle());
            assertEquals(i, current.getCurrentBillingCycle() + 1); // +1 because it's incremented after processing
        }

        // Verify all subscription payments were created
        List<com.paymentgateway.domain.entity.SubscriptionPaymentEntity> payments =
                subscriptionPaymentRepository.findBySubscriptionId(subscription.getId());
        assertEquals(3, payments.size());

        // Verify final subscription state
        Subscription finalSubscription = subscriptionService.getSubscriptionById(subscription.getId());
        assertEquals(3, finalSubscription.getCurrentBillingCycle());
    }

    @Test
    void testBillingCycle_NextBillingDateCalculation() {
        // Mock gateway response
        PurchaseResponse mockResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway_txn_" + UUID.randomUUID())
                .responseCode("1")
                .responseMessage("Transaction approved")
                .authCode("AUTH123")
                .build();

        when(retryableGatewayService.purchase(any())).thenReturn(mockResponse);

        String merchantSubscriptionId = "SUB-DATE-CALC-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Instant startDate = Instant.now();
        Subscription subscription = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Date calculation test subscription",
                idempotencyKey,
                startDate,
                null,
                null
        );

        Instant originalNextBillingDate = subscription.getNextBillingDate();

        // Process billing
        recurringPaymentService.processSubscriptionBilling(subscription);

        // Verify next billing date was updated
        Subscription updated = subscriptionService.getSubscriptionById(subscription.getId());
        assertTrue(updated.getNextBillingDate().isAfter(originalNextBillingDate));
        assertTrue(updated.getNextBillingDate().isAfter(startDate.plusSeconds(2592000))); // Approximately 30 days
    }
}

