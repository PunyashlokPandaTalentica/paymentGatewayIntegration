package com.paymentgateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.api.dto.CreateSubscriptionRequest;
import com.paymentgateway.api.dto.SubscriptionResponse;
import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.Subscription;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.RetryableGatewayService;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.SubscriptionPaymentRepository;
import com.paymentgateway.repository.SubscriptionRepository;
import com.paymentgateway.service.RecurringPaymentService;
import com.paymentgateway.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-End integration tests for Subscription REST API endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SubscriptionE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private SubscriptionPaymentRepository subscriptionPaymentRepository;

    @MockBean
    private RetryableGatewayService retryableGatewayService;

    private UUID testCustomerId;
    private Money testAmount;

    @BeforeEach
    void setUp() {
        testCustomerId = UUID.randomUUID();
        testAmount = new Money(BigDecimal.valueOf(29.99), Currency.getInstance("USD"));

        // Reset mocks
        reset(retryableGatewayService);
    }

    @Test
    void testCreateSubscription_API() throws Exception {
        CreateSubscriptionRequest request = new CreateSubscriptionRequest(
                testCustomerId,
                "SUB-API-" + UUID.randomUUID(),
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                "tok_visa_4242",
                Gateway.AUTHORIZE_NET,
                "API test subscription",
                null,
                null,
                null
        );

        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post("/v1/subscriptions")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subscriptionId").exists())
                .andExpect(jsonPath("$.merchantSubscriptionId").value(request.getMerchantSubscriptionId()))
                .andExpect(jsonPath("$.customerId").value(testCustomerId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.interval").value("MONTHLY"))
                .andExpect(jsonPath("$.nextBillingDate").exists());

        // Verify subscription was persisted
        assertTrue(subscriptionRepository.findByMerchantSubscriptionId(request.getMerchantSubscriptionId()).isPresent());
    }

    @Test
    void testCreateSubscription_API_WithoutIdempotencyKey() throws Exception {
        CreateSubscriptionRequest request = new CreateSubscriptionRequest(
                testCustomerId,
                "SUB-NO-IDEMPOTENCY-" + UUID.randomUUID(),
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                "tok_visa_4242",
                Gateway.AUTHORIZE_NET,
                "No idempotency test",
                null,
                null,
                null
        );

        // Should fail without idempotency key
        mockMvc.perform(post("/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testGetSubscription_API() throws Exception {
        // Create subscription via service
        String merchantSubscriptionId = "SUB-GET-API-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription subscription = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                "tok_visa_4242",
                Gateway.AUTHORIZE_NET,
                "Get API test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        // Get subscription via API
        mockMvc.perform(get("/v1/subscriptions/" + subscription.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionId").value(subscription.getId().toString()))
                .andExpect(jsonPath("$.merchantSubscriptionId").value(merchantSubscriptionId))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void testGetSubscription_API_NotFound() throws Exception {
        UUID nonExistentId = UUID.randomUUID();

        mockMvc.perform(get("/v1/subscriptions/" + nonExistentId))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testGetCustomerSubscriptions_API() throws Exception {
        UUID customerId = UUID.randomUUID();

        // Create multiple subscriptions for the customer
        subscriptionService.createSubscription(
                customerId,
                "SUB-CUST-1-" + UUID.randomUUID(),
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                "tok_visa_4242",
                Gateway.AUTHORIZE_NET,
                "Customer subscription 1",
                UUID.randomUUID().toString(),
                null,
                null,
                null
        );

        subscriptionService.createSubscription(
                customerId,
                "SUB-CUST-2-" + UUID.randomUUID(),
                testAmount,
                RecurrenceInterval.WEEKLY,
                1,
                "tok_visa_4242",
                Gateway.AUTHORIZE_NET,
                "Customer subscription 2",
                UUID.randomUUID().toString(),
                null,
                null,
                null
        );

        // Get customer subscriptions via API
        mockMvc.perform(get("/v1/subscriptions/customer/" + customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void testCancelSubscription_API() throws Exception {
        String merchantSubscriptionId = "SUB-CANCEL-API-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription subscription = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                "tok_visa_4242",
                Gateway.AUTHORIZE_NET,
                "Cancel API test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        // Cancel subscription via API
        mockMvc.perform(post("/v1/subscriptions/" + subscription.getId() + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionId").value(subscription.getId().toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Verify status updated in database
        Subscription cancelled = subscriptionService.getSubscriptionById(subscription.getId());
        assertEquals(SubscriptionStatus.CANCELLED, cancelled.getStatus());
    }

    @Test
    void testPauseSubscription_API() throws Exception {
        String merchantSubscriptionId = "SUB-PAUSE-API-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription subscription = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                "tok_visa_4242",
                Gateway.AUTHORIZE_NET,
                "Pause API test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        // Pause subscription via API
        mockMvc.perform(post("/v1/subscriptions/" + subscription.getId() + "/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionId").value(subscription.getId().toString()))
                .andExpect(jsonPath("$.status").value("PAUSED"));
    }

    @Test
    void testResumeSubscription_API() throws Exception {
        String merchantSubscriptionId = "SUB-RESUME-API-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription subscription = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                "tok_visa_4242",
                Gateway.AUTHORIZE_NET,
                "Resume API test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        // Pause first
        subscriptionService.pauseSubscription(subscription.getId());

        // Resume via API
        mockMvc.perform(post("/v1/subscriptions/" + subscription.getId() + "/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionId").value(subscription.getId().toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void testTriggerBilling_API() throws Exception {
        // Mock gateway response
        PurchaseResponse mockResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway_txn_123")
                .responseCode("1")
                .responseMessage("Transaction approved")
                .authCode("AUTH123")
                .build();

        when(retryableGatewayService.purchase(any())).thenReturn(mockResponse);

        String merchantSubscriptionId = "SUB-TRIGGER-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription subscription = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                "tok_visa_4242",
                Gateway.AUTHORIZE_NET,
                "Trigger billing test subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        // Trigger billing via API
        mockMvc.perform(post("/v1/subscriptions/" + subscription.getId() + "/trigger-billing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionId").value(subscription.getId().toString()))
                .andExpect(jsonPath("$.billingCycle").value(1))
                .andExpect(jsonPath("$.paymentId").exists())
                .andExpect(jsonPath("$.orderId").exists());

        // Verify subscription payment was created
        assertEquals(1, subscriptionPaymentRepository.findBySubscriptionId(subscription.getId()).size());

        // Verify subscription billing cycle was incremented
        Subscription updated = subscriptionService.getSubscriptionById(subscription.getId());
        assertEquals(1, updated.getCurrentBillingCycle());
    }

    @Test
    void testTriggerBilling_API_NotActive() throws Exception {
        String merchantSubscriptionId = "SUB-TRIGGER-INACTIVE-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        Subscription subscription = subscriptionService.createSubscription(
                testCustomerId,
                merchantSubscriptionId,
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                "tok_visa_4242",
                Gateway.AUTHORIZE_NET,
                "Inactive subscription",
                idempotencyKey,
                null,
                null,
                null
        );

        // Cancel subscription
        subscriptionService.cancelSubscription(subscription.getId());

        // Cannot trigger billing for cancelled subscription
        mockMvc.perform(post("/v1/subscriptions/" + subscription.getId() + "/trigger-billing"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testCreateSubscription_API_Validation() throws Exception {
        // Missing required fields
        CreateSubscriptionRequest invalidRequest = new CreateSubscriptionRequest(
                null, // Missing customer ID
                null, // Missing merchant subscription ID
                null, // Missing amount
                null, // Missing interval
                null,
                null, // Missing payment method token
                null,
                null,
                null,
                null,
                null
        );

        mockMvc.perform(post("/v1/subscriptions")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testSubscriptionLifecycle_CompleteFlow() throws Exception {
        // Create subscription
        CreateSubscriptionRequest createRequest = new CreateSubscriptionRequest(
                testCustomerId,
                "SUB-LIFECYCLE-" + UUID.randomUUID(),
                testAmount,
                RecurrenceInterval.MONTHLY,
                1,
                "tok_visa_4242",
                Gateway.AUTHORIZE_NET,
                "Lifecycle test subscription",
                null,
                null,
                null
        );

        String idempotencyKey = UUID.randomUUID().toString();
        String response = mockMvc.perform(post("/v1/subscriptions")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        SubscriptionResponse subscriptionResponse = objectMapper.readValue(response, SubscriptionResponse.class);
        UUID subscriptionId = subscriptionResponse.getSubscriptionId();

        // Verify initial state
        assertEquals(SubscriptionStatus.ACTIVE, subscriptionResponse.getStatus());
        assertEquals(0, subscriptionResponse.getCurrentBillingCycle());

        // Pause subscription
        mockMvc.perform(post("/v1/subscriptions/" + subscriptionId + "/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // Resume subscription
        mockMvc.perform(post("/v1/subscriptions/" + subscriptionId + "/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Cancel subscription
        mockMvc.perform(post("/v1/subscriptions/" + subscriptionId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}

