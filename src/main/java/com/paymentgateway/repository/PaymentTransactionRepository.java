package com.paymentgateway.repository;

import com.paymentgateway.domain.entity.PaymentTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransactionEntity, UUID> {
    List<PaymentTransactionEntity> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);
    Optional<PaymentTransactionEntity> findByGatewayTransactionId(String gatewayTransactionId);
}

