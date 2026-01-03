package com.paymentgateway.repository;

import com.paymentgateway.domain.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {
    Optional<OrderEntity> findByMerchantOrderId(String merchantOrderId);
    boolean existsByMerchantOrderId(String merchantOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OrderEntity o WHERE o.id = :id")
    Optional<OrderEntity> findByIdWithLock(UUID id);
}

