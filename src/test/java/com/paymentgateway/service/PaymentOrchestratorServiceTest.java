package com.paymentgateway.service;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.Customer;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.domain.model.Payment;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.OrderRepository;
import com.paymentgateway.repository.PaymentRepository;
import com.paymentgateway.repository.PaymentTransactionRepository;
import com.paymentgateway.repository.mapper.OrderMapper;
import com.paymentgateway.repository.mapper.PaymentMapper;
import com.paymentgateway.repository.mapper.PaymentTransactionMapper;
import com.paymentgateway.domain.entity.OrderEntity;
import com.paymentgateway.domain.entity.PaymentEntity;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentOrchestratorServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentTransactionRepository transactionRepository;

    @Mock
    private PaymentStateMachine stateMachine;

    @Mock
    private com.paymentgateway.gateway.RetryableGatewayService retryableGatewayService;
    
    @Mock
    private OrderMapper orderMapper;
    
    @Mock
    private PaymentMapper paymentMapper;
    
    @Mock
    private PaymentTransactionMapper transactionMapper;

    @InjectMocks
    private PaymentOrchestratorService service;
    
    private Order order;
    private Money amount;

    @BeforeEach
    void setUp() {

        amount = new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD"));
        order = Order.builder()
                .id(UUID.randomUUID())
                .merchantOrderId("ORD-123")
                .amount(amount)
                .description("Test order")
                .customer(new Customer("test@example.com", "+1234567890"))
                .status(OrderStatus.CREATED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void testCreateOrder_Success() {
        when(orderRepository.existsByMerchantOrderId(anyString())).thenReturn(false);
        
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setId(order.getId());
        orderEntity.setMerchantOrderId(order.getMerchantOrderId());
        orderEntity.setAmountCents(order.getAmount().getAmountCents());
        orderEntity.setCurrency(order.getAmount().getCurrencyCode());
        orderEntity.setDescription(order.getDescription());
        orderEntity.setCustomerEmail(order.getCustomer().getEmail());
        orderEntity.setCustomerPhone(order.getCustomer().getPhone());
        orderEntity.setStatus(order.getStatus());
        orderEntity.setCreatedAt(order.getCreatedAt());
        orderEntity.setUpdatedAt(order.getUpdatedAt());
        
        when(orderMapper.toEntity(any(Order.class))).thenReturn(orderEntity);
        when(orderRepository.save(any(OrderEntity.class))).thenReturn(orderEntity);
        when(orderMapper.toDomain(any(OrderEntity.class))).thenReturn(order);

        Order created = service.createOrder(order);

        assertNotNull(created);
        assertEquals(order.getId(), created.getId());
        verify(orderRepository).save(any());
    }

    @Test
    void testCreateOrder_DuplicateMerchantOrderId_ThrowsException() {
        when(orderRepository.existsByMerchantOrderId(anyString())).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.createOrder(order));
        verify(orderRepository, never()).save(any());
    }

    @Test
    void testCreatePayment_Success() {
        String idempotencyKey = UUID.randomUUID().toString();
        when(paymentRepository.existsByIdempotencyKey(idempotencyKey)).thenReturn(false);
        
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setId(order.getId());
        orderEntity.setMerchantOrderId(order.getMerchantOrderId());
        orderEntity.setAmountCents(order.getAmount().getAmountCents());
        orderEntity.setCurrency(order.getAmount().getCurrencyCode());
        orderEntity.setDescription(order.getDescription());
        orderEntity.setCustomerEmail(order.getCustomer().getEmail());
        orderEntity.setCustomerPhone(order.getCustomer().getPhone());
        orderEntity.setStatus(order.getStatus());
        orderEntity.setCreatedAt(order.getCreatedAt());
        orderEntity.setUpdatedAt(order.getUpdatedAt());
        
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(orderEntity));
        when(orderMapper.toDomain(any(OrderEntity.class))).thenReturn(order);
        when(paymentRepository.findByOrderId(order.getId())).thenReturn(Optional.empty());
        
        PaymentEntity paymentEntity = new PaymentEntity();
        paymentEntity.setId(UUID.randomUUID());
        paymentEntity.setOrderId(order.getId());
        paymentEntity.setPaymentType(PaymentType.PURCHASE);
        paymentEntity.setStatus(PaymentStatus.INITIATED);
        paymentEntity.setAmountCents(order.getAmount().getAmountCents());
        paymentEntity.setCurrency(order.getAmount().getCurrencyCode());
        paymentEntity.setGateway(Gateway.AUTHORIZE_NET);
        paymentEntity.setIdempotencyKey(idempotencyKey);
        paymentEntity.setCreatedAt(Instant.now());
        paymentEntity.setUpdatedAt(Instant.now());
        
        when(paymentRepository.save(any(PaymentEntity.class))).thenReturn(paymentEntity);
        
        Payment expectedPayment = Payment.builder()
                .id(paymentEntity.getId())
                .orderId(paymentEntity.getOrderId())
                .paymentType(paymentEntity.getPaymentType())
                .status(paymentEntity.getStatus())
                .amount(order.getAmount())
                .gateway(paymentEntity.getGateway())
                .idempotencyKey(paymentEntity.getIdempotencyKey())
                .createdAt(paymentEntity.getCreatedAt())
                .updatedAt(paymentEntity.getUpdatedAt())
                .build();
        
        when(paymentMapper.toDomain(any(PaymentEntity.class))).thenReturn(expectedPayment);
        when(paymentMapper.toEntity(any(Payment.class))).thenReturn(paymentEntity);

        Payment payment = service.createPayment(
                order.getId(),
                PaymentType.PURCHASE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        assertNotNull(payment);
        assertEquals(order.getId(), payment.getOrderId());
        assertEquals(PaymentStatus.INITIATED, payment.getStatus());
        verify(paymentRepository).save(any());
    }

    @Test
    void testCreatePayment_OrderNotFound_ThrowsException() {
        UUID orderId = UUID.randomUUID();
        when(paymentRepository.existsByIdempotencyKey(anyString())).thenReturn(false);
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                service.createPayment(orderId, PaymentType.PURCHASE, Gateway.AUTHORIZE_NET, "key"));
    }

    @Test
    void testProcessPurchase_Success() {
        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .orderId(order.getId())
                .paymentType(PaymentType.PURCHASE)
                .status(PaymentStatus.INITIATED)
                .amount(amount)
                .gateway(Gateway.AUTHORIZE_NET)
                .idempotencyKey("key")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        PaymentEntity paymentEntity = new PaymentEntity();
        paymentEntity.setId(payment.getId());
        paymentEntity.setOrderId(payment.getOrderId());
        paymentEntity.setPaymentType(payment.getPaymentType());
        paymentEntity.setStatus(payment.getStatus());
        paymentEntity.setAmountCents(payment.getAmount().getAmountCents());
        paymentEntity.setCurrency(payment.getAmount().getCurrencyCode());
        paymentEntity.setGateway(payment.getGateway());
        paymentEntity.setIdempotencyKey(payment.getIdempotencyKey());
        paymentEntity.setCreatedAt(payment.getCreatedAt());
        paymentEntity.setUpdatedAt(payment.getUpdatedAt());
        
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setId(order.getId());
        orderEntity.setMerchantOrderId(order.getMerchantOrderId());
        orderEntity.setAmountCents(order.getAmount().getAmountCents());
        orderEntity.setCurrency(order.getAmount().getCurrencyCode());
        orderEntity.setDescription(order.getDescription());
        orderEntity.setCustomerEmail(order.getCustomer().getEmail());
        orderEntity.setCustomerPhone(order.getCustomer().getPhone());
        orderEntity.setStatus(order.getStatus());
        orderEntity.setCreatedAt(order.getCreatedAt());
        orderEntity.setUpdatedAt(order.getUpdatedAt());
        
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(paymentEntity));
        when(paymentMapper.toDomain(any(PaymentEntity.class))).thenReturn(payment);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(orderEntity));
        when(orderMapper.toDomain(any(OrderEntity.class))).thenReturn(order);
        when(stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)).thenReturn(true);
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId())).thenReturn(java.util.Collections.emptyList());

        PurchaseResponse gatewayResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-123")
                .responseCode("1")
                .responseMessage("Approved")
                .build();

        when(retryableGatewayService.purchase(any())).thenReturn(gatewayResponse);
        when(stateMachine.derivePaymentStatus(any(), any())).thenReturn(PaymentStatus.CAPTURED);
        when(paymentRepository.findByIdWithLock(any())).thenReturn(Optional.of(paymentEntity));
        when(paymentRepository.save(any(PaymentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // paymentMapper.toDomain is already stubbed above, no need to stub again
        when(paymentMapper.toEntity(any(Payment.class))).thenReturn(paymentEntity);
        when(orderMapper.toEntity(any(Order.class))).thenReturn(orderEntity);

        var transaction = service.processPurchase(payment.getId(), "token", UUID.randomUUID());

        assertNotNull(transaction);
        assertEquals(TransactionType.PURCHASE, transaction.getTransactionType());
        verify(retryableGatewayService).purchase(any());
        verify(transactionRepository, atLeastOnce()).save(any());
    }
}

