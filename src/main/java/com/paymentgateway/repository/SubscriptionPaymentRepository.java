package com.paymentgateway.repository;

import com.paymentgateway.domain.entity.SubscriptionPaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionPaymentRepository extends JpaRepository<SubscriptionPaymentEntity, UUID> {
    List<SubscriptionPaymentEntity> findBySubscriptionId(UUID subscriptionId);
    Optional<SubscriptionPaymentEntity> findBySubscriptionIdAndBillingCycle(UUID subscriptionId, Integer billingCycle);
    List<SubscriptionPaymentEntity> findByPaymentId(UUID paymentId);
    List<SubscriptionPaymentEntity> findByOrderId(UUID orderId);
}

