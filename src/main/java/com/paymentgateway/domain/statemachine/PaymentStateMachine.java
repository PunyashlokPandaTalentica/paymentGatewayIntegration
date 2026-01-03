package com.paymentgateway.domain.statemachine;

import com.paymentgateway.domain.enums.PaymentStatus;
import com.paymentgateway.domain.enums.TransactionState;
import com.paymentgateway.domain.enums.TransactionType;
import com.paymentgateway.domain.model.Payment;
import com.paymentgateway.domain.model.PaymentTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@Slf4j
public class PaymentStateMachine {

    /**
     * Derives payment status from the latest successful transaction.
     * Transactions are authoritative - never infer from request success.
     */
    public PaymentStatus derivePaymentStatus(Payment payment, List<PaymentTransaction> transactions) {
        if (transactions.isEmpty()) {
            return PaymentStatus.INITIATED;
        }

        // Find the latest successful transaction
        Optional<PaymentTransaction> latestSuccess = transactions.stream()
                .filter(tx -> tx.getTransactionState() == TransactionState.SUCCESS 
                           || tx.getTransactionState() == TransactionState.AUTHORIZED
                           || tx.getTransactionState() == TransactionState.SETTLED)
                .max((t1, t2) -> t1.getCreatedAt().compareTo(t2.getCreatedAt()));

        if (latestSuccess.isEmpty()) {
            // Check if there are any failed transactions
            boolean hasFailures = transactions.stream()
                    .anyMatch(tx -> tx.getTransactionState() == TransactionState.FAILED);
            return hasFailures ? PaymentStatus.FAILED : PaymentStatus.INITIATED;
        }

        PaymentTransaction latest = latestSuccess.get();
        TransactionType type = latest.getTransactionType();
        TransactionState state = latest.getTransactionState();

        // State derivation based on transaction type and state
        if (type == TransactionType.PURCHASE) {
            if (state == TransactionState.SUCCESS || state == TransactionState.SETTLED) {
                return PaymentStatus.CAPTURED;
            } else if (state == TransactionState.AUTHORIZED) {
                return PaymentStatus.AUTHORIZED;
            }
        } else if (type == TransactionType.AUTH) {
            if (state == TransactionState.AUTHORIZED) {
                return PaymentStatus.AUTHORIZED;
            } else if (state == TransactionState.SUCCESS) {
                return PaymentStatus.AUTHORIZED;
            }
        } else if (type == TransactionType.CAPTURE) {
            if (state == TransactionState.SUCCESS || state == TransactionState.SETTLED) {
                return PaymentStatus.CAPTURED;
            }
        }

        // Check for failures
        boolean allFailed = transactions.stream()
                .allMatch(tx -> tx.getTransactionState() == TransactionState.FAILED);
        if (allFailed) {
            return PaymentStatus.FAILED;
        }

        return PaymentStatus.INITIATED;
    }

    /**
     * Validates if a state transition is allowed.
     * State transitions are explicit and forward-only.
     */
    public boolean canTransition(PaymentStatus current, PaymentStatus target) {
        // Define allowed transitions
        return switch (current) {
            case INITIATED -> target == PaymentStatus.AUTHORIZED 
                           || target == PaymentStatus.CAPTURED 
                           || target == PaymentStatus.FAILED;
            case AUTHORIZED -> target == PaymentStatus.CAPTURED 
                            || target == PaymentStatus.FAILED 
                            || target == PaymentStatus.CANCELLED;
            case PARTIALLY_AUTHORIZED -> target == PaymentStatus.AUTHORIZED 
                                       || target == PaymentStatus.FAILED;
            case CAPTURED -> false; // Terminal state
            case FAILED -> false; // Terminal state
            case CANCELLED -> false; // Terminal state
        };
    }

    /**
     * Validates if a transaction can be created for the payment.
     */
    public boolean canCreateTransaction(Payment payment, TransactionType transactionType) {
        PaymentStatus status = payment.getStatus();

        return switch (transactionType) {
            case PURCHASE -> status == PaymentStatus.INITIATED;
            case AUTH -> status == PaymentStatus.INITIATED;
            case CAPTURE -> status == PaymentStatus.AUTHORIZED;
            case VOID -> status == PaymentStatus.AUTHORIZED;
            case REFUND -> status == PaymentStatus.CAPTURED;
        };
    }
}

