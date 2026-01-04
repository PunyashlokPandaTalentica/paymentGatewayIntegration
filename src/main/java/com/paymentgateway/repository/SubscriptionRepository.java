package com.paymentgateway.repository;

import com.paymentgateway.domain.entity.SubscriptionEntity;
import com.paymentgateway.domain.enums.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<SubscriptionEntity, UUID> {
    Optional<SubscriptionEntity> findByMerchantSubscriptionId(String merchantSubscriptionId);
    Optional<SubscriptionEntity> findByIdempotencyKey(String idempotencyKey);
    List<SubscriptionEntity> findByCustomerId(String customerId);
    List<SubscriptionEntity> findByStatus(SubscriptionStatus status);
    
    @Query("SELECT s FROM SubscriptionEntity s WHERE s.status = :status AND s.nextBillingDate <= :date")
    List<SubscriptionEntity> findDueForBilling(@Param("status") SubscriptionStatus status, @Param("date") Instant date);
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SubscriptionEntity s WHERE s.id = :id")
    Optional<SubscriptionEntity> findByIdWithLock(@Param("id") UUID id);
    
    boolean existsByMerchantSubscriptionId(String merchantSubscriptionId);
    boolean existsByIdempotencyKey(String idempotencyKey);
}

