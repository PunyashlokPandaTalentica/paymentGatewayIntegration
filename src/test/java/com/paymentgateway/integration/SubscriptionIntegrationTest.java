package com.paymentgateway.integration;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.Subscription;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.repository.SubscriptionPaymentRepository;
import com.paymentgateway.repository.SubscriptionRepository;
import com.paymentgateway.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Subscription service layer.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SubscriptionIntegrationTest {

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    private UUID testCustomerId;
    private Money testAmount;
    private String testPaymentMethodToken;

    @BeforeEach
    void setUp() {
        testCustomerId = UUID.randomUUID();
        testAmount = new Money(BigDecimal.valueOf(29.99), Currency.getInstance("USD"));
        testPaymentMethodToken = "tok_visa_4242";
    }

    @Test
    void testCreateSubscription_Success() {
        String merchantSubscriptionId = "SUB-TEST-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription subscription = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Monthly premium subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        assertNotNull(subscription);
        assertEquals(testCustomerId, subscription.getCustomerId());
        assertEquals(merchantSubscriptionId, subscription.getMerchantSubscriptionId());
        assertEquals(testAmount, subscription.getAmount());
        assertEquals(RecurrenceInterval.MONTHLY, subscription.getInterval());
        assertEquals(1, subscription.getIntervalCount());
        assertEquals(SubscriptionStatus.ACTIVE, subscription.getStatus());
        assertEquals(0, subscription.getCurrentBillingCycle());
        assertNotNull(subscription.getNextBillingDate());
        assertTrue(subscription.getNextBillingDate().isAfter(Instant.now()));

        // Verify persisted
        assertTrue(subscriptionRepository.findById(subscription.getId()).isPresent());
        assertEquals(idempotencyKey, subscription.getIdempotencyKey());
    }

    @Test
    void testCreateSubscription_Idempotency() {
        String merchantSubscriptionId = "SUB-IDEMPOTENT-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription subscription1 = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        Subscription subscription2 = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId + "-different",
                testAmount,
                RecurrenceInterval.WEEKLY,
                2,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Different subscription",
                idempotencyKey, // Same idempotency key
                null,
                null,
                null
        );

        // Should return the same subscription
        assertEquals(subscription1.getId(), subscription2.getId());
        assertEquals(subscription1.getMerchantSubscriptionId(), subscription2.getMerchantSubscriptionId());
    }

    @Test
    void testCreateSubscription_DuplicateMerchantId() {
        String merchantSubscriptionId = "SUB-DUP-" + UUID.randomUUID();
        String idempotencyKey1 = UUID.randomUUID().toString();
        String idempotencyKey2 = UUID.randomUUID().toString();

        subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "First subscription",
                idempotencyKey1,
                null,
                null,
                null
        );

        // Second subscription with same merchant ID should fail
        assertThrows(IllegalArgumentException.class, () ->
                subscriptionService.createSubscription(
                        testCustomerId,
                        merchantSubscriptionId, // Same merchant ID
                        testAmount,
                        RecurrenceInterval.WEEKLY,
                        1,
                        testPaymentMethodToken,
                        Gateway.AUTHORIZE_NET,
                        "Second subscription",
                        idempotencyKey2,
                        null,
                        null,
                        null
                )
        );
    }

    @Test
    void testCreateSubscription_WithStartDate() {
        String merchantSubscriptionId = "SUB-START-" + UUID.randomUUID();
        Instant startDate = Instant.now().plusSeconds(86400); // Tomorrow
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription subscription = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Future subscription",
                idempotencyKey,
                startDate,
                null,
                null
        );

        assertEquals(startDate, subscription.getStartDate());
        assertTrue(subscription.getNextBillingDate().isAfter(startDate));
    }

    @Test
    void testCreateSubscription_WithMaxBillingCycles() {
        String merchantSubscriptionId = "SUB-MAX-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription subscription = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Limited subscription",
                idempotencyKey,
                null,
                null,
                12 // Max 12 billing cycles
        );

        assertEquals(12, subscription.getMaxBillingCycles());
    }

    @Test
    void testGetSubscriptionById() {
        String merchantSubscriptionId = "SUB-GET-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription created = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Get test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        Subscription retrieved = subscriptionService.getSubscriptionById(created.getId());

        assertNotNull(retrieved);
        assertEquals(created.getId(), retrieved.getId());
        assertEquals(merchantSubscriptionId, retrieved.getMerchantSubscriptionId());
    }

    @Test
    void testGetSubscriptionById_NotFound() {
        UUID nonExistentId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () ->
                subscriptionService.getSubscriptionById(nonExistentId)
        );
    }

    @Test
    void testGetSubscriptionByMerchantId() {
        String merchantSubscriptionId = "SUB-MERCHANT-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Merchant test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        Subscription retrieved = subscriptionService.getSubscriptionByMerchantId(merchantSubscriptionId);

        assertNotNull(retrieved);
        assertEquals(merchantSubscriptionId, retrieved.getMerchantSubscriptionId());
    }

    @Test
    void testGetSubscriptionsByCustomerId() {
        UUID customerId1 = UUID.randomUUID();
        UUID customerId2 = UUID.randomUUID();

        // Create subscriptions for customer 1
        subscriptionService.createSubscription(
                customerId1,
                "SUB-CUST1-1-" + UUID.randomUUID(),
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Customer 1 subscription 1",
                UUID.randomUUID().toString(),
                null,
                null,
                null
        );

        subscriptionService.createSubscription(
                customerId1,
                "SUB-CUST1-2-" + UUID.randomUUID(),
                testAmount,
                RecurrenceInterval.WEEKLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Customer 1 subscription 2",
                UUID.randomUUID().toString(),
                null,
                null,
                null
        );

        // Create subscription for customer 2
        subscriptionService.createSubscription(
                customerId2,
                "SUB-CUST2-1-" + UUID.randomUUID(),
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Customer 2 subscription",
                UUID.randomUUID().toString(),
                null,
                null,
                null
        );

        List<Subscription> customer1Subscriptions = subscriptionService.getSubscriptionsByCustomerId(customerId1);
        List<Subscription> customer2Subscriptions = subscriptionService.getSubscriptionsByCustomerId(customerId2);

        assertEquals(2, customer1Subscriptions.size());
        assertEquals(1, customer2Subscriptions.size());
    }

    @Test
    void testCancelSubscription() {
        String merchantSubscriptionId = "SUB-CANCEL-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription created = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Cancel test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        assertEquals(SubscriptionStatus.ACTIVE, created.getStatus());

        Subscription cancelled = subscriptionService.cancelSubscription(created.getId());

        assertEquals(SubscriptionStatus.CANCELLED, cancelled.getStatus());
        assertEquals(created.getId(), cancelled.getId());
    }

    @Test
    void testCancelSubscription_AlreadyCancelled() {
        String merchantSubscriptionId = "SUB-CANCEL-2-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription created = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Cancel test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        Subscription cancelled1 = subscriptionService.cancelSubscription(created.getId());
        Subscription cancelled2 = subscriptionService.cancelSubscription(created.getId());

        // Should return the same cancelled subscription
        assertEquals(cancelled1.getId(), cancelled2.getId());
        assertEquals(SubscriptionStatus.CANCELLED, cancelled2.getStatus());
    }

    @Test
    void testPauseSubscription() {
        String merchantSubscriptionId = "SUB-PAUSE-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription created = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Pause test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        Subscription paused = subscriptionService.pauseSubscription(created.getId());

        assertEquals(SubscriptionStatus.PAUSED, paused.getStatus());
    }

    @Test
    void testPauseSubscription_NotActive() {
        String merchantSubscriptionId = "SUB-PAUSE-2-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription created = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Pause test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        subscriptionService.cancelSubscription(created.getId());

        // Cannot pause a cancelled subscription
        assertThrows(IllegalStateException.class, () ->
                subscriptionService.pauseSubscription(created.getId())
        );
    }

    @Test
    void testResumeSubscription() {
        String merchantSubscriptionId = "SUB-RESUME-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription created = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Resume test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        Subscription paused = subscriptionService.pauseSubscription(created.getId());
        assertEquals(SubscriptionStatus.PAUSED, paused.getStatus());

        Subscription resumed = subscriptionService.resumeSubscription(created.getId());
        assertEquals(SubscriptionStatus.ACTIVE, resumed.getStatus());
    }

    @Test
    void testResumeSubscription_NotPaused() {
        String merchantSubscriptionId = "SUB-RESUME-2-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription created = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Resume test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        // Cannot resume an active subscription
        assertThrows(IllegalStateException.class, () ->
                subscriptionService.resumeSubscription(created.getId())
        );
    }

    @Test
    void testUpdateNextBillingDate() {
        String merchantSubscriptionId = "SUB-UPDATE-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription created = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Update test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        Instant originalNextBillingDate = created.getNextBillingDate();

        Subscription updated = subscriptionService.updateNextBillingDate(created.getId());

        assertTrue(updated.getNextBillingDate().isAfter(originalNextBillingDate));
        assertEquals(1, updated.getCurrentBillingCycle());
    }

    @Test
    void testCreateSubscription_DifferentIntervals() {
        String idempotencyKey1 = UUID.randomUUID().toString();
        String idempotencyKey2 = UUID.randomUUID().toString();
        String idempotencyKey3 = UUID.randomUUID().toString();
        String idempotencyKey4 = UUID.randomUUID().toString();

        // Daily
        Subscription daily = subscriptionService.createSubscription(
                testCustomerId,
                "SUB-DAILY-" + UUID.randomUUID(),
                testAmount,
                RecurrenceInterval.DAILY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Daily subscription",
                idempotencyKey1,
                null,
                null,
                null
        );
        assertTrue(daily.getNextBillingDate().isAfter(Instant.now()));

        // Weekly
        Subscription weekly = subscriptionService.createSubscription(
                testCustomerId,
                "SUB-WEEKLY-" + UUID.randomUUID(),
                testAmount,
                RecurrenceInterval.WEEKLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Weekly subscription",
                idempotencyKey2,
                null,
                null,
                null
        );
        assertTrue(weekly.getNextBillingDate().isAfter(Instant.now()));

        // Monthly
        Subscription monthly = subscriptionService.createSubscription(
                testCustomerId,
                "SUB-MONTHLY-" + UUID.randomUUID(),
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Monthly subscription",
                idempotencyKey3,
                null,
                null,
                null
        );
        assertTrue(monthly.getNextBillingDate().isAfter(Instant.now()));

        // Yearly
        Subscription yearly = subscriptionService.createSubscription(
                testCustomerId,
                "SUB-YEARLY-" + UUID.randomUUID(),
                testAmount,
                RecurrenceInterval.YEARLY,
                1,
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Yearly subscription",
                idempotencyKey4,
                null,
                null,
                null
        );
        assertTrue(yearly.getNextBillingDate().isAfter(Instant.now()));

        // Verify yearly is further out than monthly
        assertTrue(yearly.getNextBillingDate().isAfter(monthly.getNextBillingDate()));
    }

    @Test
    void testCreateSubscription_WithIntervalCount() {
        String merchantSubscriptionId = "SUB-INTERVAL-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription subscription = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                3, // Every 3 months
                testPaymentMethodToken,
                Gateway.AUTHORIZE_NET,
                "Quarterly subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        assertEquals(3, subscription.getIntervalCount());
    }
}

