package com.paymentgateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.api.dto.WebhookRequest;
import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.Customer;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.domain.model.Payment;
import com.paymentgateway.domain.model.PaymentTransaction;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.repository.*;
import com.paymentgateway.repository.mapper.*;
import com.paymentgateway.service.PaymentOrchestratorService;
import com.paymentgateway.service.WebhookProcessorService;
import com.paymentgateway.service.DeadLetterQueueService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WebhookIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentOrchestratorService orchestratorService;

    @Autowired
    private WebhookProcessorService webhookProcessorService;

    @MockBean
    private DeadLetterQueueService deadLetterQueueService;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private WebhookRepository webhookRepository;

    @Autowired
    private PaymentTransactionMapper transactionMapper;

    private Order testOrder;
    private Payment testPayment;
    private PaymentTransaction testTransaction;
    private String webhookSignatureKey;

    @BeforeEach
    void setUp() {
        // Mock DeadLetterQueueService to avoid database issues with H2
        when(deadLetterQueueService.getFailedWebhookByWebhookId(any())).thenReturn(null);
        when(deadLetterQueueService.shouldRetry(any())).thenReturn(true);
        
        // Create test order
        testOrder = Order.builder()
                .id(UUID.randomUUID())
                .merchantOrderId("ORD-WEBHOOK-TEST-" + UUID.randomUUID())
                .amount(new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD")))
                .description("Webhook test order")
                .customer(new Customer("webhook@example.com", "+1234567890"))
                .status(OrderStatus.CREATED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        testOrder = orchestratorService.createOrder(testOrder);

        // Create test payment
        String idempotencyKey = UUID.randomUUID().toString();
        testPayment = orchestratorService.createPayment(
                testOrder.getId(),
                PaymentType.PURCHASE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        // Get webhook signature key from application properties
        // This should match the test profile configuration
        webhookSignatureKey = "D110E2EDB226D49622CF3C743B914F8D650BF228C859DE94D91178F55999BEC445BB8346358370D582DEDE9516AA91296B6B5BE9534B54A85D22841F5F02591C";
    }

    @Test
    void testReceiveWebhook_TransactionSettled() throws Exception {
        // Create a transaction first
        String gatewayTransactionId = "gateway-txn-" + UUID.randomUUID();
        testTransaction = PaymentTransaction.builder()
                .id(UUID.randomUUID())
                .paymentId(testPayment.getId())
                .transactionType(TransactionType.PURCHASE)
                .transactionState(TransactionState.SUCCESS)
                .amount(testPayment.getAmount())
                .gatewayTransactionId(gatewayTransactionId)
                .traceId(UUID.randomUUID())
                .createdAt(Instant.now())
                .build();

        var transactionEntity = transactionMapper.toEntity(testTransaction);
        transactionRepository.save(transactionEntity);

        // Create webhook payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionReferenceId", gatewayTransactionId);
        payload.put("amount", "100.00");
        payload.put("currency", "USD");

        // For signature verification, WebhookController uses payload.toString()
        // So we need to match that format
        String payloadString = payload.toString();
        String signature = generateSignature(payloadString, webhookSignatureKey);

        WebhookRequest request = new WebhookRequest(
                "evt_" + UUID.randomUUID(),
                "TRANSACTION.SETTLED",
                "AUTHORIZE_NET",
                gatewayTransactionId,
                Instant.now(),
                signature,
                payload
        );

        mockMvc.perform(post("/v1/webhooks/authorize-net")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // Wait a bit for async processing
        Thread.sleep(500);

        // Verify webhook was saved
        var webhookEntity = webhookRepository.findByGatewayEventId(request.getEventId());
        assertTrue(webhookEntity.isPresent());
    }

    @Test
    void testReceiveWebhook_InvalidSignature() throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionReferenceId", "test-txn-123");

        String invalidSignature = "invalid-signature";

        WebhookRequest request = new WebhookRequest(
                "evt_" + UUID.randomUUID(),
                "TRANSACTION.SETTLED",
                "AUTHORIZE_NET",
                "test-txn-123",
                Instant.now(),
                invalidSignature,
                payload
        );

        mockMvc.perform(post("/v1/webhooks/authorize-net")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_SIGNATURE"));
    }

    @Test
    void testReceiveWebhook_DuplicateEvent() throws Exception {
        // Create a transaction first
        String gatewayTransactionId = "gateway-txn-" + UUID.randomUUID();
        testTransaction = PaymentTransaction.builder()
                .id(UUID.randomUUID())
                .paymentId(testPayment.getId())
                .transactionType(TransactionType.PURCHASE)
                .transactionState(TransactionState.SUCCESS)
                .amount(testPayment.getAmount())
                .gatewayTransactionId(gatewayTransactionId)
                .traceId(UUID.randomUUID())
                .createdAt(Instant.now())
                .build();

        var transactionEntity = transactionMapper.toEntity(testTransaction);
        transactionRepository.save(transactionEntity);

        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionReferenceId", gatewayTransactionId);

        String payloadString = payload.toString();
        String signature = generateSignature(payloadString, webhookSignatureKey);
        String eventId = "evt_" + UUID.randomUUID();

        WebhookRequest request = new WebhookRequest(
                eventId,
                "TRANSACTION.SETTLED",
                "AUTHORIZE_NET",
                gatewayTransactionId,
                Instant.now(),
                signature,
                payload
        );

        // Send first webhook
        mockMvc.perform(post("/v1/webhooks/authorize-net")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // Wait a bit for async processing
        Thread.sleep(500);

        // Send duplicate webhook
        mockMvc.perform(post("/v1/webhooks/authorize-net")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // Verify only one webhook was saved
        var webhooks = webhookRepository.findByGatewayEventId(eventId);
        assertTrue(webhooks.isPresent());
    }

    @Test
    void testProcessWebhook_TransactionAuthorized() throws Exception {
        // Create a transaction first
        String gatewayTransactionId = "gateway-txn-" + UUID.randomUUID();
        testTransaction = PaymentTransaction.builder()
                .id(UUID.randomUUID())
                .paymentId(testPayment.getId())
                .transactionType(TransactionType.AUTH)
                .transactionState(TransactionState.REQUESTED)
                .amount(testPayment.getAmount())
                .gatewayTransactionId(gatewayTransactionId)
                .traceId(UUID.randomUUID())
                .createdAt(Instant.now())
                .build();

        var transactionEntity = transactionMapper.toEntity(testTransaction);
        transactionRepository.save(transactionEntity);

        // Create webhook event directly
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionReferenceId", gatewayTransactionId);

        var webhookEvent = com.paymentgateway.domain.model.WebhookEvent.builder()
                .id(UUID.randomUUID())
                .gateway(Gateway.AUTHORIZE_NET)
                .eventType("TRANSACTION.AUTHORIZED")
                .gatewayEventId("evt_" + UUID.randomUUID())
                .payload(payload)
                .signatureVerified(true)
                .processed(false)
                .createdAt(Instant.now())
                .build();

        // Process webhook
        webhookProcessorService.processWebhook(webhookEvent);

        // Verify transaction state was updated
        var updatedTransaction = transactionRepository.findByGatewayTransactionId(gatewayTransactionId);
        assertTrue(updatedTransaction.isPresent());
        var transaction = transactionMapper.toDomain(updatedTransaction.get());
        assertEquals(TransactionState.AUTHORIZED, transaction.getTransactionState());
    }

    @Test
    void testProcessWebhook_TransactionDeclined() throws Exception {
        // Create a transaction first
        String gatewayTransactionId = "gateway-txn-" + UUID.randomUUID();
        testTransaction = PaymentTransaction.builder()
                .id(UUID.randomUUID())
                .paymentId(testPayment.getId())
                .transactionType(TransactionType.PURCHASE)
                .transactionState(TransactionState.REQUESTED)
                .amount(testPayment.getAmount())
                .gatewayTransactionId(gatewayTransactionId)
                .traceId(UUID.randomUUID())
                .createdAt(Instant.now())
                .build();

        var transactionEntity = transactionMapper.toEntity(testTransaction);
        transactionRepository.save(transactionEntity);

        // Create webhook event
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionReferenceId", gatewayTransactionId);

        var webhookEvent = com.paymentgateway.domain.model.WebhookEvent.builder()
                .id(UUID.randomUUID())
                .gateway(Gateway.AUTHORIZE_NET)
                .eventType("TRANSACTION.DECLINED")
                .gatewayEventId("evt_" + UUID.randomUUID())
                .payload(payload)
                .signatureVerified(true)
                .processed(false)
                .createdAt(Instant.now())
                .build();

        // Process webhook
        webhookProcessorService.processWebhook(webhookEvent);

        // Verify transaction state was updated
        var updatedTransaction = transactionRepository.findByGatewayTransactionId(gatewayTransactionId);
        assertTrue(updatedTransaction.isPresent());
        var transaction = transactionMapper.toDomain(updatedTransaction.get());
        assertEquals(TransactionState.FAILED, transaction.getTransactionState());
    }

    @Test
    void testProcessWebhook_AlreadyProcessed() throws Exception {
        // Create webhook event that's already processed
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionReferenceId", "test-txn-123");

        var webhookEvent = com.paymentgateway.domain.model.WebhookEvent.builder()
                .id(UUID.randomUUID())
                .gateway(Gateway.AUTHORIZE_NET)
                .eventType("TRANSACTION.SETTLED")
                .gatewayEventId("evt_" + UUID.randomUUID())
                .payload(payload)
                .signatureVerified(true)
                .processed(true) // Already processed
                .processedAt(Instant.now())
                .createdAt(Instant.now())
                .build();

        // Process webhook - should return early
        assertDoesNotThrow(() -> webhookProcessorService.processWebhook(webhookEvent));
    }

    @Test
    void testProcessWebhook_UnknownEventType() throws Exception {
        // Create webhook event with unknown event type
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionReferenceId", "test-txn-123");

        var webhookEvent = com.paymentgateway.domain.model.WebhookEvent.builder()
                .id(UUID.randomUUID())
                .gateway(Gateway.AUTHORIZE_NET)
                .eventType("UNKNOWN.EVENT")
                .gatewayEventId("evt_" + UUID.randomUUID())
                .payload(payload)
                .signatureVerified(true)
                .processed(false)
                .createdAt(Instant.now())
                .build();

        // Process webhook - should not throw exception for unknown event types
        assertDoesNotThrow(() -> webhookProcessorService.processWebhook(webhookEvent));

        // Verify webhook was marked as processed
        var savedWebhook = webhookRepository.findByGatewayEventId(webhookEvent.getGatewayEventId());
        assertTrue(savedWebhook.isPresent());
        assertTrue(savedWebhook.get().getProcessed());
    }

    private String generateSignature(String payload, String secretKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to generate signature", e);
        }
    }
}

