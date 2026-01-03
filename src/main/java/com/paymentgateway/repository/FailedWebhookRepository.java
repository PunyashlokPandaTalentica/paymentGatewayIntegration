package com.paymentgateway.repository;

import com.paymentgateway.domain.entity.FailedWebhookEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FailedWebhookRepository extends JpaRepository<FailedWebhookEntity, UUID> {
    Optional<FailedWebhookEntity> findByWebhookId(UUID webhookId);
    Optional<FailedWebhookEntity> findByGatewayEventId(String gatewayEventId);
    List<FailedWebhookEntity> findByRetryCountLessThanOrderByCreatedAtAsc(Integer maxRetryCount);
}

