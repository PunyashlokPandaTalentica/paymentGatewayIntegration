package com.paymentgateway.repository.mapper;

import com.paymentgateway.domain.entity.FailedWebhookEntity;
import com.paymentgateway.domain.model.FailedWebhook;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class FailedWebhookMapper {

    public FailedWebhookEntity toEntity(FailedWebhook failedWebhook) {
        return FailedWebhookEntity.builder()
                .id(failedWebhook.getId())
                .webhookId(failedWebhook.getWebhookId())
                .gatewayEventId(failedWebhook.getGatewayEventId())
                .eventType(failedWebhook.getEventType())
                .payload(failedWebhook.getPayload())
                .errorMessage(failedWebhook.getErrorMessage())
                .errorStackTrace(failedWebhook.getErrorStackTrace())
                .retryCount(failedWebhook.getRetryCount())
                .lastRetryAt(failedWebhook.getLastRetryAt())
                .createdAt(failedWebhook.getCreatedAt())
                .updatedAt(failedWebhook.getUpdatedAt())
                .build();
    }

    public FailedWebhook toDomain(FailedWebhookEntity entity) {
        return FailedWebhook.builder()
                .id(entity.getId())
                .webhookId(entity.getWebhookId())
                .gatewayEventId(entity.getGatewayEventId())
                .eventType(entity.getEventType())
                .payload(entity.getPayload())
                .errorMessage(entity.getErrorMessage())
                .errorStackTrace(entity.getErrorStackTrace())
                .retryCount(entity.getRetryCount())
                .lastRetryAt(entity.getLastRetryAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public List<FailedWebhook> toDomainList(List<FailedWebhookEntity> entities) {
        return entities.stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }
}

