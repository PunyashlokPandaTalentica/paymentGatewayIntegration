package com.paymentgateway.repository;

import com.paymentgateway.domain.entity.PaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {
    Optional<PaymentEntity> findByOrderId(UUID orderId);
    Optional<PaymentEntity> findByIdempotencyKey(String idempotencyKey);
    boolean existsByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentEntity p WHERE p.id = :id")
    Optional<PaymentEntity> findByIdWithLock(UUID id);
}

