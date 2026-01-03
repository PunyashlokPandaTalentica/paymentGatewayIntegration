package com.paymentgateway.service;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.entity.PaymentEntity;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.RetryableGatewayService;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.*;
import com.paymentgateway.repository.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

@Service
@Slf4j
public class PaymentOrchestratorService {

    @Autowired
    private OrderRepository orderRepository;
    
    @Autowired
    private PaymentRepository paymentRepository;
    
    @Autowired
    private PaymentTransactionRepository transactionRepository;
    
    @Autowired
    private PaymentStateMachine stateMachine;
    
    @Autowired
    private RetryableGatewayService retryableGatewayService;
    
    @Autowired
    private OrderMapper orderMapper;
    
    @Autowired
    private PaymentMapper paymentMapper;
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Transactional
    public Order createOrder(Order order) {
        String traceId = getOrCreateTraceId();
        MDC.put("traceId", traceId);
        MDC.put("operation", "createOrder");
        MDC.put("merchantOrderId", order.getMerchantOrderId());
        
        try {
            log.info("Creating order with merchantOrderId: {}", order.getMerchantOrderId());
            if (orderRepository.existsByMerchantOrderId(order.getMerchantOrderId())) {
                throw new IllegalArgumentException("Order with merchantOrderId already exists: " + order.getMerchantOrderId());
            }
            var entity = orderMapper.toEntity(order);
            var saved = orderRepository.save(entity);
            var result = orderMapper.toDomain(saved);
            log.info("Order created successfully: {}", result.getId());
            return result;
        } finally {
            MDC.clear();
        }
    }

    public Order getOrderById(UUID orderId) {
        return orderRepository.findById(orderId)
                .map(orderMapper::toDomain)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    @Transactional
    public Payment createPayment(UUID orderId, PaymentType paymentType, Gateway gateway, String idempotencyKey) {
        // Check idempotency
        if (paymentRepository.existsByIdempotencyKey(idempotencyKey)) {
            var entity = paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Payment exists but not found"));
            return paymentMapper.toDomain(entity);
        }

        // Verify order exists
        var orderEntity = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        var order = orderMapper.toDomain(orderEntity);

        // Enforce one payment per order
        if (paymentRepository.findByOrderId(orderId).isPresent()) {
            throw new IllegalStateException("Payment already exists for order: " + orderId);
        }

        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .paymentType(paymentType)
                .status(PaymentStatus.INITIATED)
                .amount(order.getAmount())
                .gateway(gateway)
                .idempotencyKey(idempotencyKey)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        var paymentEntity = paymentMapper.toEntity(payment);
        var savedEntity = paymentRepository.save(paymentEntity);
        payment = paymentMapper.toDomain(savedEntity);

        // Update order status
        var updatedOrder = order.withStatus(OrderStatus.PAYMENT_INITIATED);
        var updatedOrderEntity = orderMapper.toEntity(updatedOrder);
        orderRepository.save(updatedOrderEntity);

        return payment;
    }

    @Transactional
    public PaymentTransaction processPurchase(UUID paymentId, String paymentMethodToken, UUID traceId) {
        var paymentEntity = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
        var payment = paymentMapper.toDomain(paymentEntity);

        if (!stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)) {
            throw new IllegalStateException("Cannot create PURCHASE transaction for payment in state: " + payment.getStatus());
        }

        var orderEntity = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));
        var order = orderMapper.toDomain(orderEntity);

        // Create transaction record
        PaymentTransaction transaction = PaymentTransaction.builder()
                .id(UUID.randomUUID())
                .paymentId(paymentId)
                .transactionType(TransactionType.PURCHASE)
                .transactionState(TransactionState.REQUESTED)
                .amount(payment.getAmount())
                .traceId(traceId)
                .createdAt(Instant.now())
                .build();

        var transactionEntity = transactionMapper.toEntity(transaction);
        transactionRepository.save(transactionEntity);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getMerchantOrderId(),
                order.getDescription()
        );

        PurchaseResponse gatewayResponse = retryableGatewayService.purchase(gatewayRequest);

        // Update transaction with gateway response
        TransactionState newState = gatewayResponse.isSuccess() 
                ? TransactionState.SUCCESS 
                : TransactionState.FAILED;
        
        transaction = transaction.withState(newState)
                .withGatewayResponse(
                        gatewayResponse.getGatewayTransactionId(),
                        gatewayResponse.getResponseCode(),
                        gatewayResponse.getResponseMessage()
                );
        var updatedTransactionEntity = transactionMapper.toEntity(transaction);
        transactionRepository.save(updatedTransactionEntity);

        // Derive and update payment status
        var transactionEntities = transactionRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId);
        var transactions = transactionMapper.toDomainList(transactionEntities);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        var updatedPaymentEntity = updatePaymentWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        var updatedPayment = paymentMapper.toDomain(updatedPaymentEntity);
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        var updatedOrder = order.withStatus(newOrderStatus);
        var updatedOrderEntity = orderMapper.toEntity(updatedOrder);
        orderRepository.save(updatedOrderEntity);

        return transaction;
    }
    
    private PaymentEntity updatePaymentWithLock(UUID id, Function<Payment, Payment> updater) {
        var entity = paymentRepository.findByIdWithLock(id)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + id));
        var payment = paymentMapper.toDomain(entity);
        var updated = updater.apply(payment);
        var updatedEntity = paymentMapper.toEntity(updated);
        return paymentRepository.save(updatedEntity);
    }

    @Transactional
    public PaymentTransaction processAuthorize(UUID paymentId, String paymentMethodToken, UUID traceId) {
        var paymentEntity = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
        var payment = paymentMapper.toDomain(paymentEntity);

        if (!stateMachine.canCreateTransaction(payment, TransactionType.AUTH)) {
            throw new IllegalStateException("Cannot create AUTH transaction for payment in state: " + payment.getStatus());
        }

        var orderEntity = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));
        var order = orderMapper.toDomain(orderEntity);

        // Create transaction record
        PaymentTransaction transaction = PaymentTransaction.builder()
                .id(UUID.randomUUID())
                .paymentId(paymentId)
                .transactionType(TransactionType.AUTH)
                .transactionState(TransactionState.REQUESTED)
                .amount(payment.getAmount())
                .traceId(traceId)
                .createdAt(Instant.now())
                .build();

        var transactionEntity = transactionMapper.toEntity(transaction);
        transactionRepository.save(transactionEntity);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getMerchantOrderId(),
                order.getDescription()
        );

        AuthResponse gatewayResponse = retryableGatewayService.authorize(gatewayRequest);

        // Update transaction with gateway response
        TransactionState newState = gatewayResponse.isSuccess() 
                ? TransactionState.AUTHORIZED 
                : TransactionState.FAILED;
        
        transaction = transaction.withState(newState)
                .withGatewayResponse(
                        gatewayResponse.getGatewayTransactionId(),
                        gatewayResponse.getResponseCode(),
                        gatewayResponse.getResponseMessage()
                );
        var updatedTransactionEntity = transactionMapper.toEntity(transaction);
        transactionRepository.save(updatedTransactionEntity);

        // Derive and update payment status
        var transactionEntities = transactionRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId);
        var transactions = transactionMapper.toDomainList(transactionEntities);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        var updatedPaymentEntity = updatePaymentWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        var updatedPayment = paymentMapper.toDomain(updatedPaymentEntity);
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        var updatedOrder = order.withStatus(newOrderStatus);
        var updatedOrderEntity = orderMapper.toEntity(updatedOrder);
        orderRepository.save(updatedOrderEntity);

        return transaction;
    }

    @Transactional
    public PaymentTransaction processCapture(UUID paymentId, UUID parentTransactionId, Money amount, UUID traceId) {
        var paymentEntity = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
        var payment = paymentMapper.toDomain(paymentEntity);

        if (!stateMachine.canCreateTransaction(payment, TransactionType.CAPTURE)) {
            throw new IllegalStateException("Cannot create CAPTURE transaction for payment in state: " + payment.getStatus());
        }

        var parentTransactionEntity = transactionRepository.findById(parentTransactionId)
                .orElseThrow(() -> new IllegalArgumentException("Parent transaction not found: " + parentTransactionId));
        var parentTransaction = transactionMapper.toDomain(parentTransactionEntity);

        if (!parentTransaction.getPaymentId().equals(paymentId)) {
            throw new IllegalArgumentException("Parent transaction does not belong to this payment");
        }

        if (parentTransaction.getTransactionType() != TransactionType.AUTH) {
            throw new IllegalStateException("Can only capture AUTH transactions");
        }

        // Create transaction record
        PaymentTransaction transaction = PaymentTransaction.builder()
                .id(UUID.randomUUID())
                .paymentId(paymentId)
                .transactionType(TransactionType.CAPTURE)
                .transactionState(TransactionState.REQUESTED)
                .amount(amount)
                .parentTransactionId(parentTransactionId)
                .traceId(traceId)
                .createdAt(Instant.now())
                .build();

        var transactionEntity = transactionMapper.toEntity(transaction);
        transactionRepository.save(transactionEntity);

        // Call gateway
        CaptureResponse gatewayResponse = retryableGatewayService.capture(
                parentTransaction.getGatewayTransactionId(),
                amount.getAmountCents(),
                amount.getCurrencyCode()
        );

        // Update transaction with gateway response
        TransactionState newState = gatewayResponse.isSuccess() 
                ? TransactionState.SUCCESS 
                : TransactionState.FAILED;
        
        transaction = transaction.withState(newState)
                .withGatewayResponse(
                        gatewayResponse.getGatewayTransactionId(),
                        gatewayResponse.getResponseCode(),
                        gatewayResponse.getResponseMessage()
                );
        var updatedTransactionEntity = transactionMapper.toEntity(transaction);
        transactionRepository.save(updatedTransactionEntity);

        // Derive and update payment status
        var transactionEntities = transactionRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId);
        var transactions = transactionMapper.toDomainList(transactionEntities);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        var updatedPaymentEntity = updatePaymentWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        var updatedPayment = paymentMapper.toDomain(updatedPaymentEntity);
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        var orderEntity = orderRepository.findById(updatedPayment.getOrderId()).orElse(null);
        if (orderEntity != null) {
            var order = orderMapper.toDomain(orderEntity);
            var updatedOrder = order.withStatus(newOrderStatus);
            var updatedOrderEntity = orderMapper.toEntity(updatedOrder);
            orderRepository.save(updatedOrderEntity);
        }

        return transaction;
    }

    private OrderStatus deriveOrderStatus(PaymentStatus paymentStatus) {
        return switch (paymentStatus) {
            case INITIATED -> OrderStatus.PAYMENT_INITIATED;
            case AUTHORIZED, PARTIALLY_AUTHORIZED -> OrderStatus.PAYMENT_PENDING;
            case CAPTURED -> OrderStatus.COMPLETED;
            case FAILED -> OrderStatus.FAILED;
            case CANCELLED -> OrderStatus.CANCELLED;
        };
    }

    private String getOrCreateTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString();
        }
        return traceId;
    }
}

