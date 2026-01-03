package com.paymentgateway.domain.statemachine;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.Payment;
import com.paymentgateway.domain.model.PaymentTransaction;
import com.paymentgateway.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PaymentStateMachineTest {

    private PaymentStateMachine stateMachine;
    private Payment payment;
    private Money amount;

    @BeforeEach
    void setUp() {
        stateMachine = new PaymentStateMachine();
        amount = new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD"));
        payment = Payment.builder()
                .id(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .paymentType(PaymentType.PURCHASE)
                .status(PaymentStatus.INITIATED)
                .amount(amount)
                .gateway(Gateway.AUTHORIZE_NET)
                .idempotencyKey("test-key")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void testDerivePaymentStatus_NoTransactions_ReturnsInitiated() {
        PaymentStatus status = stateMachine.derivePaymentStatus(payment, Collections.emptyList());
        assertEquals(PaymentStatus.INITIATED, status);
    }

    @Test
    void testDerivePaymentStatus_SuccessfulPurchase_ReturnsCaptured() {
        PaymentTransaction transaction = createTransaction(TransactionType.PURCHASE, TransactionState.SUCCESS);
        List<PaymentTransaction> transactions = Collections.singletonList(transaction);

        PaymentStatus status = stateMachine.derivePaymentStatus(payment, transactions);
        assertEquals(PaymentStatus.CAPTURED, status);
    }

    @Test
    void testDerivePaymentStatus_Authorized_ReturnsAuthorized() {
        PaymentTransaction transaction = createTransaction(TransactionType.AUTH, TransactionState.AUTHORIZED);
        List<PaymentTransaction> transactions = Collections.singletonList(transaction);

        PaymentStatus status = stateMachine.derivePaymentStatus(payment, transactions);
        assertEquals(PaymentStatus.AUTHORIZED, status);
    }

    @Test
    void testDerivePaymentStatus_FailedTransaction_ReturnsFailed() {
        PaymentTransaction transaction = createTransaction(TransactionType.PURCHASE, TransactionState.FAILED);
        List<PaymentTransaction> transactions = Collections.singletonList(transaction);

        PaymentStatus status = stateMachine.derivePaymentStatus(payment, transactions);
        assertEquals(PaymentStatus.FAILED, status);
    }

    @Test
    void testCanTransition_InitiatedToAuthorized_ReturnsTrue() {
        assertTrue(stateMachine.canTransition(PaymentStatus.INITIATED, PaymentStatus.AUTHORIZED));
    }

    @Test
    void testCanTransition_InitiatedToCaptured_ReturnsTrue() {
        assertTrue(stateMachine.canTransition(PaymentStatus.INITIATED, PaymentStatus.CAPTURED));
    }

    @Test
    void testCanTransition_CapturedToAny_ReturnsFalse() {
        assertFalse(stateMachine.canTransition(PaymentStatus.CAPTURED, PaymentStatus.AUTHORIZED));
        assertFalse(stateMachine.canTransition(PaymentStatus.CAPTURED, PaymentStatus.INITIATED));
    }

    @Test
    void testCanCreateTransaction_PurchaseOnInitiated_ReturnsTrue() {
        Payment payment = this.payment.withStatus(PaymentStatus.INITIATED);
        assertTrue(stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE));
    }

    @Test
    void testCanCreateTransaction_CaptureOnAuthorized_ReturnsTrue() {
        Payment payment = this.payment.withStatus(PaymentStatus.AUTHORIZED);
        assertTrue(stateMachine.canCreateTransaction(payment, TransactionType.CAPTURE));
    }

    @Test
    void testCanCreateTransaction_CaptureOnInitiated_ReturnsFalse() {
        Payment payment = this.payment.withStatus(PaymentStatus.INITIATED);
        assertFalse(stateMachine.canCreateTransaction(payment, TransactionType.CAPTURE));
    }

    private PaymentTransaction createTransaction(TransactionType type, TransactionState state) {
        return PaymentTransaction.builder()
                .id(UUID.randomUUID())
                .paymentId(payment.getId())
                .transactionType(type)
                .transactionState(state)
                .amount(amount)
                .traceId(UUID.randomUUID())
                .createdAt(Instant.now())
                .build();
    }
}

