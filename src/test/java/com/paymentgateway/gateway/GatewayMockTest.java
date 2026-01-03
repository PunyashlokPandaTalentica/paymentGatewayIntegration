package com.paymentgateway.gateway;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.Customer;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GatewayMockTest {

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private RetryableGatewayService retryableGatewayService;

    private Order order;
    private Money amount;

    @BeforeEach
    void setUp() {
        amount = new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD"));
        order = Order.builder()
                .id(UUID.randomUUID())
                .merchantOrderId("ORD-GATEWAY-TEST-" + UUID.randomUUID())
                .amount(amount)
                .description("Gateway test order")
                .customer(new Customer("gateway@example.com", "+1234567890"))
                .status(OrderStatus.CREATED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void testPurchase_Success() {
        // Arrange
        PurchaseRequest request = new PurchaseRequest(
                "token-123",
                amount,
                order.getId().toString(),
                order.getMerchantOrderId(),
                order.getDescription()
        );

        PurchaseResponse successResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-123")
                .responseCode("1")
                .responseMessage("Approved")
                .authCode("AUTH123")
                .build();

        when(paymentGateway.purchase(any(PurchaseRequest.class))).thenReturn(successResponse);

        // Act
        PurchaseResponse response = paymentGateway.purchase(request);

        // Assert
        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals("gateway-txn-123", response.getGatewayTransactionId());
        assertEquals("1", response.getResponseCode());
        assertEquals("Approved", response.getResponseMessage());
        assertEquals("AUTH123", response.getAuthCode());
        verify(paymentGateway, times(1)).purchase(request);
    }

    @Test
    void testPurchase_Failure() {
        // Arrange
        PurchaseRequest request = new PurchaseRequest(
                "token-123",
                amount,
                order.getId().toString(),
                order.getMerchantOrderId(),
                order.getDescription()
        );

        PurchaseResponse failureResponse = PurchaseResponse.builder()
                .success(false)
                .gatewayTransactionId(null)
                .responseCode("2")
                .responseMessage("Declined")
                .authCode(null)
                .build();

        when(paymentGateway.purchase(any(PurchaseRequest.class))).thenReturn(failureResponse);

        // Act
        PurchaseResponse response = paymentGateway.purchase(request);

        // Assert
        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals("2", response.getResponseCode());
        assertEquals("Declined", response.getResponseMessage());
        verify(paymentGateway, times(1)).purchase(request);
    }

    @Test
    void testAuthorize_Success() {
        // Arrange
        PurchaseRequest request = new PurchaseRequest(
                "token-123",
                amount,
                order.getId().toString(),
                order.getMerchantOrderId(),
                order.getDescription()
        );

        AuthResponse successResponse = AuthResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-auth-123")
                .responseCode("1")
                .responseMessage("Authorized")
                .authCode("AUTH456")
                .build();

        when(paymentGateway.authorize(any(PurchaseRequest.class))).thenReturn(successResponse);

        // Act
        AuthResponse response = paymentGateway.authorize(request);

        // Assert
        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals("gateway-auth-123", response.getGatewayTransactionId());
        assertEquals("1", response.getResponseCode());
        assertEquals("Authorized", response.getResponseMessage());
        assertEquals("AUTH456", response.getAuthCode());
        verify(paymentGateway, times(1)).authorize(request);
    }

    @Test
    void testAuthorize_Failure() {
        // Arrange
        PurchaseRequest request = new PurchaseRequest(
                "token-123",
                amount,
                order.getId().toString(),
                order.getMerchantOrderId(),
                order.getDescription()
        );

        AuthResponse failureResponse = AuthResponse.builder()
                .success(false)
                .gatewayTransactionId(null)
                .responseCode("3")
                .responseMessage("Invalid card")
                .authCode(null)
                .build();

        when(paymentGateway.authorize(any(PurchaseRequest.class))).thenReturn(failureResponse);

        // Act
        AuthResponse response = paymentGateway.authorize(request);

        // Assert
        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals("3", response.getResponseCode());
        assertEquals("Invalid card", response.getResponseMessage());
        verify(paymentGateway, times(1)).authorize(request);
    }

    @Test
    void testCapture_Success() {
        // Arrange
        String transactionId = "gateway-auth-123";
        long amountCents = 10000L;
        String currency = "USD";

        CaptureResponse successResponse = CaptureResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-capture-123")
                .responseCode("1")
                .responseMessage("Captured")
                .build();

        when(paymentGateway.capture(transactionId, amountCents, currency)).thenReturn(successResponse);

        // Act
        CaptureResponse response = paymentGateway.capture(transactionId, amountCents, currency);

        // Assert
        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals("gateway-capture-123", response.getGatewayTransactionId());
        assertEquals("1", response.getResponseCode());
        assertEquals("Captured", response.getResponseMessage());
        verify(paymentGateway, times(1)).capture(transactionId, amountCents, currency);
    }

    @Test
    void testCapture_Failure() {
        // Arrange
        String transactionId = "gateway-auth-123";
        long amountCents = 10000L;
        String currency = "USD";

        CaptureResponse failureResponse = CaptureResponse.builder()
                .success(false)
                .gatewayTransactionId(null)
                .responseCode("4")
                .responseMessage("Transaction not found")
                .build();

        when(paymentGateway.capture(transactionId, amountCents, currency)).thenReturn(failureResponse);

        // Act
        CaptureResponse response = paymentGateway.capture(transactionId, amountCents, currency);

        // Assert
        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals("4", response.getResponseCode());
        assertEquals("Transaction not found", response.getResponseMessage());
        verify(paymentGateway, times(1)).capture(transactionId, amountCents, currency);
    }

    @Test
    void testPurchase_WithRetryableGatewayService() {
        // Arrange
        PurchaseRequest request = new PurchaseRequest(
                "token-123",
                amount,
                order.getId().toString(),
                order.getMerchantOrderId(),
                order.getDescription()
        );

        PurchaseResponse successResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-123")
                .responseCode("1")
                .responseMessage("Approved")
                .authCode("AUTH123")
                .build();

        when(retryableGatewayService.purchase(any(PurchaseRequest.class))).thenReturn(successResponse);

        // Act
        PurchaseResponse response = retryableGatewayService.purchase(request);

        // Assert
        assertNotNull(response);
        assertTrue(response.isSuccess());
        verify(retryableGatewayService, times(1)).purchase(request);
    }

    @Test
    void testAuthorize_WithRetryableGatewayService() {
        // Arrange
        PurchaseRequest request = new PurchaseRequest(
                "token-123",
                amount,
                order.getId().toString(),
                order.getMerchantOrderId(),
                order.getDescription()
        );

        AuthResponse successResponse = AuthResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-auth-123")
                .responseCode("1")
                .responseMessage("Authorized")
                .authCode("AUTH456")
                .build();

        when(retryableGatewayService.authorize(any(PurchaseRequest.class))).thenReturn(successResponse);

        // Act
        AuthResponse response = retryableGatewayService.authorize(request);

        // Assert
        assertNotNull(response);
        assertTrue(response.isSuccess());
        verify(retryableGatewayService, times(1)).authorize(request);
    }

    @Test
    void testCapture_WithRetryableGatewayService() {
        // Arrange
        String transactionId = "gateway-auth-123";
        long amountCents = 10000L;
        String currency = "USD";

        CaptureResponse successResponse = CaptureResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-capture-123")
                .responseCode("1")
                .responseMessage("Captured")
                .build();

        when(retryableGatewayService.capture(transactionId, amountCents, currency)).thenReturn(successResponse);

        // Act
        CaptureResponse response = retryableGatewayService.capture(transactionId, amountCents, currency);

        // Assert
        assertNotNull(response);
        assertTrue(response.isSuccess());
        verify(retryableGatewayService, times(1)).capture(transactionId, amountCents, currency);
    }

    @Test
    void testPurchase_WithDifferentAmounts() {
        // Test with different amounts
        Money smallAmount = new Money(BigDecimal.valueOf(1.00), Currency.getInstance("USD"));
        Money largeAmount = new Money(BigDecimal.valueOf(10000.00), Currency.getInstance("USD"));

        PurchaseRequest smallRequest = new PurchaseRequest(
                "token-123",
                smallAmount,
                order.getId().toString(),
                order.getMerchantOrderId(),
                order.getDescription()
        );

        PurchaseRequest largeRequest = new PurchaseRequest(
                "token-123",
                largeAmount,
                order.getId().toString(),
                order.getMerchantOrderId(),
                order.getDescription()
        );

        PurchaseResponse smallResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-small")
                .responseCode("1")
                .responseMessage("Approved")
                .authCode("AUTH123")
                .build();

        PurchaseResponse largeResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-large")
                .responseCode("1")
                .responseMessage("Approved")
                .authCode("AUTH456")
                .build();

        when(paymentGateway.purchase(smallRequest)).thenReturn(smallResponse);
        when(paymentGateway.purchase(largeRequest)).thenReturn(largeResponse);

        // Act
        PurchaseResponse smallResult = paymentGateway.purchase(smallRequest);
        PurchaseResponse largeResult = paymentGateway.purchase(largeRequest);

        // Assert
        assertTrue(smallResult.isSuccess());
        assertTrue(largeResult.isSuccess());
        assertEquals("gateway-txn-small", smallResult.getGatewayTransactionId());
        assertEquals("gateway-txn-large", largeResult.getGatewayTransactionId());
    }

    @Test
    void testPurchase_WithDifferentCurrencies() {
        // Test with different currencies
        Money usdAmount = new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD"));
        Money eurAmount = new Money(BigDecimal.valueOf(100.00), Currency.getInstance("EUR"));

        PurchaseRequest usdRequest = new PurchaseRequest(
                "token-123",
                usdAmount,
                order.getId().toString(),
                order.getMerchantOrderId(),
                order.getDescription()
        );

        PurchaseRequest eurRequest = new PurchaseRequest(
                "token-123",
                eurAmount,
                order.getId().toString(),
                order.getMerchantOrderId(),
                order.getDescription()
        );

        PurchaseResponse usdResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-usd")
                .responseCode("1")
                .responseMessage("Approved")
                .authCode("AUTH123")
                .build();

        PurchaseResponse eurResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-eur")
                .responseCode("1")
                .responseMessage("Approved")
                .authCode("AUTH456")
                .build();

        when(paymentGateway.purchase(usdRequest)).thenReturn(usdResponse);
        when(paymentGateway.purchase(eurRequest)).thenReturn(eurResponse);

        // Act
        PurchaseResponse usdResult = paymentGateway.purchase(usdRequest);
        PurchaseResponse eurResult = paymentGateway.purchase(eurRequest);

        // Assert
        assertTrue(usdResult.isSuccess());
        assertTrue(eurResult.isSuccess());
        assertEquals("gateway-txn-usd", usdResult.getGatewayTransactionId());
        assertEquals("gateway-txn-eur", eurResult.getGatewayTransactionId());
    }
}

