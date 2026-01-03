package com.paymentgateway.service;

import com.paymentgateway.domain.model.FailedWebhook;
import com.paymentgateway.domain.model.WebhookEvent;
import com.paymentgateway.repository.FailedWebhookRepository;
import com.paymentgateway.repository.mapper.FailedWebhookMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for managing failed webhooks in a dead letter queue.
 * Provides functionality to store, retrieve, and retry failed webhooks.
 */
@Service
@Slf4j
public class DeadLetterQueueService {

    @Autowired
    private FailedWebhookRepository failedWebhookRepository;

    @Autowired
    private FailedWebhookMapper failedWebhookMapper;

    @Value("${app.webhook.max-retry-count:3}")
    private int maxRetryCount;

    /**
     * Adds a failed webhook to the dead letter queue.
     */
    @Transactional
    public FailedWebhook addFailedWebhook(WebhookEvent webhook, Exception error) {
        String traceId = getOrCreateTraceId();
        MDC.put("traceId", traceId);
        MDC.put("webhookId", webhook.getId().toString());
        MDC.put("gatewayEventId", webhook.getGatewayEventId());

        try {
            // Check if already exists
            var existingEntity = failedWebhookRepository.findByWebhookId(webhook.getId());
            if (existingEntity.isPresent()) {
                var existing = failedWebhookMapper.toDomain(existingEntity.get());
                if (existing.getRetryCount() >= maxRetryCount) {
                    log.warn("Webhook already in dead letter queue with max retries: {}", webhook.getGatewayEventId());
                    return existing;
                }
                // Increment retry count
                var updated = existing.incrementRetryCount();
                var updatedEntity = failedWebhookMapper.toEntity(updated);
                failedWebhookRepository.save(updatedEntity);
                log.info("Incremented retry count for failed webhook: {} (count: {})", 
                        webhook.getGatewayEventId(), updated.getRetryCount());
                return updated;
            }

            // Create new failed webhook entry
            String errorMessage = error != null ? error.getMessage() : "Unknown error";
            String stackTrace = getStackTrace(error);

            FailedWebhook failedWebhook = FailedWebhook.builder()
                    .id(UUID.randomUUID())
                    .webhookId(webhook.getId())
                    .gatewayEventId(webhook.getGatewayEventId())
                    .eventType(webhook.getEventType())
                    .payload(webhook.getPayload())
                    .errorMessage(errorMessage)
                    .errorStackTrace(stackTrace)
                    .retryCount(1)
                    .lastRetryAt(Instant.now())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            var entity = failedWebhookMapper.toEntity(failedWebhook);
            var savedEntity = failedWebhookRepository.save(entity);
            var saved = failedWebhookMapper.toDomain(savedEntity);

            log.error("Added webhook to dead letter queue: {} (retry count: {})", 
                    webhook.getGatewayEventId(), saved.getRetryCount());

            return saved;
        } finally {
            MDC.clear();
        }
    }

    /**
     * Retrieves all failed webhooks that can be retried (retry count < max).
     */
    @Transactional(readOnly = true)
    public List<FailedWebhook> getRetryableFailedWebhooks() {
        return failedWebhookRepository.findByRetryCountLessThanOrderByCreatedAtAsc(maxRetryCount)
                .stream()
                .map(failedWebhookMapper::toDomain)
                .collect(Collectors.toList());
    }

    /**
     * Retrieves a failed webhook by webhook ID.
     */
    @Transactional(readOnly = true)
    public FailedWebhook getFailedWebhookByWebhookId(UUID webhookId) {
        return failedWebhookRepository.findByWebhookId(webhookId)
                .map(failedWebhookMapper::toDomain)
                .orElse(null);
    }

    /**
     * Retrieves a failed webhook by gateway event ID.
     */
    @Transactional(readOnly = true)
    public FailedWebhook getFailedWebhookByGatewayEventId(String gatewayEventId) {
        return failedWebhookRepository.findByGatewayEventId(gatewayEventId)
                .map(failedWebhookMapper::toDomain)
                .orElse(null);
    }

    /**
     * Checks if a webhook should be retried based on retry count.
     */
    public boolean shouldRetry(FailedWebhook failedWebhook) {
        return failedWebhook != null && failedWebhook.getRetryCount() < maxRetryCount;
    }

    /**
     * Increments the retry count for a failed webhook.
     */
    @Transactional
    public FailedWebhook incrementRetryCount(FailedWebhook failedWebhook) {
        var updated = failedWebhook.incrementRetryCount();
        var entity = failedWebhookMapper.toEntity(updated);
        var savedEntity = failedWebhookRepository.save(entity);
        log.info("Incremented retry count for failed webhook: {} (count: {})", 
                failedWebhook.getGatewayEventId(), updated.getRetryCount());
        return failedWebhookMapper.toDomain(savedEntity);
    }

    /**
     * Removes a failed webhook from the dead letter queue (e.g., after successful retry).
     */
    @Transactional
    public void removeFailedWebhook(UUID failedWebhookId) {
        failedWebhookRepository.deleteById(failedWebhookId);
        log.info("Removed failed webhook from dead letter queue: {}", failedWebhookId);
    }

    private String getStackTrace(Exception e) {
        if (e == null) {
            return null;
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }

    private String getOrCreateTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString();
        }
        return traceId;
    }
}

