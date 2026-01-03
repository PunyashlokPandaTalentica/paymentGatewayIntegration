package com.paymentgateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.api.dto.*;
import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.Customer;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.domain.model.Payment;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.RetryableGatewayService;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.OrderRepository;
import com.paymentgateway.repository.PaymentRepository;
import com.paymentgateway.repository.PaymentTransactionRepository;
import com.paymentgateway.repository.mapper.PaymentTransactionMapper;
import com.paymentgateway.service.PaymentOrchestratorService;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-End tests for the complete payment flow:
 * 1. Create Order
 * 2. Create Payment
 * 3. Process Transaction (Purchase/Authorize/Capture)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PaymentE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentOrchestratorService orchestratorService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentTransactionRepository transactionRepository;


    @MockBean
    private RetryableGatewayService retryableGatewayService;

    @BeforeEach
    void setUp() {
        // Reset mocks
        reset(retryableGatewayService);
    }

    @Test
    void testE2E_PurchaseFlow() throws Exception {
        // Step 1: Create Order via API
        CreateOrderRequest orderRequest = new CreateOrderRequest(
                "ORD-E2E-PURCHASE-" + UUID.randomUUID(),
                new Money(BigDecimal.valueOf(150.00), Currency.getInstance("USD")),
                "E2E Purchase Test Order",
                new Customer("e2e@example.com", "+1234567890")
        );

        String orderResponse = mockMvc.perform(post("/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").exists())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String orderId = extractOrderId(orderResponse);

        // Step 2: Create Payment via API
        CreatePaymentRequest paymentRequest = new CreatePaymentRequest(
                "credit_card",
                PaymentType.PURCHASE,
                Gateway.AUTHORIZE_NET
        );

        String idempotencyKey = UUID.randomUUID().toString();
        String paymentResponse = mockMvc.perform(post("/v1/orders/" + orderId + "/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").exists())
                .andExpect(jsonPath("$.status").value("INITIATED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String paymentId = extractPaymentId(paymentResponse);

        // Step 3: Process Purchase Transaction via API
        PurchaseTransactionRequest transactionRequest = new PurchaseTransactionRequest(
                "token-e2e-purchase-" + UUID.randomUUID()
        );

        // Mock successful gateway response
        PurchaseResponse gatewayResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-e2e-purchase-" + UUID.randomUUID())
                .responseCode("1")
                .responseMessage("Approved")
                .authCode("AUTH-E2E")
                .build();

        when(retryableGatewayService.purchase(any())).thenReturn(gatewayResponse);

        mockMvc.perform(post("/v1/payments/" + paymentId + "/transactions/purchase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transactionRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").exists())
                .andExpect(jsonPath("$.type").value("PURCHASE"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.gatewayReferenceId").exists());

        // Verify gateway was called
        verify(retryableGatewayService, times(1)).purchase(any());

        // Verify final state
        var order = orderRepository.findById(UUID.fromString(orderId));
        assertTrue(order.isPresent());
        assertEquals(OrderStatus.COMPLETED, order.get().getStatus());

        var payment = paymentRepository.findById(UUID.fromString(paymentId));
        assertTrue(payment.isPresent());
        assertEquals(PaymentStatus.CAPTURED, payment.get().getStatus());

        // Verify transaction exists
        var transactions = transactionRepository.findByPaymentIdOrderByCreatedAtAsc(UUID.fromString(paymentId));
        assertEquals(1, transactions.size());
        assertEquals(TransactionType.PURCHASE, transactions.get(0).getTransactionType());
        assertEquals(TransactionState.SUCCESS, transactions.get(0).getTransactionState());
    }

    @Test
    void testE2E_AuthorizeAndCaptureFlow() throws Exception {
        // Step 1: Create Order
        CreateOrderRequest orderRequest = new CreateOrderRequest(
                "ORD-E2E-AUTH-CAPTURE-" + UUID.randomUUID(),
                new Money(BigDecimal.valueOf(200.00), Currency.getInstance("USD")),
                "E2E Auth-Capture Test Order",
                new Customer("e2e-auth@example.com", "+1234567890")
        );

        String orderResponse = mockMvc.perform(post("/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String orderId = extractOrderId(orderResponse);

        // Step 2: Create Payment with AUTH_CAPTURE flow
        CreatePaymentRequest paymentRequest = new CreatePaymentRequest(
                "credit_card",
                PaymentType.AUTH_CAPTURE,
                Gateway.AUTHORIZE_NET
        );

        String idempotencyKey = UUID.randomUUID().toString();
        String paymentResponse = mockMvc.perform(post("/v1/orders/" + orderId + "/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String paymentId = extractPaymentId(paymentResponse);

        // Step 3: Authorize Transaction
        PurchaseTransactionRequest authRequest = new PurchaseTransactionRequest(
                "token-e2e-auth-" + UUID.randomUUID()
        );

        AuthResponse authResponse = AuthResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-e2e-auth-" + UUID.randomUUID())
                .responseCode("1")
                .responseMessage("Authorized")
                .authCode("AUTH-E2E-AUTH")
                .build();

        when(retryableGatewayService.authorize(any())).thenReturn(authResponse);

        String authTransactionResponse = mockMvc.perform(post("/v1/payments/" + paymentId + "/transactions/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(authRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("AUTH"))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String authTransactionId = extractTransactionId(authTransactionResponse);

        // Verify authorization state
        var paymentAfterAuth = paymentRepository.findById(UUID.fromString(paymentId));
        assertTrue(paymentAfterAuth.isPresent());
        assertEquals(PaymentStatus.AUTHORIZED, paymentAfterAuth.get().getStatus());

        // Step 4: Capture Transaction
        CaptureRequest captureRequest = new CaptureRequest(
                new Money(BigDecimal.valueOf(200.00), Currency.getInstance("USD"))
        );

        CaptureResponse captureResponse = CaptureResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-e2e-capture-" + UUID.randomUUID())
                .responseCode("1")
                .responseMessage("Captured")
                .build();

        when(retryableGatewayService.capture(anyString(), anyLong(), anyString())).thenReturn(captureResponse);

        mockMvc.perform(post("/v1/payments/" + paymentId + "/transactions/" + authTransactionId + "/capture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(captureRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").exists())
                .andExpect(jsonPath("$.type").value("CAPTURE"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.parentTransactionId").value(authTransactionId));

        // Verify gateway calls
        verify(retryableGatewayService, times(1)).authorize(any());
        verify(retryableGatewayService, times(1)).capture(anyString(), anyLong(), anyString());

        // Verify final state
        var order = orderRepository.findById(UUID.fromString(orderId));
        assertTrue(order.isPresent());
        assertEquals(OrderStatus.COMPLETED, order.get().getStatus());

        var payment = paymentRepository.findById(UUID.fromString(paymentId));
        assertTrue(payment.isPresent());
        assertEquals(PaymentStatus.CAPTURED, payment.get().getStatus());

        // Verify both transactions exist
        var transactions = transactionRepository.findByPaymentIdOrderByCreatedAtAsc(UUID.fromString(paymentId));
        assertEquals(2, transactions.size());
        assertEquals(TransactionType.AUTH, transactions.get(0).getTransactionType());
        assertEquals(TransactionState.AUTHORIZED, transactions.get(0).getTransactionState());
        assertEquals(TransactionType.CAPTURE, transactions.get(1).getTransactionType());
        assertEquals(TransactionState.SUCCESS, transactions.get(1).getTransactionState());
    }

    @Test
    void testE2E_GetAllTransactions() throws Exception {
        // Create order, payment, and transaction via service
        Order order = Order.builder()
                .id(UUID.randomUUID())
                .merchantOrderId("ORD-E2E-GET-TXNS-" + UUID.randomUUID())
                .amount(new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD")))
                .description("E2E Get Transactions Test")
                .customer(new Customer("e2e-get@example.com", "+1234567890"))
                .status(OrderStatus.CREATED)
                .createdAt(java.time.Instant.now())
                .updatedAt(java.time.Instant.now())
                .build();

        order = orchestratorService.createOrder(order);

        Payment payment = orchestratorService.createPayment(
                order.getId(),
                PaymentType.PURCHASE,
                Gateway.AUTHORIZE_NET,
                UUID.randomUUID().toString()
        );

        PurchaseResponse purchaseResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-get-txns")
                .responseCode("1")
                .responseMessage("Approved")
                .authCode("AUTH")
                .build();

        when(retryableGatewayService.purchase(any())).thenReturn(purchaseResponse);

        orchestratorService.processPurchase(
                payment.getId(),
                "token-get-txns",
                UUID.randomUUID()
        );

        // Get all transactions via API
        mockMvc.perform(get("/v1/payments/" + payment.getId() + "/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].transactionId").exists())
                .andExpect(jsonPath("$[0].type").value("PURCHASE"))
                .andExpect(jsonPath("$[0].status").value("SUCCESS"));
    }

    @Test
    void testE2E_PurchaseFlow_Failure() throws Exception {
        // Create order and payment
        CreateOrderRequest orderRequest = new CreateOrderRequest(
                "ORD-E2E-FAIL-" + UUID.randomUUID(),
                new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD")),
                "E2E Failure Test",
                new Customer("e2e-fail@example.com", "+1234567890")
        );

        String orderId = extractOrderId(mockMvc.perform(post("/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());

        CreatePaymentRequest paymentRequest = new CreatePaymentRequest(
                "credit_card",
                PaymentType.PURCHASE,
                Gateway.AUTHORIZE_NET
        );

        String paymentId = extractPaymentId(mockMvc.perform(post("/v1/orders/" + orderId + "/payments")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());

        // Mock failed gateway response
        PurchaseResponse failureResponse = PurchaseResponse.builder()
                .success(false)
                .gatewayTransactionId(null)
                .responseCode("2")
                .responseMessage("Declined")
                .authCode(null)
                .build();

        when(retryableGatewayService.purchase(any())).thenReturn(failureResponse);

        PurchaseTransactionRequest transactionRequest = new PurchaseTransactionRequest(
                "token-fail"
        );

        mockMvc.perform(post("/v1/payments/" + paymentId + "/transactions/purchase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transactionRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"));

        // Verify payment status is FAILED
        var payment = paymentRepository.findById(UUID.fromString(paymentId));
        assertTrue(payment.isPresent());
        assertEquals(PaymentStatus.FAILED, payment.get().getStatus());
    }

    // Helper methods to extract IDs from JSON responses
    private String extractOrderId(String jsonResponse) throws Exception {
        return objectMapper.readTree(jsonResponse).get("orderId").asText();
    }

    private String extractPaymentId(String jsonResponse) throws Exception {
        return objectMapper.readTree(jsonResponse).get("paymentId").asText();
    }

    private String extractTransactionId(String jsonResponse) throws Exception {
        return objectMapper.readTree(jsonResponse).get("transactionId").asText();
    }
}

