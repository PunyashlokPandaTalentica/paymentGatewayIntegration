package com.paymentgateway.domain.model;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Value
@Builder
public class FailedWebhook {
    UUID id;
    UUID webhookId;
    String gatewayEventId;
    String eventType;
    Map<String, Object> payload;
    String errorMessage;
    String errorStackTrace;
    Integer retryCount;
    Instant lastRetryAt;
    Instant createdAt;
    Instant updatedAt;

    public FailedWebhook incrementRetryCount() {
        return FailedWebhook.builder()
                .id(this.id)
                .webhookId(this.webhookId)
                .gatewayEventId(this.gatewayEventId)
                .eventType(this.eventType)
                .payload(this.payload)
                .errorMessage(this.errorMessage)
                .errorStackTrace(this.errorStackTrace)
                .retryCount(this.retryCount + 1)
                .lastRetryAt(Instant.now())
                .createdAt(this.createdAt)
                .updatedAt(Instant.now())
                .build();
    }
}

