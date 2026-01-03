package com.paymentgateway.domain.model;

import com.paymentgateway.domain.enums.Gateway;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Value
@Builder
public class WebhookEvent {
    UUID id;
    Gateway gateway;
    String eventType;
    String gatewayEventId;
    Map<String, Object> payload;
    boolean signatureVerified;
    boolean processed;
    Instant processedAt;
    Instant createdAt;

    public WebhookEvent markAsProcessed() {
        return WebhookEvent.builder()
                .id(this.id)
                .gateway(this.gateway)
                .eventType(this.eventType)
                .gatewayEventId(this.gatewayEventId)
                .payload(this.payload)
                .signatureVerified(this.signatureVerified)
                .processed(true)
                .processedAt(Instant.now())
                .createdAt(this.createdAt)
                .build();
    }
}

