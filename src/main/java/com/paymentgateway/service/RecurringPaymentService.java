package com.paymentgateway.service;

import com.paymentgateway.domain.entity.SubscriptionEntity;
import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.gateway.impl.AuthorizeNetGateway;
import com.paymentgateway.repository.*;
import com.paymentgateway.repository.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Service for processing recurring payments based on subscription schedules.
 */
@Service
@Slf4j
public class RecurringPaymentService {

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private SubscriptionPaymentRepository subscriptionPaymentRepository;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private PaymentOrchestratorService paymentOrchestratorService;

    @Autowired
    private SubscriptionMapper subscriptionMapper;

    @Autowired
    private SubscriptionPaymentMapper subscriptionPaymentMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private PaymentMapper paymentMapper;

    @Autowired(required = false)
    private AuthorizeNetGateway authorizeNetGateway;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private PaymentTransactionMapper paymentTransactionMapper;

    @Autowired
    private PaymentStateMachine stateMachine;

    /**
     * Processes all subscriptions that are due for billing.
     * This is scheduled to run periodically (e.g., every hour).
     */
    @Scheduled(fixedRateString = "${app.recurring-payment.check-interval:3600000}") // Default: 1 hour
    @Transactional
    public void processDueSubscriptions() {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        MDC.put("operation", "processDueSubscriptions");

        try {
            log.info("Processing subscriptions due for billing");

            Instant now = Instant.now();
            List<SubscriptionEntity> dueSubscriptions = subscriptionRepository.findDueForBilling(
                    SubscriptionStatus.ACTIVE, now);

            log.info("Found {} subscriptions due for billing", dueSubscriptions.size());

            for (SubscriptionEntity entity : dueSubscriptions) {
                try {
                    processSubscriptionBilling(subscriptionMapper.toDomain(entity));
                } catch (Exception e) {
                    log.error("Error processing subscription billing: {}", entity.getId(), e);
                    // Mark subscription as failed if too many failures
                    handleSubscriptionFailure(entity.getId(), e);
                }
            }

            log.info("Completed processing due subscriptions");

        } finally {
            MDC.clear();
        }
    }

    /**
     * Processes a single subscription billing cycle.
     */
    @Transactional
    public SubscriptionPayment processSubscriptionBilling(Subscription subscription) {
        String traceId = getOrCreateTraceId();
        MDC.put("traceId", traceId);
        MDC.put("operation", "processSubscriptionBilling");
        MDC.put("subscriptionId", subscription.getId().toString());

        try {
            log.info("Processing billing for subscription: {} (cycle: {})", 
                    subscription.getMerchantSubscriptionId(), 
                    subscription.getCurrentBillingCycle() + 1);

            // Check subscription status first
            if (subscription.getStatus() != SubscriptionStatus.ACTIVE) {
                throw new IllegalStateException("Only active subscriptions can be billed. Current status: " + subscription.getStatus());
            }

            // Check if subscription has reached max billing cycles
            if (subscription.getMaxBillingCycles() != null &&
                subscription.getCurrentBillingCycle() >= subscription.getMaxBillingCycles()) {
                log.info("Subscription reached max billing cycles, cancelling: {}", subscription.getId());
                subscriptionService.cancelSubscription(subscription.getId());
                throw new IllegalStateException("Subscription reached max billing cycles");
            }

            // Check if subscription has passed end date
            if (subscription.getEndDate() != null && Instant.now().isAfter(subscription.getEndDate())) {
                log.info("Subscription passed end date, cancelling: {}", subscription.getId());
                var expired = subscription.withStatus(SubscriptionStatus.EXPIRED);
                var expiredEntity = subscriptionMapper.toEntity(expired);
                subscriptionRepository.save(expiredEntity);
                throw new IllegalStateException("Subscription has expired");
            }

            // Create order for this billing cycle
            int nextCycle = subscription.getCurrentBillingCycle() + 1;
            String merchantOrderId = subscription.getMerchantSubscriptionId() + "-cycle-" + nextCycle;
            
            Order order = Order.builder()
                    .id(UUID.randomUUID())
                    .merchantOrderId(merchantOrderId)
                    .amount(subscription.getAmount())
                    .description("Subscription billing: " + subscription.getDescription())
                    .customer(new Customer(subscription.getCustomerId() + "@subscription.local", ""))
                    .status(OrderStatus.CREATED)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            order = paymentOrchestratorService.createOrder(order);
            log.info("Created order for subscription billing: orderId={}, merchantOrderId={}", 
                    order.getId(), order.getMerchantOrderId());

            // Create payment for the order
            String idempotencyKey = UUID.randomUUID().toString();
            Payment payment = paymentOrchestratorService.createPayment(
                    order.getId(),
                    PaymentType.PURCHASE,
                    subscription.getGateway(),
                    idempotencyKey
            );
            log.info("Created payment for subscription billing: paymentId={}", payment.getId());

            // Process purchase using customer profile
            PurchaseResponse gatewayResponse = authorizeNetGateway.purchaseWithCustomerProfile(
                    subscription.getCustomerProfileId(),
                    subscription.getPaymentProfileId(),
                    subscription.getAmount().getAmount(),
                    merchantOrderId,
                    subscription.getDescription()
            );

            // CRITICAL: Process the transaction through orchestrator to update payment/order status
            if (gatewayResponse.isSuccess()) {
                log.info("Gateway purchase succeeded for subscription: {}, transactionId={}", 
                        subscription.getMerchantSubscriptionId(), gatewayResponse.getGatewayTransactionId());
                
                // Create transaction record
                PaymentTransaction transaction = PaymentTransaction.builder()
                        .id(UUID.randomUUID())
                        .paymentId(payment.getId())
                        .transactionType(TransactionType.PURCHASE)
                        .transactionState(TransactionState.SUCCESS)
                        .amount(subscription.getAmount())
                        .gatewayTransactionId(gatewayResponse.getGatewayTransactionId())
                        .gatewayResponseCode(gatewayResponse.getResponseCode())
                        .gatewayResponseMsg(gatewayResponse.getResponseMessage())
                        .traceId(UUID.fromString(traceId))
                        .createdAt(Instant.now())
                        .build();

                // Save transaction
                var transactionEntity = paymentTransactionMapper.toEntity(transaction);
                paymentTransactionRepository.save(transactionEntity);
                
                // Update payment status to CAPTURED
                UUID finalPaymentId = payment.getId();
                var paymentEntity = paymentRepository.findByIdWithLock(finalPaymentId)
                        .orElseThrow(() -> new IllegalStateException("Payment not found: " + finalPaymentId));
                Payment updatedPayment = paymentMapper.toDomain(paymentEntity);
                
                // Derive new status from transactions
                var allTransactions = paymentTransactionRepository.findByPaymentIdOrderByCreatedAtAsc(finalPaymentId);
                var transactions = paymentTransactionMapper.toDomainList(allTransactions);
                PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(updatedPayment, transactions);
                
                updatedPayment = updatedPayment.withStatus(newPaymentStatus);
                paymentEntity = paymentMapper.toEntity(updatedPayment);
                paymentRepository.save(paymentEntity);
                
                log.info("Updated payment status to: {}", newPaymentStatus);
                
                // Update order status to COMPLETED
                UUID finalOrderId = order.getId();
                var orderEntity = orderRepository.findById(finalOrderId)
                        .orElseThrow(() -> new IllegalStateException("Order not found: " + finalOrderId));
                Order updatedOrder = orderMapper.toDomain(orderEntity);
                
                OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
                updatedOrder = updatedOrder.withStatus(newOrderStatus);
                orderEntity = orderMapper.toEntity(updatedOrder);
                orderRepository.save(orderEntity);
                
                log.info("Updated order status to: {}", newOrderStatus);
            } else {
                log.error("Gateway purchase failed for subscription: {}, error={}", 
                        subscription.getMerchantSubscriptionId(), gatewayResponse.getResponseMessage());
                throw new RuntimeException("Subscription billing failed: " + gatewayResponse.getResponseMessage());
            }

            // Update subscription billing info
            Instant nextBillingDate = calculateNextBillingDate(
                    subscription.getNextBillingDate(),
                    subscription.getInterval(),
                    subscription.getIntervalCount()
            );

            Subscription updatedSubscription = Subscription.builder()
                    .id(subscription.getId())
                    .customerId(subscription.getCustomerId())
                    .merchantSubscriptionId(subscription.getMerchantSubscriptionId())
                    .amount(subscription.getAmount())
                    .interval(subscription.getInterval())
                    .intervalCount(subscription.getIntervalCount())
                    .status(subscription.getStatus())
                    .gateway(subscription.getGateway())
                    .customerProfileId(subscription.getCustomerProfileId())
                    .paymentProfileId(subscription.getPaymentProfileId())
                    .startDate(subscription.getStartDate())
                    .nextBillingDate(nextBillingDate)
                    .endDate(subscription.getEndDate())
                    .maxBillingCycles(subscription.getMaxBillingCycles())
                    .currentBillingCycle(nextCycle)
                    .description(subscription.getDescription())
                    .idempotencyKey(subscription.getIdempotencyKey())
                    .createdAt(subscription.getCreatedAt())
                    .updatedAt(Instant.now())
                    .build();

            var updatedSubEntity = subscriptionMapper.toEntity(updatedSubscription);
            subscriptionRepository.save(updatedSubEntity);

            // Create subscription payment record
            SubscriptionPayment subscriptionPayment = SubscriptionPayment.builder()
                    .id(UUID.randomUUID())
                    .subscriptionId(subscription.getId())
                    .paymentId(payment.getId())
                    .orderId(order.getId())
                    .billingCycle(nextCycle)
                    .amount(subscription.getAmount())
                    .scheduledDate(subscription.getNextBillingDate())
                    .processedAt(Instant.now())
                    .createdAt(Instant.now())
                    .build();

            var subPaymentEntity = subscriptionPaymentMapper.toEntity(subscriptionPayment);
            subscriptionPaymentRepository.save(subPaymentEntity);

            log.info("Successfully processed subscription billing: {} (cycle: {})", 
                    subscription.getMerchantSubscriptionId(), nextCycle);

            return subscriptionPayment;

        } finally {
            MDC.clear();
        }
    }

    private OrderStatus deriveOrderStatus(PaymentStatus paymentStatus) {
        return switch (paymentStatus) {
            case INITIATED -> OrderStatus.PAYMENT_INITIATED;
            case AUTHORIZED, PARTIALLY_AUTHORIZED -> OrderStatus.PAYMENT_PENDING;
            case CAPTURED -> OrderStatus.COMPLETED;
            case FAILED -> OrderStatus.FAILED;
            case CANCELLED -> OrderStatus.CANCELLED;
        };
    }

    private Instant calculateNextBillingDate(Instant currentDate, RecurrenceInterval interval, Integer intervalCount) {
        if (currentDate == null) {
            currentDate = Instant.now();
        }
        if (intervalCount == null || intervalCount < 1) {
            intervalCount = 1;
        }

        return switch (interval) {
            case DAILY -> currentDate.plus(intervalCount, ChronoUnit.DAYS);
            case WEEKLY -> currentDate.plus(intervalCount * 7L, ChronoUnit.DAYS);
            case MONTHLY -> currentDate.atZone(ZoneId.systemDefault())
                    .plusMonths(intervalCount)
                    .toInstant();
            case YEARLY -> currentDate.atZone(ZoneId.systemDefault())
                    .plusYears(intervalCount)
                    .toInstant();
        };
    }

    /**
     * Manually triggers billing for a specific subscription.
     */
    @Transactional
    public SubscriptionPayment triggerBilling(UUID subscriptionId) {
        Subscription subscription = subscriptionService.getSubscriptionById(subscriptionId);
        
        if (subscription.getStatus() != SubscriptionStatus.ACTIVE) {
            throw new IllegalStateException("Only active subscriptions can be billed");
        }

        return processSubscriptionBilling(subscription);
    }

    /**
     * Handles subscription failure (e.g., payment declined).
     */
    private void handleSubscriptionFailure(UUID subscriptionId, Exception error) {
        try {
            var entity = subscriptionRepository.findByIdWithLock(subscriptionId).orElse(null);
            if (entity == null) {
                return;
            }
            
            // For now, we'll just log the failure
            // In a production system, you might want to:
            // - Track failure count
            // - Cancel after N consecutive failures
            // - Send notifications
            
            log.warn("Subscription billing failed: {} - {}", subscriptionId, error.getMessage());
            
            // Optionally mark as failed after multiple failures
            // For basic implementation, we'll leave it active for retry

        } catch (Exception e) {
            log.error("Error handling subscription failure: {}", subscriptionId, e);
        }
    }

    private String getOrCreateTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString();
        }
        return traceId;
    }
}

