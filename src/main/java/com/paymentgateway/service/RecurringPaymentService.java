package com.paymentgateway.service;

import com.paymentgateway.domain.entity.SubscriptionEntity;
import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.repository.*;
import com.paymentgateway.repository.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    private OrderMapper orderMapper;

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
                    .description(subscription.getDescription() != null ? 
                            subscription.getDescription() + " - Billing Cycle " + nextCycle : 
                            "Subscription billing cycle " + nextCycle)
                    .customer(new Customer(null, null)) // Customer info can be enhanced
                    .status(OrderStatus.CREATED)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            var orderEntity = orderMapper.toEntity(order);
            var savedOrderEntity = orderRepository.save(orderEntity);
            order = orderMapper.toDomain(savedOrderEntity);

            // Create payment intent
            String idempotencyKey = subscription.getId().toString() + "-cycle-" + nextCycle;
            Payment payment = paymentOrchestratorService.createPayment(
                    order.getId(),
                    PaymentType.PURCHASE,
                    subscription.getGateway(),
                    idempotencyKey);

            // Process purchase transaction
            UUID traceIdForTransaction = UUID.randomUUID();
            paymentOrchestratorService.processPurchase(
                    payment.getId(),
                    subscription.getPaymentMethodToken(),
                    traceIdForTransaction);

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

            var subscriptionPaymentEntity = subscriptionPaymentMapper.toEntity(subscriptionPayment);
            var savedSubscriptionPaymentEntity = subscriptionPaymentRepository.save(subscriptionPaymentEntity);
            var savedSubscriptionPayment = subscriptionPaymentMapper.toDomain(savedSubscriptionPaymentEntity);

            // Update subscription's next billing date and increment cycle
            subscriptionService.updateNextBillingDate(subscription.getId());

            log.info("Successfully processed subscription billing: {} (cycle: {})", 
                    subscription.getMerchantSubscriptionId(), nextCycle);

            return savedSubscriptionPayment;

        } finally {
            MDC.clear();
        }
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

