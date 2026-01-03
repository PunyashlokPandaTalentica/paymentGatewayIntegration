package com.paymentgateway.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "failed_webhooks", indexes = {
    @Index(name = "idx_failed_webhooks_webhook_id", columnList = "webhook_id"),
    @Index(name = "idx_failed_webhooks_gateway_event_id", columnList = "gateway_event_id"),
    @Index(name = "idx_failed_webhooks_created_at", columnList = "created_at"),
    @Index(name = "idx_failed_webhooks_retry_count", columnList = "retry_count")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailedWebhookEntity {
    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "webhook_id", nullable = false, columnDefinition = "UUID")
    private UUID webhookId;

    @Column(name = "gateway_event_id", nullable = false, length = 100)
    private String gatewayEventId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "error_stack_trace", columnDefinition = "TEXT")
    private String errorStackTrace;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "last_retry_at")
    private Instant lastRetryAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

