package com.paymentgateway.domain.entity;

import com.paymentgateway.domain.enums.Gateway;
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
@Table(name = "webhooks", indexes = {
    @Index(name = "idx_webhook_event", columnList = "gateway_event_id", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookEventEntity {
    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "gateway", nullable = false, length = 30)
    private Gateway gateway;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "gateway_event_id", unique = true, nullable = false, length = 100)
    private String gatewayEventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "signature_verified", nullable = false)
    private Boolean signatureVerified;

    @Column(name = "processed", nullable = false)
    private Boolean processed;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

