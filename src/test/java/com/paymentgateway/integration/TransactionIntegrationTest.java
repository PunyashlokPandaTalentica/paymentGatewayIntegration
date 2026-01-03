package com.paymentgateway.integration;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.Customer;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.domain.model.Payment;
import com.paymentgateway.domain.model.PaymentTransaction;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.RetryableGatewayService;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.PaymentRepository;
import com.paymentgateway.repository.PaymentTransactionRepository;
import com.paymentgateway.repository.mapper.PaymentTransactionMapper;
import com.paymentgateway.service.PaymentOrchestratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransactionIntegrationTest {

    @Autowired
    private PaymentOrchestratorService orchestratorService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @MockBean
    private RetryableGatewayService retryableGatewayService;

    private Order testOrder;
    private Payment testPayment;
    private Money amount;

    @BeforeEach
    void setUp() {
        amount = new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD"));

        // Create test order
        testOrder = Order.builder()
                .id(UUID.randomUUID())
                .merchantOrderId("ORD-TXN-TEST-" + UUID.randomUUID())
                .amount(amount)
                .description("Transaction test order")
                .customer(new Customer("txn@example.com", "+1234567890"))
                .status(OrderStatus.CREATED)
                .createdAt(java.time.Instant.now())
                .updatedAt(java.time.Instant.now())
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
    }

    @Test
    void testProcessPurchase_Success() {
        // Mock successful gateway response
        PurchaseResponse successResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-purchase-" + UUID.randomUUID())
                .responseCode("1")
                .responseMessage("Approved")
                .authCode("AUTH123")
                .build();

        when(retryableGatewayService.purchase(any())).thenReturn(successResponse);

        UUID traceId = UUID.randomUUID();
        String paymentMethodToken = "token-purchase-" + UUID.randomUUID();

        PaymentTransaction transaction = orchestratorService.processPurchase(
                testPayment.getId(),
                paymentMethodToken,
                traceId
        );

        // Verify transaction
        assertNotNull(transaction);
        assertEquals(TransactionType.PURCHASE, transaction.getTransactionType());
        assertEquals(TransactionState.SUCCESS, transaction.getTransactionState());
        assertEquals(testPayment.getId(), transaction.getPaymentId());
        assertEquals(traceId, transaction.getTraceId());
        assertNotNull(transaction.getGatewayTransactionId());
        assertEquals("1", transaction.getGatewayResponseCode());

        // Verify gateway was called
        verify(retryableGatewayService, times(1)).purchase(any());

        // Verify transaction was persisted
        assertTrue(transactionRepository.findById(transaction.getId()).isPresent());

        // Verify payment status was updated
        var updatedPayment = paymentRepository.findById(testPayment.getId());
        assertTrue(updatedPayment.isPresent());
        var payment = updatedPayment.get();
        assertEquals(PaymentStatus.CAPTURED, payment.getStatus());
    }

    @Test
    void testProcessPurchase_Failure() {
        // Mock failed gateway response
        PurchaseResponse failureResponse = PurchaseResponse.builder()
                .success(false)
                .gatewayTransactionId(null)
                .responseCode("2")
                .responseMessage("Declined")
                .authCode(null)
                .build();

        when(retryableGatewayService.purchase(any())).thenReturn(failureResponse);

        UUID traceId = UUID.randomUUID();
        String paymentMethodToken = "token-purchase-fail-" + UUID.randomUUID();

        PaymentTransaction transaction = orchestratorService.processPurchase(
                testPayment.getId(),
                paymentMethodToken,
                traceId
        );

        // Verify transaction
        assertNotNull(transaction);
        assertEquals(TransactionType.PURCHASE, transaction.getTransactionType());
        assertEquals(TransactionState.FAILED, transaction.getTransactionState());
        assertEquals("2", transaction.getGatewayResponseCode());
        assertEquals("Declined", transaction.getGatewayResponseMsg());

        // Verify payment status
        var updatedPayment = paymentRepository.findById(testPayment.getId());
        assertTrue(updatedPayment.isPresent());
        assertEquals(PaymentStatus.FAILED, updatedPayment.get().getStatus());
    }

    @Test
    void testProcessAuthorize_Success() {
        // Create a new order for authorization test
        Order authOrder = Order.builder()
                .id(UUID.randomUUID())
                .merchantOrderId("ORD-AUTH-TEST-" + UUID.randomUUID())
                .amount(amount)
                .description("Auth test order")
                .customer(new Customer("auth@example.com", "+1234567890"))
                .status(OrderStatus.CREATED)
                .createdAt(java.time.Instant.now())
                .updatedAt(java.time.Instant.now())
                .build();
        authOrder = orchestratorService.createOrder(authOrder);

        // Create a new payment for authorization
        String idempotencyKey = UUID.randomUUID().toString();
        Payment authPayment = orchestratorService.createPayment(
                authOrder.getId(),
                PaymentType.AUTH_CAPTURE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        // Mock successful authorization response
        AuthResponse successResponse = AuthResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-auth-" + UUID.randomUUID())
                .responseCode("1")
                .responseMessage("Authorized")
                .authCode("AUTH456")
                .build();

        when(retryableGatewayService.authorize(any())).thenReturn(successResponse);

        UUID traceId = UUID.randomUUID();
        String paymentMethodToken = "token-auth-" + UUID.randomUUID();

        PaymentTransaction transaction = orchestratorService.processAuthorize(
                authPayment.getId(),
                paymentMethodToken,
                traceId
        );

        // Verify transaction
        assertNotNull(transaction);
        assertEquals(TransactionType.AUTH, transaction.getTransactionType());
        assertEquals(TransactionState.AUTHORIZED, transaction.getTransactionState());
        assertEquals(authPayment.getId(), transaction.getPaymentId());
        assertNotNull(transaction.getGatewayTransactionId());

        // Verify gateway was called
        verify(retryableGatewayService, times(1)).authorize(any());

        // Verify payment status
        var updatedPayment = paymentRepository.findById(authPayment.getId());
        assertTrue(updatedPayment.isPresent());
        assertEquals(PaymentStatus.AUTHORIZED, updatedPayment.get().getStatus());
    }

    @Test
    void testProcessAuthorize_Failure() {
        // Create a new order for authorization failure test
        Order authOrder = Order.builder()
                .id(UUID.randomUUID())
                .merchantOrderId("ORD-AUTH-FAIL-" + UUID.randomUUID())
                .amount(amount)
                .description("Auth fail test order")
                .customer(new Customer("authfail@example.com", "+1234567890"))
                .status(OrderStatus.CREATED)
                .createdAt(java.time.Instant.now())
                .updatedAt(java.time.Instant.now())
                .build();
        authOrder = orchestratorService.createOrder(authOrder);

        // Create a new payment for authorization
        String idempotencyKey = UUID.randomUUID().toString();
        Payment authPayment = orchestratorService.createPayment(
                authOrder.getId(),
                PaymentType.AUTH_CAPTURE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        // Mock failed authorization response
        AuthResponse failureResponse = AuthResponse.builder()
                .success(false)
                .gatewayTransactionId(null)
                .responseCode("3")
                .responseMessage("Invalid card")
                .authCode(null)
                .build();

        when(retryableGatewayService.authorize(any())).thenReturn(failureResponse);

        UUID traceId = UUID.randomUUID();
        String paymentMethodToken = "token-auth-fail-" + UUID.randomUUID();

        PaymentTransaction transaction = orchestratorService.processAuthorize(
                authPayment.getId(),
                paymentMethodToken,
                traceId
        );

        // Verify transaction failed
        assertNotNull(transaction);
        assertEquals(TransactionState.FAILED, transaction.getTransactionState());
        assertEquals("3", transaction.getGatewayResponseCode());
    }

    @Test
    void testProcessCapture_Success() {
        // Create a new order for capture test
        Order captureOrder = Order.builder()
                .id(UUID.randomUUID())
                .merchantOrderId("ORD-CAPTURE-TEST-" + UUID.randomUUID())
                .amount(amount)
                .description("Capture test order")
                .customer(new Customer("capture@example.com", "+1234567890"))
                .status(OrderStatus.CREATED)
                .createdAt(java.time.Instant.now())
                .updatedAt(java.time.Instant.now())
                .build();
        captureOrder = orchestratorService.createOrder(captureOrder);

        // First, create and authorize a transaction
        String idempotencyKey = UUID.randomUUID().toString();
        Payment authPayment = orchestratorService.createPayment(
                captureOrder.getId(),
                PaymentType.AUTH_CAPTURE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        // Mock authorization
        AuthResponse authResponse = AuthResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-auth-capture-" + UUID.randomUUID())
                .responseCode("1")
                .responseMessage("Authorized")
                .authCode("AUTH789")
                .build();

        when(retryableGatewayService.authorize(any())).thenReturn(authResponse);

        UUID authTraceId = UUID.randomUUID();
        PaymentTransaction authTransaction = orchestratorService.processAuthorize(
                authPayment.getId(),
                "token-auth-capture",
                authTraceId
        );

        // Now capture the authorized transaction
        CaptureResponse captureResponse = CaptureResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-capture-" + UUID.randomUUID())
                .responseCode("1")
                .responseMessage("Captured")
                .build();

        when(retryableGatewayService.capture(anyString(), anyLong(), anyString())).thenReturn(captureResponse);

        UUID captureTraceId = UUID.randomUUID();
        PaymentTransaction captureTransaction = orchestratorService.processCapture(
                authPayment.getId(),
                authTransaction.getId(),
                amount,
                captureTraceId
        );

        // Verify capture transaction
        assertNotNull(captureTransaction);
        assertEquals(TransactionType.CAPTURE, captureTransaction.getTransactionType());
        assertEquals(TransactionState.SUCCESS, captureTransaction.getTransactionState());
        assertEquals(authTransaction.getId(), captureTransaction.getParentTransactionId());
        assertEquals(authPayment.getId(), captureTransaction.getPaymentId());

        // Verify gateway was called
        verify(retryableGatewayService, times(1)).capture(anyString(), anyLong(), anyString());

        // Verify payment status
        var updatedPayment = paymentRepository.findById(authPayment.getId());
        assertTrue(updatedPayment.isPresent());
        assertEquals(PaymentStatus.CAPTURED, updatedPayment.get().getStatus());
    }

    @Test
    void testProcessCapture_Failure() {
        // Create a new order for capture failure test
        Order captureOrder = Order.builder()
                .id(UUID.randomUUID())
                .merchantOrderId("ORD-CAPTURE-FAIL-" + UUID.randomUUID())
                .amount(amount)
                .description("Capture fail test order")
                .customer(new Customer("capturefail@example.com", "+1234567890"))
                .status(OrderStatus.CREATED)
                .createdAt(java.time.Instant.now())
                .updatedAt(java.time.Instant.now())
                .build();
        captureOrder = orchestratorService.createOrder(captureOrder);

        // First, create and authorize a transaction
        String idempotencyKey = UUID.randomUUID().toString();
        Payment authPayment = orchestratorService.createPayment(
                captureOrder.getId(),
                PaymentType.AUTH_CAPTURE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        // Mock authorization
        AuthResponse authResponse = AuthResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-auth-capture-fail-" + UUID.randomUUID())
                .responseCode("1")
                .responseMessage("Authorized")
                .authCode("AUTH999")
                .build();

        when(retryableGatewayService.authorize(any())).thenReturn(authResponse);

        UUID authTraceId = UUID.randomUUID();
        PaymentTransaction authTransaction = orchestratorService.processAuthorize(
                authPayment.getId(),
                "token-auth-capture-fail",
                authTraceId
        );

        // Mock failed capture
        CaptureResponse failureResponse = CaptureResponse.builder()
                .success(false)
                .gatewayTransactionId(null)
                .responseCode("4")
                .responseMessage("Transaction not found")
                .build();

        when(retryableGatewayService.capture(anyString(), anyLong(), anyString())).thenReturn(failureResponse);

        UUID captureTraceId = UUID.randomUUID();
        PaymentTransaction captureTransaction = orchestratorService.processCapture(
                authPayment.getId(),
                authTransaction.getId(),
                amount,
                captureTraceId
        );

        // Verify capture transaction failed
        assertNotNull(captureTransaction);
        assertEquals(TransactionState.FAILED, captureTransaction.getTransactionState());
        assertEquals("4", captureTransaction.getGatewayResponseCode());
    }

    @Test
    void testProcessPurchase_InvalidPaymentState() {
        // Process a purchase first
        PurchaseResponse successResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-1")
                .responseCode("1")
                .responseMessage("Approved")
                .authCode("AUTH123")
                .build();

        when(retryableGatewayService.purchase(any())).thenReturn(successResponse);

        orchestratorService.processPurchase(
                testPayment.getId(),
                "token-1",
                UUID.randomUUID()
        );

        // Try to process another purchase - should fail
        assertThrows(IllegalStateException.class, () ->
                orchestratorService.processPurchase(
                        testPayment.getId(),
                        "token-2",
                        UUID.randomUUID()
                )
        );
    }

    @Test
    void testGetAllTransactions() {
        // Create multiple transactions
        PurchaseResponse purchaseResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-purchase-1")
                .responseCode("1")
                .responseMessage("Approved")
                .authCode("AUTH1")
                .build();

        when(retryableGatewayService.purchase(any())).thenReturn(purchaseResponse);

        PaymentTransaction transaction1 = orchestratorService.processPurchase(
                testPayment.getId(),
                "token-1",
                UUID.randomUUID()
        );

        // Get all transactions
        List<com.paymentgateway.domain.entity.PaymentTransactionEntity> entities =
                transactionRepository.findByPaymentIdOrderByCreatedAtAsc(testPayment.getId());
        List<PaymentTransaction> transactions = transactionMapper.toDomainList(entities);

        assertNotNull(transactions);
        assertEquals(1, transactions.size());
        assertEquals(transaction1.getId(), transactions.get(0).getId());
    }

    @Test
    void testProcessCapture_InvalidParentTransaction() {
        // Create a new order for invalid parent test
        Order invalidOrder = Order.builder()
                .id(UUID.randomUUID())
                .merchantOrderId("ORD-INVALID-PARENT-" + UUID.randomUUID())
                .amount(amount)
                .description("Invalid parent test order")
                .customer(new Customer("invalid@example.com", "+1234567890"))
                .status(OrderStatus.CREATED)
                .createdAt(java.time.Instant.now())
                .updatedAt(java.time.Instant.now())
                .build();
        invalidOrder = orchestratorService.createOrder(invalidOrder);

        // Create a payment and authorize it first
        String idempotencyKey = UUID.randomUUID().toString();
        Payment payment = orchestratorService.createPayment(
                invalidOrder.getId(),
                PaymentType.AUTH_CAPTURE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        // Authorize first to get payment in AUTHORIZED state
        AuthResponse authResponse = AuthResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-auth-invalid-parent")
                .responseCode("1")
                .responseMessage("Authorized")
                .authCode("AUTH")
                .build();

        when(retryableGatewayService.authorize(any())).thenReturn(authResponse);

        orchestratorService.processAuthorize(
                payment.getId(),
                "token-auth-invalid-parent",
                UUID.randomUUID()
        );

        // Now try to capture with invalid parent transaction ID
        UUID invalidParentId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () ->
                orchestratorService.processCapture(
                        payment.getId(),
                        invalidParentId,
                        amount,
                        UUID.randomUUID()
                )
        );
    }

    @Test
    void testProcessCapture_OnlyAuthTransactions() {
        // Create a payment and process a purchase (not auth)
        PurchaseResponse purchaseResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-purchase-only")
                .responseCode("1")
                .responseMessage("Approved")
                .authCode("AUTH1")
                .build();

        when(retryableGatewayService.purchase(any())).thenReturn(purchaseResponse);

        PaymentTransaction purchaseTransaction = orchestratorService.processPurchase(
                testPayment.getId(),
                "token-purchase-only",
                UUID.randomUUID()
        );

        // Try to capture a purchase transaction (should fail)
        assertThrows(IllegalStateException.class, () ->
                orchestratorService.processCapture(
                        testPayment.getId(),
                        purchaseTransaction.getId(),
                        amount,
                        UUID.randomUUID()
                )
        );
    }
}

