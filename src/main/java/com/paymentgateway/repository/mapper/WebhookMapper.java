package com.paymentgateway.repository.mapper;

import com.paymentgateway.domain.entity.WebhookEventEntity;
import com.paymentgateway.domain.model.WebhookEvent;
import org.springframework.stereotype.Component;

@Component
public class WebhookMapper {

    public WebhookEventEntity toEntity(WebhookEvent webhook) {
        return WebhookEventEntity.builder()
                .id(webhook.getId())
                .gateway(webhook.getGateway())
                .eventType(webhook.getEventType())
                .gatewayEventId(webhook.getGatewayEventId())
                .payload(webhook.getPayload())
                .signatureVerified(webhook.isSignatureVerified())
                .processed(webhook.isProcessed())
                .processedAt(webhook.getProcessedAt())
                .createdAt(webhook.getCreatedAt())
                .build();
    }

    public WebhookEvent toDomain(WebhookEventEntity entity) {
        return WebhookEvent.builder()
                .id(entity.getId())
                .gateway(entity.getGateway())
                .eventType(entity.getEventType())
                .gatewayEventId(entity.getGatewayEventId())
                .payload(entity.getPayload())
                .signatureVerified(entity.getSignatureVerified())
                .processed(entity.getProcessed())
                .processedAt(entity.getProcessedAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}




