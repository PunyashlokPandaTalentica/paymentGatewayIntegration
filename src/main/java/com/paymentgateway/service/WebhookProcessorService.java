package com.paymentgateway.service;

import com.paymentgateway.api.exception.WebhookProcessingException;
import com.paymentgateway.domain.enums.TransactionState;
import com.paymentgateway.domain.model.FailedWebhook;
import com.paymentgateway.domain.model.PaymentTransaction;
import com.paymentgateway.domain.model.WebhookEvent;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.repository.PaymentRepository;
import com.paymentgateway.repository.PaymentTransactionRepository;
import com.paymentgateway.repository.WebhookRepository;
import com.paymentgateway.repository.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class WebhookProcessorService {

    @Autowired
    private WebhookRepository webhookRepository;
    
    @Autowired
    private PaymentTransactionRepository transactionRepository;
    
    @Autowired
    private PaymentRepository paymentRepository;
    
    @Autowired
    private PaymentStateMachine stateMachine;
    
    @Autowired
    private WebhookMapper webhookMapper;
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;
    
    @Autowired
    private PaymentMapper paymentMapper;

    @Autowired
    private DeadLetterQueueService deadLetterQueueService;

    @Value("${app.webhook.max-retry-count:3}")
    private int maxRetryCount;

    /**
     * Processes a webhook event asynchronously.
     * Webhooks are authoritative and can advance state even if API calls failed.
     * Failed webhooks are added to the dead letter queue.
     */
    @Transactional
    public void processWebhook(WebhookEvent webhook) {
        String traceId = getOrCreateTraceId();
        MDC.put("traceId", traceId);
        MDC.put("webhookId", webhook.getId().toString());
        MDC.put("gatewayEventId", webhook.getGatewayEventId());
        MDC.put("eventType", webhook.getEventType());

        log.info("Processing webhook event: {} (type: {})", webhook.getGatewayEventId(), webhook.getEventType());

        FailedWebhook failedWebhook = null;
        try {
            // Check if already processed
            if (webhook.isProcessed()) {
                log.info("Webhook already processed: {}", webhook.getGatewayEventId());
                return;
            }

            // Check if this webhook is in the dead letter queue
            failedWebhook = deadLetterQueueService.getFailedWebhookByWebhookId(webhook.getId());
            if (failedWebhook != null && !deadLetterQueueService.shouldRetry(failedWebhook)) {
                log.warn("Webhook exceeded max retry count, skipping: {} (retry count: {})", 
                        webhook.getGatewayEventId(), failedWebhook.getRetryCount());
                return;
            }

            // Process based on event type
            String eventType = webhook.getEventType();
            if (eventType.startsWith("TRANSACTION.")) {
                processTransactionWebhook(webhook);
            } else {
                log.warn("Unknown webhook event type: {}", eventType);
                // Unknown event types are not considered errors, just log and mark as processed
            }

            // Mark as processed
            WebhookEvent processed = webhook.markAsProcessed();
            var entity = webhookMapper.toEntity(processed);
            webhookRepository.save(entity);

            // If this was a retry from dead letter queue, remove it
            if (failedWebhook != null) {
                deadLetterQueueService.removeFailedWebhook(failedWebhook.getId());
                log.info("Successfully processed webhook from dead letter queue: {}", webhook.getGatewayEventId());
            }

            log.info("Successfully processed webhook: {}", webhook.getGatewayEventId());

        } catch (Exception e) {
            log.error("Error processing webhook: {} (attempt: {})", 
                    webhook.getGatewayEventId(), 
                    failedWebhook != null ? failedWebhook.getRetryCount() + 1 : 1, 
                    e);

            // Add to dead letter queue
            FailedWebhook savedFailed = deadLetterQueueService.addFailedWebhook(webhook, e);
            
            boolean retryable = savedFailed.getRetryCount() < maxRetryCount;
            throw new WebhookProcessingException(
                "Failed to process webhook: " + e.getMessage(),
                webhook.getId(),
                webhook.getGatewayEventId(),
                retryable,
                e
            );
        } finally {
            MDC.clear();
        }
    }

    private void processTransactionWebhook(WebhookEvent webhook) {
        Map<String, Object> payload = webhook.getPayload();
        String transactionRefId = (String) payload.get("transactionReferenceId");
        
        if (transactionRefId == null) {
            log.warn("No transaction reference ID in webhook payload");
            return;
        }

        // Find transaction by gateway transaction ID
        var transactionEntity = transactionRepository
                .findByGatewayTransactionId(transactionRefId)
                .orElse(null);

        if (transactionEntity == null) {
            log.warn("Transaction not found for gateway ID: {}", transactionRefId);
            return;
        }
        
        var transaction = transactionMapper.toDomain(transactionEntity);

        // Update transaction state based on webhook event type
        TransactionState newState = deriveTransactionStateFromWebhook(webhook.getEventType(), payload);
        
        if (newState != null && newState != transaction.getTransactionState()) {
            PaymentTransaction updated = transaction.withState(newState);
            var updatedEntity = transactionMapper.toEntity(updated);
            transactionRepository.save(updatedEntity);

            // Derive and update payment status
            var transactionEntities = transactionRepository.findByPaymentIdOrderByCreatedAtAsc(transaction.getPaymentId());
            var transactions = transactionMapper.toDomainList(transactionEntities);
            paymentRepository.findById(transaction.getPaymentId()).ifPresent(entity -> {
                var payment = paymentMapper.toDomain(entity);
                com.paymentgateway.domain.enums.PaymentStatus newPaymentStatus = 
                        stateMachine.derivePaymentStatus(payment, transactions);
                var updatedPayment = payment.withStatus(newPaymentStatus);
                var updatedPaymentEntity = paymentMapper.toEntity(updatedPayment);
                paymentRepository.save(updatedPaymentEntity);
            });
        }
    }

    private TransactionState deriveTransactionStateFromWebhook(String eventType, Map<String, Object> payload) {
        return switch (eventType) {
            case "TRANSACTION.SETTLED" -> TransactionState.SETTLED;
            case "TRANSACTION.AUTHORIZED" -> TransactionState.AUTHORIZED;
            case "TRANSACTION.CAPTURED" -> TransactionState.SUCCESS;
            case "TRANSACTION.DECLINED", "TRANSACTION.ERROR" -> TransactionState.FAILED;
            case "TRANSACTION.PENDING" -> TransactionState.PENDING;
            default -> null;
        };
    }

    private String getOrCreateTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString();
        }
        return traceId;
    }
}

