package com.paymentgateway.repository;

import com.paymentgateway.domain.entity.WebhookEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookRepository extends JpaRepository<WebhookEventEntity, UUID> {
    Optional<WebhookEventEntity> findByGatewayEventId(String gatewayEventId);
    boolean existsByGatewayEventId(String gatewayEventId);
}

