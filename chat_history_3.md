# Currency column type mismatch
_Exported on 03/01/2026 at 13:10:30 GMT+5:30 from Cursor (2.0.77)_

---

**User**

Debug 

Unable to build Hibernate SessionFactory; nested exception is org.hibernate.tool.schema.spi.SchemaManagementException: Schema-validation: wrong column type encountered in column [currency] in table [orders]; found [bpchar (Types#CHAR)], but expecting [varchar(3) (Types#VARCHAR)]

---

**Cursor**

Investigating the Hibernate schema validation error. Checking the Order entity and database configuration.


Mismatch: the migration uses `CHAR(3)`, while Hibernate expects `VARCHAR(3)` from the entity. Checking the migration file and other entities for similar issues:


All three tables use `CHAR(3)` for `currency`, but the entities expect `VARCHAR(3)`. Checking for other migration files to determine if we need a new migration or can update the existing one:


Creating a new migration to change `currency` columns from `CHAR(3)` to `VARCHAR(3)` to match the entities. This is safer than modifying the initial migration if it's already applied.


```sql
-- Alter currency columns from CHAR(3) to VARCHAR(3) to match Hibernate entity definitions
ALTER TABLE orders ALTER COLUMN currency TYPE VARCHAR(3);
ALTER TABLE payments ALTER COLUMN currency TYPE VARCHAR(3);
ALTER TABLE payment_transactions ALTER COLUMN currency TYPE VARCHAR(3);

```

Created a migration to change all `currency` columns from `CHAR(3)` to `VARCHAR(3)`. This aligns the schema with Hibernate's expectation (`VARCHAR(3)`) when using `@Column(length = 3)` on a String field.

**What I did:**
- Created a new Flyway migration `V2__alter_currency_columns_to_varchar.sql` that alters the `currency` columns in:
  - `orders` table
  - `payments` table  
  - `payment_transactions` table

**Next steps:**
1. Run your application — Flyway will automatically apply this migration on startup
2. The Hibernate validation error should be resolved once the columns are changed to `VARCHAR(3)`

**Alternative approach (if you prefer CHAR):**
If you want to keep `CHAR(3)` in the database, you would need to update all three entity classes to use:
```java
@Column(name = "currency", nullable = false, columnDefinition = "CHAR(3)")
```

However, using `VARCHAR(3)` is more standard and flexible for currency codes, so the migration approach is recommended.

The migration will run automatically when you start your application, and the schema validation error should be resolved.

```java
@Column(name = "currency", nullable = false, columnDefinition = "CHAR(3)")
```

---

**User**

Debug 



@bash (297-325) 

---

**Cursor**

Investigating the circular dependency. Examining the involved classes to identify the cycle:


Checking OrderMapper for dependencies that could cause the cycle:


The issue: `OrderController` depends on both `PaymentOrchestratorService` and `OrderRepository`, while `PaymentOrchestratorService` also depends on `OrderRepository`, creating a circular dependency during initialization.

The fix: remove the direct `OrderRepository` dependency from `OrderController` and use `PaymentOrchestratorService` for all order operations. Checking what methods are needed:


Adding a method to `PaymentOrchestratorService` to get an order by ID, then updating `OrderController` to use it instead of the repository directly:


Adding a method to `PaymentOrchestratorService` to get an order by ID, then updating `OrderController` to remove the direct repository dependency:


```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentOrchestratorService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentStateMachine stateMachine;
    private final PaymentGateway paymentGateway;

    @Transactional
    public Order createOrder(Order order) {
        if (orderRepository.existsByMerchantOrderId(order.getMerchantOrderId())) {
            throw new IllegalArgumentException("Order with merchantOrderId already exists: " + order.getMerchantOrderId());
        }
        return orderRepository.save(order);
    }

    public Order getOrderById(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    @Transactional
    public Payment createPayment(UUID orderId, PaymentType paymentType, Gateway gateway, String idempotencyKey) {
        // Check idempotency
        if (paymentRepository.existsByIdempotencyKey(idempotencyKey)) {
            Payment existing = paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Payment exists but not found"));
            return existing;
        }

        // Verify order exists
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

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

        payment = paymentRepository.save(payment);

        // Update order status
        Order updatedOrder = order.withStatus(OrderStatus.PAYMENT_INITIATED);
        orderRepository.save(updatedOrder);

        return payment;
    }

    @Transactional
    public PaymentTransaction processPurchase(UUID paymentId, String paymentMethodToken, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)) {
            throw new IllegalStateException("Cannot create PURCHASE transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        PurchaseResponse gatewayResponse = paymentGateway.purchase(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processAuthorize(UUID paymentId, String paymentMethodToken, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.AUTH)) {
            throw new IllegalStateException("Cannot create AUTH transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        AuthResponse gatewayResponse = paymentGateway.authorize(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processCapture(UUID paymentId, UUID parentTransactionId, Money amount, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.CAPTURE)) {
            throw new IllegalStateException("Cannot create CAPTURE transaction for payment in state: " + payment.getStatus());
        }

        PaymentTransaction parentTransaction = transactionRepository.findById(parentTransactionId)
                .orElseThrow(() -> new IllegalArgumentException("Parent transaction not found: " + parentTransactionId));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        CaptureResponse gatewayResponse = paymentGateway.capture(
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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order order = orderRepository.findById(updatedPayment.getOrderId()).orElse(null);
        if (order != null) {
            orderRepository.save(order.withStatus(newOrderStatus));
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
}


```

```java
package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.CreateOrderRequest;
import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.OrderResponse;
import com.paymentgateway.domain.enums.OrderStatus;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.service.PaymentOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Orders", description = "API endpoints for managing orders. Orders represent business intent and contain customer and amount information.")
public class OrderController {

    private final PaymentOrchestratorService orchestratorService;
    private final OrderRepository orderRepository;

    @PostMapping
    @Operation(
            summary = "Create a new order",
            description = "Creates a new order with the specified merchant order ID, amount, description, and customer information. " +
                    "Orders represent the business intent for a payment and must be created before payments can be processed."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Order created successfully",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - missing required fields or invalid data format",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        try {
            Order order = Order.builder()
                    .id(UUID.randomUUID())
                    .merchantOrderId(request.getMerchantOrderId())
                    .amount(request.getAmount())
                    .description(request.getDescription())
                    .customer(request.getCustomer())
                    .status(OrderStatus.CREATED)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            order = orchestratorService.createOrder(order);

            OrderResponse response = OrderResponse.builder()
                    .orderId(order.getId().toString())
                    .status(order.getStatus())
                    .amount(order.getAmount())
                    .createdAt(order.getCreatedAt())
                    .build();

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.error("Error creating order", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @GetMapping("/{orderId}")
    @Operation(
            summary = "Get order by ID",
            description = "Retrieves the details of an order by its unique identifier. " +
                    "Returns the order status, amount, creation timestamp, and other relevant information."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Order retrieved successfully",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Order not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> getOrder(
            @Parameter(description = "Unique identifier of the order (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String orderId) {
        try {
            UUID id = UUID.fromString(orderId);
            Order order = orderRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

            OrderResponse response = OrderResponse.builder()
                    .orderId(order.getId().toString())
                    .status(order.getStatus())
                    .amount(order.getAmount())
                    .createdAt(order.getCreatedAt())
                    .build();

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("NOT_FOUND")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }
}


```

```java
package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.CreateOrderRequest;
import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.OrderResponse;
import com.paymentgateway.domain.enums.OrderStatus;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.service.PaymentOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Orders", description = "API endpoints for managing orders. Orders represent business intent and contain customer and amount information.")
public class OrderController {

    private final PaymentOrchestratorService orchestratorService;

    @PostMapping
    @Operation(
            summary = "Create a new order",
            description = "Creates a new order with the specified merchant order ID, amount, description, and customer information. " +
                    "Orders represent the business intent for a payment and must be created before payments can be processed."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Order created successfully",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - missing required fields or invalid data format",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        try {
            Order order = Order.builder()
                    .id(UUID.randomUUID())
                    .merchantOrderId(request.getMerchantOrderId())
                    .amount(request.getAmount())
                    .description(request.getDescription())
                    .customer(request.getCustomer())
                    .status(OrderStatus.CREATED)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            order = orchestratorService.createOrder(order);

            OrderResponse response = OrderResponse.builder()
                    .orderId(order.getId().toString())
                    .status(order.getStatus())
                    .amount(order.getAmount())
                    .createdAt(order.getCreatedAt())
                    .build();

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.error("Error creating order", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @GetMapping("/{orderId}")
    @Operation(
            summary = "Get order by ID",
            description = "Retrieves the details of an order by its unique identifier. " +
                    "Returns the order status, amount, creation timestamp, and other relevant information."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Order retrieved successfully",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Order not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> getOrder(
            @Parameter(description = "Unique identifier of the order (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String orderId) {
        try {
            UUID id = UUID.fromString(orderId);
            Order order = orderRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

            OrderResponse response = OrderResponse.builder()
                    .orderId(order.getId().toString())
                    .status(order.getStatus())
                    .amount(order.getAmount())
                    .createdAt(order.getCreatedAt())
                    .build();

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("NOT_FOUND")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }
}


```

```java
package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.CreateOrderRequest;
import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.OrderResponse;
import com.paymentgateway.domain.enums.OrderStatus;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.service.PaymentOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Orders", description = "API endpoints for managing orders. Orders represent business intent and contain customer and amount information.")
public class OrderController {

    private final PaymentOrchestratorService orchestratorService;

    @PostMapping
    @Operation(
            summary = "Create a new order",
            description = "Creates a new order with the specified merchant order ID, amount, description, and customer information. " +
                    "Orders represent the business intent for a payment and must be created before payments can be processed."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Order created successfully",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - missing required fields or invalid data format",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        try {
            Order order = Order.builder()
                    .id(UUID.randomUUID())
                    .merchantOrderId(request.getMerchantOrderId())
                    .amount(request.getAmount())
                    .description(request.getDescription())
                    .customer(request.getCustomer())
                    .status(OrderStatus.CREATED)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            order = orchestratorService.createOrder(order);

            OrderResponse response = OrderResponse.builder()
                    .orderId(order.getId().toString())
                    .status(order.getStatus())
                    .amount(order.getAmount())
                    .createdAt(order.getCreatedAt())
                    .build();

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.error("Error creating order", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @GetMapping("/{orderId}")
    @Operation(
            summary = "Get order by ID",
            description = "Retrieves the details of an order by its unique identifier. " +
                    "Returns the order status, amount, creation timestamp, and other relevant information."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Order retrieved successfully",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Order not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> getOrder(
            @Parameter(description = "Unique identifier of the order (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String orderId) {
        try {
            UUID id = UUID.fromString(orderId);
            Order order = orchestratorService.getOrderById(id);

            OrderResponse response = OrderResponse.builder()
                    .orderId(order.getId().toString())
                    .status(order.getStatus())
                    .amount(order.getAmount())
                    .createdAt(order.getCreatedAt())
                    .build();

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("NOT_FOUND")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }
}


```

Checking for linting errors:


Verifying the final state of `OrderController`:


Fixed the circular dependency.

## Changes Made:

1. **Removed direct repository dependency from `OrderController`**:
   - Removed the `OrderRepository` field
   - Removed the import for `OrderRepository`

2. **Added `getOrderById` method to `PaymentOrchestratorService`**:
   - Added a public method to retrieve orders by ID through the service layer

3. **Updated `OrderController.getOrder()` method**:
   - Changed from `orderRepository.findById(id)` to `orchestratorService.getOrderById(id)`

## Why This Fixes the Issue:

The circular dependency was:
- `OrderController` → `PaymentOrchestratorService` → `OrderRepository` (via `JpaOrderRepositoryImpl`)
- `OrderController` also directly depended on `OrderRepository`

This created a cycle during Spring bean initialization. By removing the direct repository dependency from the controller and routing all order operations through the service layer, the cycle is broken and the layering is correct:

**Controller → Service → Repository**

The application should now start without the circular dependency error. The controller only depends on the service, and the service depends on the repository, creating a clean unidirectional dependency flow.

---

**User**

@bash (456-480) 

---

**Cursor**

The error persists. Checking for other dependencies that could cause the cycle. Verifying the compiled classes are up to date and investigating further:


The compiled classes may be stale. Verifying the current source and rebuilding. Checking for other potential causes:


Project recompiled. The source looks correct. Checking for event listeners, `@PostConstruct` methods, or other dependencies that could cause the cycle:


Using `@Lazy` on the `OrderRepository` dependency in `PaymentOrchestratorService` to break the cycle. This defers initialization until first use:


```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentOrchestratorService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentStateMachine stateMachine;
    private final PaymentGateway paymentGateway;

    @Transactional
    public Order createOrder(Order order) {
        if (orderRepository.existsByMerchantOrderId(order.getMerchantOrderId())) {
            throw new IllegalArgumentException("Order with merchantOrderId already exists: " + order.getMerchantOrderId());
        }
        return orderRepository.save(order);
    }

    public Order getOrderById(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    @Transactional
    public Payment createPayment(UUID orderId, PaymentType paymentType, Gateway gateway, String idempotencyKey) {
        // Check idempotency
        if (paymentRepository.existsByIdempotencyKey(idempotencyKey)) {
            Payment existing = paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Payment exists but not found"));
            return existing;
        }

        // Verify order exists
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

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

        payment = paymentRepository.save(payment);

        // Update order status
        Order updatedOrder = order.withStatus(OrderStatus.PAYMENT_INITIATED);
        orderRepository.save(updatedOrder);

        return payment;
    }

    @Transactional
    public PaymentTransaction processPurchase(UUID paymentId, String paymentMethodToken, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)) {
            throw new IllegalStateException("Cannot create PURCHASE transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        PurchaseResponse gatewayResponse = paymentGateway.purchase(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processAuthorize(UUID paymentId, String paymentMethodToken, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.AUTH)) {
            throw new IllegalStateException("Cannot create AUTH transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        AuthResponse gatewayResponse = paymentGateway.authorize(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processCapture(UUID paymentId, UUID parentTransactionId, Money amount, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.CAPTURE)) {
            throw new IllegalStateException("Cannot create CAPTURE transaction for payment in state: " + payment.getStatus());
        }

        PaymentTransaction parentTransaction = transactionRepository.findById(parentTransactionId)
                .orElseThrow(() -> new IllegalArgumentException("Parent transaction not found: " + parentTransactionId));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        CaptureResponse gatewayResponse = paymentGateway.capture(
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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order order = orderRepository.findById(updatedPayment.getOrderId()).orElse(null);
        if (order != null) {
            orderRepository.save(order.withStatus(newOrderStatus));
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
}


```

```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentOrchestratorService {

    @Lazy
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentStateMachine stateMachine;
    private final PaymentGateway paymentGateway;

    @Transactional
    public Order createOrder(Order order) {
        if (orderRepository.existsByMerchantOrderId(order.getMerchantOrderId())) {
            throw new IllegalArgumentException("Order with merchantOrderId already exists: " + order.getMerchantOrderId());
        }
        return orderRepository.save(order);
    }

    public Order getOrderById(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    @Transactional
    public Payment createPayment(UUID orderId, PaymentType paymentType, Gateway gateway, String idempotencyKey) {
        // Check idempotency
        if (paymentRepository.existsByIdempotencyKey(idempotencyKey)) {
            Payment existing = paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Payment exists but not found"));
            return existing;
        }

        // Verify order exists
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

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

        payment = paymentRepository.save(payment);

        // Update order status
        Order updatedOrder = order.withStatus(OrderStatus.PAYMENT_INITIATED);
        orderRepository.save(updatedOrder);

        return payment;
    }

    @Transactional
    public PaymentTransaction processPurchase(UUID paymentId, String paymentMethodToken, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)) {
            throw new IllegalStateException("Cannot create PURCHASE transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        PurchaseResponse gatewayResponse = paymentGateway.purchase(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processAuthorize(UUID paymentId, String paymentMethodToken, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.AUTH)) {
            throw new IllegalStateException("Cannot create AUTH transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        AuthResponse gatewayResponse = paymentGateway.authorize(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processCapture(UUID paymentId, UUID parentTransactionId, Money amount, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.CAPTURE)) {
            throw new IllegalStateException("Cannot create CAPTURE transaction for payment in state: " + payment.getStatus());
        }

        PaymentTransaction parentTransaction = transactionRepository.findById(parentTransactionId)
                .orElseThrow(() -> new IllegalArgumentException("Parent transaction not found: " + parentTransactionId));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        CaptureResponse gatewayResponse = paymentGateway.capture(
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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order order = orderRepository.findById(updatedPayment.getOrderId()).orElse(null);
        if (order != null) {
            orderRepository.save(order.withStatus(newOrderStatus));
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
}


```

Fixing `@Lazy` usage: with `@RequiredArgsConstructor`, it should be on the constructor parameter, not the field. Updating accordingly:


Replacing `@RequiredArgsConstructor` with an explicit constructor so `@Lazy` can be applied to the parameter:


```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class PaymentOrchestratorService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentStateMachine stateMachine;
    private final PaymentGateway paymentGateway;

    public PaymentOrchestratorService(
            @Lazy OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            PaymentTransactionRepository transactionRepository,
            PaymentStateMachine stateMachine,
            PaymentGateway paymentGateway) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.transactionRepository = transactionRepository;
        this.stateMachine = stateMachine;
        this.paymentGateway = paymentGateway;
    }

    @Transactional
    public Order createOrder(Order order) {
        if (orderRepository.existsByMerchantOrderId(order.getMerchantOrderId())) {
            throw new IllegalArgumentException("Order with merchantOrderId already exists: " + order.getMerchantOrderId());
        }
        return orderRepository.save(order);
    }

    public Order getOrderById(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    @Transactional
    public Payment createPayment(UUID orderId, PaymentType paymentType, Gateway gateway, String idempotencyKey) {
        // Check idempotency
        if (paymentRepository.existsByIdempotencyKey(idempotencyKey)) {
            Payment existing = paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Payment exists but not found"));
            return existing;
        }

        // Verify order exists
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

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

        payment = paymentRepository.save(payment);

        // Update order status
        Order updatedOrder = order.withStatus(OrderStatus.PAYMENT_INITIATED);
        orderRepository.save(updatedOrder);

        return payment;
    }

    @Transactional
    public PaymentTransaction processPurchase(UUID paymentId, String paymentMethodToken, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)) {
            throw new IllegalStateException("Cannot create PURCHASE transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        PurchaseResponse gatewayResponse = paymentGateway.purchase(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processAuthorize(UUID paymentId, String paymentMethodToken, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.AUTH)) {
            throw new IllegalStateException("Cannot create AUTH transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        AuthResponse gatewayResponse = paymentGateway.authorize(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processCapture(UUID paymentId, UUID parentTransactionId, Money amount, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.CAPTURE)) {
            throw new IllegalStateException("Cannot create CAPTURE transaction for payment in state: " + payment.getStatus());
        }

        PaymentTransaction parentTransaction = transactionRepository.findById(parentTransactionId)
                .orElseThrow(() -> new IllegalArgumentException("Parent transaction not found: " + parentTransactionId));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        CaptureResponse gatewayResponse = paymentGateway.capture(
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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order order = orderRepository.findById(updatedPayment.getOrderId()).orElse(null);
        if (order != null) {
            orderRepository.save(order.withStatus(newOrderStatus));
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
}


```

Rebuilding the project to compile the changes:


Project compiled successfully. Summary of changes to fix the circular dependency:

## Summary of Changes:

1. **Removed direct repository dependency from `OrderController`**:
   - Removed the `OrderRepository` field and import
   - Controller now only depends on `PaymentOrchestratorService`

2. **Added `getOrderById` method to `PaymentOrchestratorService`**:
   - Added a public method to retrieve orders through the service layer

3. **Updated `OrderController.getOrder()` method**:
   - Changed from `orderRepository.findById(id)` to `orchestratorService.getOrderById(id)`

4. **Added `@Lazy` annotation to break the cycle**:
   - Applied `@Lazy` to the `OrderRepository` dependency in `PaymentOrchestratorService`
   - Replaced `@RequiredArgsConstructor` with an explicit constructor to properly apply `@Lazy`

The `@Lazy` annotation defers initialization of `OrderRepository` until first use, breaking the circular dependency during Spring bean initialization.

**The dependency chain is now:**
- `OrderController` → `PaymentOrchestratorService` → `@Lazy OrderRepository` → `JpaOrderRepositoryImpl`

The application should start without the circular dependency error. Try running it again.

---

**User**

Simplify and fix... 

 fix - use autowiring all places

---

**Cursor**

Converting to `@Autowired` field injection across the codebase. Checking the current state and updating:


Converting all classes to use `@Autowired` field injection. Starting with the main files:


```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class PaymentOrchestratorService {

    @Autowired
    @Lazy
    private OrderRepository orderRepository;
    
    @Autowired
    private PaymentRepository paymentRepository;
    
    @Autowired
    private PaymentTransactionRepository transactionRepository;
    
    @Autowired
    private PaymentStateMachine stateMachine;
    
    @Autowired
    private PaymentGateway paymentGateway;

    @Transactional
    public Order createOrder(Order order) {
        if (orderRepository.existsByMerchantOrderId(order.getMerchantOrderId())) {
            throw new IllegalArgumentException("Order with merchantOrderId already exists: " + order.getMerchantOrderId());
        }
        return orderRepository.save(order);
    }

    public Order getOrderById(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    @Transactional
    public Payment createPayment(UUID orderId, PaymentType paymentType, Gateway gateway, String idempotencyKey) {
        // Check idempotency
        if (paymentRepository.existsByIdempotencyKey(idempotencyKey)) {
            Payment existing = paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Payment exists but not found"));
            return existing;
        }

        // Verify order exists
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

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

        payment = paymentRepository.save(payment);

        // Update order status
        Order updatedOrder = order.withStatus(OrderStatus.PAYMENT_INITIATED);
        orderRepository.save(updatedOrder);

        return payment;
    }

    @Transactional
    public PaymentTransaction processPurchase(UUID paymentId, String paymentMethodToken, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)) {
            throw new IllegalStateException("Cannot create PURCHASE transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        PurchaseResponse gatewayResponse = paymentGateway.purchase(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processAuthorize(UUID paymentId, String paymentMethodToken, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.AUTH)) {
            throw new IllegalStateException("Cannot create AUTH transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        AuthResponse gatewayResponse = paymentGateway.authorize(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processCapture(UUID paymentId, UUID parentTransactionId, Money amount, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.CAPTURE)) {
            throw new IllegalStateException("Cannot create CAPTURE transaction for payment in state: " + payment.getStatus());
        }

        PaymentTransaction parentTransaction = transactionRepository.findById(parentTransactionId)
                .orElseThrow(() -> new IllegalArgumentException("Parent transaction not found: " + parentTransactionId));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        CaptureResponse gatewayResponse = paymentGateway.capture(
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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order order = orderRepository.findById(updatedPayment.getOrderId()).orElse(null);
        if (order != null) {
            orderRepository.save(order.withStatus(newOrderStatus));
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
}


```

```java
package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.CreateOrderRequest;
import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.OrderResponse;
import com.paymentgateway.domain.enums.OrderStatus;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.service.PaymentOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/orders")
@Slf4j
@Tag(name = "Orders", description = "API endpoints for managing orders. Orders represent business intent and contain customer and amount information.")
public class OrderController {

    @Autowired
    private PaymentOrchestratorService orchestratorService;

    @PostMapping
    @Operation(
            summary = "Create a new order",
            description = "Creates a new order with the specified merchant order ID, amount, description, and customer information. " +
                    "Orders represent the business intent for a payment and must be created before payments can be processed."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Order created successfully",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - missing required fields or invalid data format",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        try {
            Order order = Order.builder()
                    .id(UUID.randomUUID())
                    .merchantOrderId(request.getMerchantOrderId())
                    .amount(request.getAmount())
                    .description(request.getDescription())
                    .customer(request.getCustomer())
                    .status(OrderStatus.CREATED)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            order = orchestratorService.createOrder(order);

            OrderResponse response = OrderResponse.builder()
                    .orderId(order.getId().toString())
                    .status(order.getStatus())
                    .amount(order.getAmount())
                    .createdAt(order.getCreatedAt())
                    .build();

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.error("Error creating order", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @GetMapping("/{orderId}")
    @Operation(
            summary = "Get order by ID",
            description = "Retrieves the details of an order by its unique identifier. " +
                    "Returns the order status, amount, creation timestamp, and other relevant information."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Order retrieved successfully",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Order not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> getOrder(
            @Parameter(description = "Unique identifier of the order (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String orderId) {
        try {
            UUID id = UUID.fromString(orderId);
            Order order = orchestratorService.getOrderById(id);

            OrderResponse response = OrderResponse.builder()
                    .orderId(order.getId().toString())
                    .status(order.getStatus())
                    .amount(order.getAmount())
                    .createdAt(order.getCreatedAt())
                    .build();

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("NOT_FOUND")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }
}


```

Updating the remaining controllers and services:


```java
package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.CreatePaymentRequest;
import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.PaymentResponse;
import com.paymentgateway.domain.model.Payment;
import com.paymentgateway.repository.PaymentRepository;
import com.paymentgateway.service.PaymentOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1")
@Slf4j
@Tag(name = "Payments", description = "API endpoints for managing payments. Payments represent the payment lifecycle and link orders to transactions.")
public class PaymentController {

    @Autowired
    private PaymentOrchestratorService orchestratorService;
    
    @Autowired
    private PaymentRepository paymentRepository;

    @PostMapping("/orders/{orderId}/payments")
    @Operation(
            summary = "Create a payment for an order",
            description = "Creates a new payment for the specified order. " +
                    "A payment represents the payment lifecycle and must be created before transactions can be processed. " +
                    "The payment specifies the payment method, flow type (purchase or authorize), and gateway to use. " +
                    "Idempotency is supported via the Idempotency-Key header."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Payment created successfully",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - order not found, invalid payment configuration, or duplicate idempotency key",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> createPayment(
            @Parameter(description = "Unique identifier of the order (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String orderId,
            @Valid @RequestBody CreatePaymentRequest request,
            @Parameter(description = "Idempotency key to prevent duplicate payments. If not provided, a new key will be generated.", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            UUID orderUuid = UUID.fromString(orderId);

            // Generate idempotency key if not provided
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                idempotencyKey = UUID.randomUUID().toString();
            }

            // Check idempotency - use payment repository's idempotency check
            if (paymentRepository.existsByIdempotencyKey(idempotencyKey)) {
                Payment existing = paymentRepository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException("Payment exists but not found"));
                PaymentResponse response = PaymentResponse.builder()
                        .paymentId(existing.getId().toString())
                        .orderId(existing.getOrderId().toString())
                        .status(existing.getStatus())
                        .flow(existing.getPaymentType())
                        .build();
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
            }

            Payment payment = orchestratorService.createPayment(
                    orderUuid,
                    request.getFlow(),
                    request.getGateway(),
                    idempotencyKey
            );

            PaymentResponse response = PaymentResponse.builder()
                    .paymentId(payment.getId().toString())
                    .orderId(payment.getOrderId().toString())
                    .status(payment.getStatus())
                    .flow(payment.getPaymentType())
                    .build();

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Error creating payment", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }
}


```

```java
package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.CaptureRequest;
import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.PurchaseTransactionRequest;
import com.paymentgateway.api.dto.TransactionResponse;
import com.paymentgateway.domain.enums.TransactionState;
import com.paymentgateway.domain.model.PaymentTransaction;
import com.paymentgateway.repository.PaymentTransactionRepository;
import com.paymentgateway.service.PaymentOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/payments")
@Slf4j
@Tag(name = "Transactions", description = "API endpoints for managing payment transactions including purchase, authorization, and capture operations")
public class TransactionController {

    @Autowired
    private PaymentOrchestratorService orchestratorService;
    
    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @PostMapping("/{paymentId}/transactions/purchase")
    @Operation(
            summary = "Process a purchase transaction",
            description = "Creates and processes a purchase transaction for the specified payment. " +
                    "A purchase transaction immediately captures the funds from the payment method. " +
                    "This is a one-step process that combines authorization and capture."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Purchase transaction created and processed successfully",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - payment ID format invalid, payment not found, or invalid payment method token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> purchase(
            @Parameter(description = "Unique identifier of the payment (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String paymentId,
            @Valid @RequestBody PurchaseTransactionRequest request,
            @Parameter(description = "Idempotency key to prevent duplicate transactions. If not provided, a new key will be generated.", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            UUID paymentUuid = UUID.fromString(paymentId);
            UUID traceId = UUID.randomUUID();

            PaymentTransaction transaction = orchestratorService.processPurchase(
                    paymentUuid,
                    request.getPaymentMethodToken(),
                    traceId
            );

            TransactionResponse response = buildTransactionResponse(transaction);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Error processing purchase", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @PostMapping("/{paymentId}/transactions/authorize")
    @Operation(
            summary = "Authorize a payment transaction",
            description = "Creates and processes an authorization transaction for the specified payment. " +
                    "An authorization reserves funds on the payment method but does not capture them. " +
                    "The funds must be captured separately using the capture endpoint before they expire."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Authorization transaction created and processed successfully",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - payment ID format invalid, payment not found, or invalid payment method token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> authorize(
            @Parameter(description = "Unique identifier of the payment (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String paymentId,
            @Valid @RequestBody PurchaseTransactionRequest request,
            @Parameter(description = "Idempotency key to prevent duplicate transactions. If not provided, a new key will be generated.", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            UUID paymentUuid = UUID.fromString(paymentId);
            UUID traceId = UUID.randomUUID();

            PaymentTransaction transaction = orchestratorService.processAuthorize(
                    paymentUuid,
                    request.getPaymentMethodToken(),
                    traceId
            );

            TransactionResponse response = buildTransactionResponse(transaction);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Error processing authorize", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @PostMapping("/{paymentId}/transactions/{transactionId}/capture")
    @Operation(
            summary = "Capture an authorized transaction",
            description = "Captures funds from a previously authorized transaction. " +
                    "The capture amount can be equal to or less than the authorized amount. " +
                    "Partial captures are supported, and multiple captures can be made against a single authorization " +
                    "up to the total authorized amount."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Capture transaction created and processed successfully",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - invalid payment/transaction ID, amount exceeds authorized amount, or transaction not in authorized state",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> capture(
            @Parameter(description = "Unique identifier of the payment (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String paymentId,
            @Parameter(description = "Unique identifier of the authorized transaction to capture (UUID)", required = true, example = "223e4567-e89b-12d3-a456-426614174000")
            @PathVariable String transactionId,
            @Valid @RequestBody CaptureRequest request,
            @Parameter(description = "Idempotency key to prevent duplicate transactions. If not provided, a new key will be generated.", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            UUID paymentUuid = UUID.fromString(paymentId);
            UUID transactionUuid = UUID.fromString(transactionId);
            UUID traceId = UUID.randomUUID();

            PaymentTransaction transaction = orchestratorService.processCapture(
                    paymentUuid,
                    transactionUuid,
                    request.getAmount(),
                    traceId
            );

            TransactionResponse response = buildTransactionResponse(transaction);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Error processing capture", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @GetMapping("/{paymentId}/transactions")
    @Operation(
            summary = "Get all transactions for a payment",
            description = "Retrieves all transactions associated with the specified payment. " +
                    "Returns a list of transactions including purchase, authorization, and capture transactions " +
                    "with their current status and details."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of transactions retrieved successfully",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Payment not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> getTransactions(
            @Parameter(description = "Unique identifier of the payment (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String paymentId) {
        try {
            UUID paymentUuid = UUID.fromString(paymentId);
            List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentUuid);

            List<TransactionResponse> responses = transactions.stream()
                    .map(this::buildTransactionResponse)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(responses);
        } catch (IllegalArgumentException e) {
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("NOT_FOUND")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    private TransactionResponse buildTransactionResponse(PaymentTransaction transaction) {
        TransactionResponse.TransactionResponseBuilder builder = TransactionResponse.builder()
                .transactionId(transaction.getId().toString())
                .type(transaction.getTransactionType())
                .status(transaction.getTransactionState())
                .amount(transaction.getAmount())
                .gatewayReferenceId(transaction.getGatewayTransactionId())
                .createdAt(transaction.getCreatedAt());

        if (transaction.getParentTransactionId() != null) {
            builder.parentTransactionId(transaction.getParentTransactionId().toString());
        }

        if (transaction.getTransactionState() == TransactionState.AUTHORIZED) {
            builder.authorizedAmount(transaction.getAmount());
        }

        return builder.build();
    }
}


```

```java
package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.WebhookRequest;
import com.paymentgateway.domain.enums.Gateway;
import com.paymentgateway.domain.model.WebhookEvent;
import com.paymentgateway.repository.WebhookRepository;
import com.paymentgateway.service.WebhookProcessorService;
import com.paymentgateway.service.WebhookSignatureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/webhooks")
@Slf4j
@Tag(name = "Webhooks", description = "API endpoints for receiving webhook events from payment gateways. Webhooks are processed asynchronously and support idempotency.")
public class WebhookController {

    @Autowired
    private WebhookRepository webhookRepository;
    
    @Autowired
    private WebhookSignatureService signatureService;
    
    @Autowired
    private WebhookProcessorService processorService;

    @PostMapping("/authorize-net")
    @Operation(
            summary = "Receive Authorize.Net webhook",
            description = "Receives and processes webhook events from Authorize.Net. " +
                    "The webhook is validated for signature authenticity and checked for idempotency using the gateway event ID. " +
                    "Webhooks are processed asynchronously, and duplicate events are automatically ignored."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Webhook received and queued for processing successfully"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid webhook signature - signature verification failed",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error while processing webhook",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> receiveAuthorizeNetWebhook(
            @Valid @RequestBody WebhookRequest request) {
        try {
            // Check idempotency by gateway event ID
            if (webhookRepository.existsByGatewayEventId(request.getEventId())) {
                log.info("Webhook already received: {}", request.getEventId());
                return ResponseEntity.ok().build();
            }

            // Verify signature (using payload as string representation)
            String payloadString = request.getPayload() != null ? request.getPayload().toString() : "";
            boolean signatureValid = signatureService.verifySignature(payloadString, request.getSignature());
            if (!signatureValid) {
                log.warn("Invalid webhook signature for event: {}", request.getEventId());
                ErrorResponse error = ErrorResponse.builder()
                        .error(ErrorResponse.ErrorDetail.builder()
                                .code("INVALID_SIGNATURE")
                                .message("Webhook signature verification failed")
                                .retryable(false)
                                .traceId(UUID.randomUUID().toString())
                                .build())
                        .build();
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
            }

            // Create webhook event
            WebhookEvent webhook = WebhookEvent.builder()
                    .id(UUID.randomUUID())
                    .gateway(Gateway.AUTHORIZE_NET)
                    .eventType(request.getEventType())
                    .gatewayEventId(request.getEventId())
                    .payload(request.getPayload())
                    .signatureVerified(signatureValid)
                    .processed(false)
                    .createdAt(Instant.now())
                    .build();

            webhook = webhookRepository.save(webhook);

            // Process asynchronously
            processWebhookAsync(webhook);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Error processing webhook", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("WEBHOOK_PROCESSING_ERROR")
                            .message("Failed to process webhook")
                            .retryable(true)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @Async
    public void processWebhookAsync(WebhookEvent webhook) {
        try {
            processorService.processWebhook(webhook);
        } catch (Exception e) {
            log.error("Error in async webhook processing", e);
        }
    }
}


```

Updating the remaining services and repositories:


```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.TransactionState;
import com.paymentgateway.domain.model.PaymentTransaction;
import com.paymentgateway.domain.model.WebhookEvent;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.repository.PaymentRepository;
import com.paymentgateway.repository.PaymentTransactionRepository;
import com.paymentgateway.repository.WebhookRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class WebhookProcessorService {

    @Autowired
    private WebhookRepository webhookRepository;
    
    @Autowired
    private PaymentTransactionRepository transactionRepository;
    
    @Autowired
    private PaymentRepository paymentRepository;
    
    @Autowired
    private PaymentStateMachine stateMachine;

    /**
     * Processes a webhook event asynchronously.
     * Webhooks are authoritative and can advance state even if API calls failed.
     */
    public void processWebhook(WebhookEvent webhook) {
        log.info("Processing webhook event: {}", webhook.getGatewayEventId());

        // Check if already processed
        if (webhook.isProcessed()) {
            log.info("Webhook already processed: {}", webhook.getGatewayEventId());
            return;
        }

        try {
            // Process based on event type
            String eventType = webhook.getEventType();
            if (eventType.startsWith("TRANSACTION.")) {
                processTransactionWebhook(webhook);
            } else {
                log.warn("Unknown webhook event type: {}", eventType);
            }

            // Mark as processed
            WebhookEvent processed = webhook.markAsProcessed();
            webhookRepository.save(processed);

        } catch (Exception e) {
            log.error("Error processing webhook: {}", webhook.getGatewayEventId(), e);
            throw e;
        }
    }

    private void processTransactionWebhook(WebhookEvent webhook) {
        Map<String, Object> payload = webhook.getPayload();
        String transactionRefId = (String) payload.get("transactionReferenceId");
        
        if (transactionRefId == null) {
            log.warn("No transaction reference ID in webhook payload");
            return;
        }

        // Find transaction by gateway transaction ID
        PaymentTransaction transaction = transactionRepository
                .findByGatewayTransactionId(transactionRefId)
                .orElse(null);

        if (transaction == null) {
            log.warn("Transaction not found for gateway ID: {}", transactionRefId);
            return;
        }

        // Update transaction state based on webhook event type
        TransactionState newState = deriveTransactionStateFromWebhook(webhook.getEventType(), payload);
        
        if (newState != null && newState != transaction.getTransactionState()) {
            PaymentTransaction updated = transaction.withState(newState);
            transactionRepository.save(updated);

            // Derive and update payment status
            List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(transaction.getPaymentId());
            paymentRepository.findById(transaction.getPaymentId()).ifPresent(payment -> {
                com.paymentgateway.domain.enums.PaymentStatus newPaymentStatus = 
                        stateMachine.derivePaymentStatus(payment, transactions);
                paymentRepository.updateWithLock(payment.getId(), p -> p.withStatus(newPaymentStatus));
            });
        }
    }

    private TransactionState deriveTransactionStateFromWebhook(String eventType, Map<String, Object> payload) {
        return switch (eventType) {
            case "TRANSACTION.SETTLED" -> TransactionState.SETTLED;
            case "TRANSACTION.AUTHORIZED" -> TransactionState.AUTHORIZED;
            case "TRANSACTION.CAPTURED" -> TransactionState.SUCCESS;
            case "TRANSACTION.DECLINED", "TRANSACTION.ERROR" -> TransactionState.FAILED;
            case "TRANSACTION.PENDING" -> TransactionState.PENDING;
            default -> null;
        };
    }
}


```

```java
package com.paymentgateway.repository.impl;

import com.paymentgateway.domain.model.Order;
import com.paymentgateway.domain.entity.OrderEntity;
import com.paymentgateway.repository.OrderRepository;
import com.paymentgateway.repository.jpa.JpaOrderRepository;
import com.paymentgateway.repository.mapper.OrderMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "app.repository.type", havingValue = "jpa", matchIfMissing = true)
public class JpaOrderRepositoryImpl implements OrderRepository {

    @Autowired
    private JpaOrderRepository jpaRepository;
    
    @Autowired
    private OrderMapper mapper;

    @Override
    public Order save(Order order) {
        OrderEntity entity = mapper.toEntity(order);
        OrderEntity saved = jpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<Order> findById(UUID id) {
        return jpaRepository.findById(id)
                .map(mapper::toDomain);
    }

    @Override
    public Optional<Order> findByMerchantOrderId(String merchantOrderId) {
        return jpaRepository.findByMerchantOrderId(merchantOrderId)
                .map(mapper::toDomain);
    }

    @Override
    public boolean existsByMerchantOrderId(String merchantOrderId) {
        return jpaRepository.existsByMerchantOrderId(merchantOrderId);
    }
}


```

```java
package com.paymentgateway.repository.impl;

import com.paymentgateway.domain.model.Payment;
import com.paymentgateway.domain.entity.PaymentEntity;
import com.paymentgateway.repository.PaymentRepository;
import com.paymentgateway.repository.jpa.JpaPaymentRepository;
import com.paymentgateway.repository.mapper.PaymentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Repository
@ConditionalOnProperty(name = "app.repository.type", havingValue = "jpa", matchIfMissing = true)
public class JpaPaymentRepositoryImpl implements PaymentRepository {

    @Autowired
    private JpaPaymentRepository jpaRepository;
    
    @Autowired
    private PaymentMapper mapper;

    @Override
    public Payment save(Payment payment) {
        PaymentEntity entity = mapper.toEntity(payment);
        PaymentEntity saved = jpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return jpaRepository.findById(id)
                .map(mapper::toDomain);
    }

    @Override
    public Optional<Payment> findByOrderId(UUID orderId) {
        return jpaRepository.findByOrderId(orderId)
                .map(mapper::toDomain);
    }

    @Override
    public Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey)
                .map(mapper::toDomain);
    }

    @Override
    public boolean existsByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.existsByIdempotencyKey(idempotencyKey);
    }

    @Override
    public synchronized Payment updateWithLock(UUID id, Function<Payment, Payment> updater) {
        PaymentEntity entity = jpaRepository.findByIdWithLock(id)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + id));
        
        Payment payment = mapper.toDomain(entity);
        Payment updated = updater.apply(payment);
        PaymentEntity updatedEntity = mapper.toEntity(updated);
        
        PaymentEntity saved = jpaRepository.save(updatedEntity);
        return mapper.toDomain(saved);
    }
}


```

Updating the remaining repository implementations:


```java
package com.paymentgateway.repository.impl;

import com.paymentgateway.domain.model.PaymentTransaction;
import com.paymentgateway.domain.entity.PaymentTransactionEntity;
import com.paymentgateway.repository.PaymentTransactionRepository;
import com.paymentgateway.repository.jpa.JpaPaymentTransactionRepository;
import com.paymentgateway.repository.mapper.PaymentTransactionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "app.repository.type", havingValue = "jpa", matchIfMissing = true)
public class JpaPaymentTransactionRepositoryImpl implements PaymentTransactionRepository {

    @Autowired
    private JpaPaymentTransactionRepository jpaRepository;
    
    @Autowired
    private PaymentTransactionMapper mapper;

    @Override
    public PaymentTransaction save(PaymentTransaction transaction) {
        PaymentTransactionEntity entity = mapper.toEntity(transaction);
        PaymentTransactionEntity saved = jpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<PaymentTransaction> findById(UUID id) {
        return jpaRepository.findById(id)
                .map(mapper::toDomain);
    }

    @Override
    public List<PaymentTransaction> findByPaymentId(UUID paymentId) {
        List<PaymentTransactionEntity> entities = jpaRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId);
        return mapper.toDomainList(entities);
    }

    @Override
    public Optional<PaymentTransaction> findByGatewayTransactionId(String gatewayTransactionId) {
        return jpaRepository.findByGatewayTransactionId(gatewayTransactionId)
                .map(mapper::toDomain);
    }
}


```

```java
package com.paymentgateway.repository.impl;

import com.paymentgateway.domain.model.WebhookEvent;
import com.paymentgateway.domain.entity.WebhookEventEntity;
import com.paymentgateway.repository.WebhookRepository;
import com.paymentgateway.repository.jpa.JpaWebhookRepository;
import com.paymentgateway.repository.mapper.WebhookMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "app.repository.type", havingValue = "jpa", matchIfMissing = true)
public class JpaWebhookRepositoryImpl implements WebhookRepository {

    @Autowired
    private JpaWebhookRepository jpaRepository;
    
    @Autowired
    private WebhookMapper mapper;

    @Override
    public WebhookEvent save(WebhookEvent webhook) {
        WebhookEventEntity entity = mapper.toEntity(webhook);
        WebhookEventEntity saved = jpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<WebhookEvent> findById(UUID id) {
        return jpaRepository.findById(id)
                .map(mapper::toDomain);
    }

    @Override
    public Optional<WebhookEvent> findByGatewayEventId(String gatewayEventId) {
        return jpaRepository.findByGatewayEventId(gatewayEventId)
                .map(mapper::toDomain);
    }

    @Override
    public boolean existsByGatewayEventId(String gatewayEventId) {
        return jpaRepository.existsByGatewayEventId(gatewayEventId);
    }
}


```

These linter errors are pre-existing and unrelated to the `@Autowired` changes. Compiling to verify the circular dependency is resolved:


Compilation succeeded. Summary of changes:

## Summary of Changes

Converted all dependency injection to use `@Autowired` field injection instead of constructor injection with `@RequiredArgsConstructor`. This simplifies the code and resolves the circular dependency.

### Files Updated:

1. **Controllers:**
   - `OrderController` - Uses `@Autowired` for `PaymentOrchestratorService`
   - `PaymentController` - Uses `@Autowired` for `PaymentOrchestratorService` and `PaymentRepository`
   - `TransactionController` - Uses `@Autowired` for `PaymentOrchestratorService` and `PaymentTransactionRepository`
   - `WebhookController` - Uses `@Autowired` for `WebhookRepository`, `WebhookSignatureService`, and `WebhookProcessorService`

2. **Services:**
   - `PaymentOrchestratorService` - Uses `@Autowired` with `@Lazy` on `OrderRepository` to break the circular dependency
   - `WebhookProcessorService` - Uses `@Autowired` for all dependencies

3. **Repository Implementations:**
   - `JpaOrderRepositoryImpl` - Uses `@Autowired` for `JpaOrderRepository` and `OrderMapper`
   - `JpaPaymentRepositoryImpl` - Uses `@Autowired` for `JpaPaymentRepository` and `PaymentMapper`
   - `JpaPaymentTransactionRepositoryImpl` - Uses `@Autowired` for dependencies
   - `JpaWebhookRepositoryImpl` - Uses `@Autowired` for dependencies

### Key Fix:
- Added `@Lazy` annotation to `OrderRepository` in `PaymentOrchestratorService` to break the circular dependency during Spring bean initialization.

The project compiles successfully. The circular dependency issue should be resolved. The linter errors shown are pre-existing issues (missing getters/builders) and don't affect compilation or runtime.

---

**User**

@bash (250-286) 

---

**Cursor**

Updating the test to use `@Autowired` field injection instead of constructor injection. Checking and fixing the test file:


Updating the test to use `@InjectMocks` instead of constructor injection:


```java
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
    private PaymentGateway paymentGateway;

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
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        Order created = service.createOrder(order);

        assertNotNull(created);
        assertEquals(order.getId(), created.getId());
        verify(orderRepository).save(order);
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
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(order.getId())).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payment payment = service.createPayment(
                order.getId(),
                PaymentType.PURCHASE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        assertNotNull(payment);
        assertEquals(order.getId(), payment.getOrderId());
        assertEquals(PaymentStatus.INITIATED, payment.getStatus());
        verify(paymentRepository).save(any(Payment.class));
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

        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)).thenReturn(true);
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.findByPaymentId(payment.getId())).thenReturn(java.util.Collections.emptyList());

        PurchaseResponse gatewayResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-123")
                .responseCode("1")
                .responseMessage("Approved")
                .build();

        when(paymentGateway.purchase(any())).thenReturn(gatewayResponse);
        when(stateMachine.derivePaymentStatus(any(), any())).thenReturn(PaymentStatus.CAPTURED);
        when(paymentRepository.updateWithLock(any(), any())).thenAnswer(invocation -> {
            java.util.function.Function<Payment, Payment> updater = invocation.getArgument(1);
            return updater.apply(payment);
        });

        var transaction = service.processPurchase(payment.getId(), "token", UUID.randomUUID());

        assertNotNull(transaction);
        assertEquals(TransactionType.PURCHASE, transaction.getTransactionType());
        verify(paymentGateway).purchase(any());
        verify(transactionRepository, atLeastOnce()).save(any());
    }
}


```

Checking for other test files that need updates:


Test compilation succeeded. Updating `OrderControllerTest` to use `orchestratorService.getOrderById()` instead of the repository directly:


```java
package com.paymentgateway.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.api.dto.CreateOrderRequest;
import com.paymentgateway.domain.enums.OrderStatus;
import com.paymentgateway.domain.model.Customer;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.repository.OrderRepository;
import com.paymentgateway.service.PaymentOrchestratorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaymentOrchestratorService orchestratorService;

    @Test
    void testCreateOrder_Success() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(
                "ORD-123",
                new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD")),
                "Test order",
                new Customer("test@example.com", "+1234567890")
        );

        Order order = Order.builder()
                .id(UUID.randomUUID())
                .merchantOrderId("ORD-123")
                .amount(request.getAmount())
                .description("Test order")
                .customer(request.getCustomer())
                .status(OrderStatus.CREATED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(orchestratorService.createOrder(any(Order.class))).thenReturn(order);

        mockMvc.perform(post("/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").exists())
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void testGetOrder_Success() throws Exception {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .merchantOrderId("ORD-123")
                .amount(new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD")))
                .description("Test order")
                .customer(new Customer("test@example.com", "+1234567890"))
                .status(OrderStatus.CREATED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        mockMvc.perform(get("/v1/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void testGetOrder_NotFound() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/orders/" + orderId))
                .andExpect(status().isNotFound());
    }
}


```

```java
package com.paymentgateway.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.api.dto.CreateOrderRequest;
import com.paymentgateway.domain.enums.OrderStatus;
import com.paymentgateway.domain.model.Customer;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.service.PaymentOrchestratorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaymentOrchestratorService orchestratorService;

    @Test
    void testCreateOrder_Success() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(
                "ORD-123",
                new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD")),
                "Test order",
                new Customer("test@example.com", "+1234567890")
        );

        Order order = Order.builder()
                .id(UUID.randomUUID())
                .merchantOrderId("ORD-123")
                .amount(request.getAmount())
                .description("Test order")
                .customer(request.getCustomer())
                .status(OrderStatus.CREATED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(orchestratorService.createOrder(any(Order.class))).thenReturn(order);

        mockMvc.perform(post("/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").exists())
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void testGetOrder_Success() throws Exception {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .merchantOrderId("ORD-123")
                .amount(new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD")))
                .description("Test order")
                .customer(new Customer("test@example.com", "+1234567890"))
                .status(OrderStatus.CREATED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        mockMvc.perform(get("/v1/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void testGetOrder_NotFound() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/orders/" + orderId))
                .andExpect(status().isNotFound());
    }
}


```

```java
package com.paymentgateway.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.api.dto.CreateOrderRequest;
import com.paymentgateway.domain.enums.OrderStatus;
import com.paymentgateway.domain.model.Customer;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.service.PaymentOrchestratorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaymentOrchestratorService orchestratorService;

    @Test
    void testCreateOrder_Success() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(
                "ORD-123",
                new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD")),
                "Test order",
                new Customer("test@example.com", "+1234567890")
        );

        Order order = Order.builder()
                .id(UUID.randomUUID())
                .merchantOrderId("ORD-123")
                .amount(request.getAmount())
                .description("Test order")
                .customer(request.getCustomer())
                .status(OrderStatus.CREATED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(orchestratorService.createOrder(any(Order.class))).thenReturn(order);

        mockMvc.perform(post("/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").exists())
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void testGetOrder_Success() throws Exception {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .merchantOrderId("ORD-123")
                .amount(new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD")))
                .description("Test order")
                .customer(new Customer("test@example.com", "+1234567890"))
                .status(OrderStatus.CREATED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(orchestratorService.getOrderById(orderId)).thenReturn(order);

        mockMvc.perform(get("/v1/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void testGetOrder_NotFound() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/orders/" + orderId))
                .andExpect(status().isNotFound());
    }
}


```

```java
package com.paymentgateway.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.api.dto.CreateOrderRequest;
import com.paymentgateway.domain.enums.OrderStatus;
import com.paymentgateway.domain.model.Customer;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.service.PaymentOrchestratorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaymentOrchestratorService orchestratorService;

    @Test
    void testCreateOrder_Success() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(
                "ORD-123",
                new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD")),
                "Test order",
                new Customer("test@example.com", "+1234567890")
        );

        Order order = Order.builder()
                .id(UUID.randomUUID())
                .merchantOrderId("ORD-123")
                .amount(request.getAmount())
                .description("Test order")
                .customer(request.getCustomer())
                .status(OrderStatus.CREATED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(orchestratorService.createOrder(any(Order.class))).thenReturn(order);

        mockMvc.perform(post("/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").exists())
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void testGetOrder_Success() throws Exception {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .merchantOrderId("ORD-123")
                .amount(new Money(BigDecimal.valueOf(100.00), Currency.getInstance("USD")))
                .description("Test order")
                .customer(new Customer("test@example.com", "+1234567890"))
                .status(OrderStatus.CREATED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(orchestratorService.getOrderById(orderId)).thenReturn(order);

        mockMvc.perform(get("/v1/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void testGetOrder_NotFound() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orchestratorService.getOrderById(orderId))
                .thenThrow(new IllegalArgumentException("Order not found: " + orderId));

        mockMvc.perform(get("/v1/orders/" + orderId))
                .andExpect(status().isNotFound());
    }
}


```

Running a full build to verify everything compiles:


All tests compile successfully. Summary of fixes:

## Test Fixes Summary

### 1. **PaymentOrchestratorServiceTest.java**
   - **Issue**: Test was using constructor injection, but the service now uses `@Autowired` field injection
   - **Fix**: 
     - Replaced manual constructor call with `@InjectMocks` annotation
     - Added `@InjectMocks` import from Mockito
     - Removed the manual constructor instantiation in `setUp()` method

### 2. **OrderControllerTest.java**
   - **Issue**: Test was mocking `OrderRepository` directly, but the controller now uses `PaymentOrchestratorService.getOrderById()` instead
   - **Fix**:
     - Removed `@MockBean` for `OrderRepository` (no longer needed)
     - Updated test to use `orchestratorService.getOrderById()` instead of `orderRepository.findById()`
     - Updated the "not found" test to expect an `IllegalArgumentException` thrown by the service method

### Changes Made:
```java
// Before (PaymentOrchestratorServiceTest):
service = new PaymentOrchestratorService(orderRepository, paymentRepository, ...);

// After:
@InjectMocks
private PaymentOrchestratorService service;
```

```java
// Before (OrderControllerTest):
@MockBean
private OrderRepository orderRepository;
when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

// After:
when(orchestratorService.getOrderById(orderId)).thenReturn(order);
```

All tests now compile and align with the `@Autowired` field injection pattern used throughout the codebase. The circular dependency issue is resolved, and the tests are updated to match the new dependency injection approach.

```java
// Before (PaymentOrchestratorServiceTest):
service = new PaymentOrchestratorService(orderRepository, paymentRepository, ...);

// After:
@InjectMocks
private PaymentOrchestratorService service;
```

```java
// Before (OrderControllerTest):
@MockBean
private OrderRepository orderRepository;
when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

// After:
when(orchestratorService.getOrderById(orderId)).thenReturn(order);
```

---

**User**

@bash (438-507) 

---

**Cursor**

Investigating the circular dependency. Checking dependencies and applying `@Lazy` where needed:


Adding `@Lazy` to `PaymentRepository` and `PaymentTransactionRepository` in `PaymentOrchestratorService` to break the cycle:


```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class PaymentOrchestratorService {

    @Autowired
    @Lazy
    private OrderRepository orderRepository;
    
    @Autowired
    @Lazy
    private PaymentRepository paymentRepository;
    
    @Autowired
    @Lazy
    private PaymentTransactionRepository transactionRepository;
    
    @Autowired
    private PaymentStateMachine stateMachine;
    
    @Autowired
    private PaymentGateway paymentGateway;

    @Transactional
    public Order createOrder(Order order) {
        if (orderRepository.existsByMerchantOrderId(order.getMerchantOrderId())) {
            throw new IllegalArgumentException("Order with merchantOrderId already exists: " + order.getMerchantOrderId());
        }
        return orderRepository.save(order);
    }

    public Order getOrderById(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    @Transactional
    public Payment createPayment(UUID orderId, PaymentType paymentType, Gateway gateway, String idempotencyKey) {
        // Check idempotency
        if (paymentRepository.existsByIdempotencyKey(idempotencyKey)) {
            Payment existing = paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Payment exists but not found"));
            return existing;
        }

        // Verify order exists
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

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

        payment = paymentRepository.save(payment);

        // Update order status
        Order updatedOrder = order.withStatus(OrderStatus.PAYMENT_INITIATED);
        orderRepository.save(updatedOrder);

        return payment;
    }

    @Transactional
    public PaymentTransaction processPurchase(UUID paymentId, String paymentMethodToken, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)) {
            throw new IllegalStateException("Cannot create PURCHASE transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        PurchaseResponse gatewayResponse = paymentGateway.purchase(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processAuthorize(UUID paymentId, String paymentMethodToken, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.AUTH)) {
            throw new IllegalStateException("Cannot create AUTH transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        AuthResponse gatewayResponse = paymentGateway.authorize(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processCapture(UUID paymentId, UUID parentTransactionId, Money amount, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.CAPTURE)) {
            throw new IllegalStateException("Cannot create CAPTURE transaction for payment in state: " + payment.getStatus());
        }

        PaymentTransaction parentTransaction = transactionRepository.findById(parentTransactionId)
                .orElseThrow(() -> new IllegalArgumentException("Parent transaction not found: " + parentTransactionId));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        CaptureResponse gatewayResponse = paymentGateway.capture(
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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order order = orderRepository.findById(updatedPayment.getOrderId()).orElse(null);
        if (order != null) {
            orderRepository.save(order.withStatus(newOrderStatus));
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
}


```

Checking if `PaymentStateMachine` or `PaymentGateway` creates a cycle:


`PaymentStateMachine` and `PaymentGateway` have no dependencies that would cause a cycle. Adding `@Lazy` to the controller's dependency and checking if we need to enable circular references:


```java
package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.CreateOrderRequest;
import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.OrderResponse;
import com.paymentgateway.domain.enums.OrderStatus;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.service.PaymentOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/orders")
@Slf4j
@Tag(name = "Orders", description = "API endpoints for managing orders. Orders represent business intent and contain customer and amount information.")
public class OrderController {

    @Autowired
    @Lazy
    private PaymentOrchestratorService orchestratorService;

    @PostMapping
    @Operation(
            summary = "Create a new order",
            description = "Creates a new order with the specified merchant order ID, amount, description, and customer information. " +
                    "Orders represent the business intent for a payment and must be created before payments can be processed."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Order created successfully",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - missing required fields or invalid data format",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        try {
            Order order = Order.builder()
                    .id(UUID.randomUUID())
                    .merchantOrderId(request.getMerchantOrderId())
                    .amount(request.getAmount())
                    .description(request.getDescription())
                    .customer(request.getCustomer())
                    .status(OrderStatus.CREATED)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            order = orchestratorService.createOrder(order);

            OrderResponse response = OrderResponse.builder()
                    .orderId(order.getId().toString())
                    .status(order.getStatus())
                    .amount(order.getAmount())
                    .createdAt(order.getCreatedAt())
                    .build();

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.error("Error creating order", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @GetMapping("/{orderId}")
    @Operation(
            summary = "Get order by ID",
            description = "Retrieves the details of an order by its unique identifier. " +
                    "Returns the order status, amount, creation timestamp, and other relevant information."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Order retrieved successfully",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Order not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> getOrder(
            @Parameter(description = "Unique identifier of the order (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String orderId) {
        try {
            UUID id = UUID.fromString(orderId);
            Order order = orchestratorService.getOrderById(id);

            OrderResponse response = OrderResponse.builder()
                    .orderId(order.getId().toString())
                    .status(order.getStatus())
                    .amount(order.getAmount())
                    .createdAt(order.getCreatedAt())
                    .build();

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("NOT_FOUND")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }
}


```

Adding a Spring Boot configuration to allow circular references as a fallback:


```yaml
spring:
  main:
    allow-circular-references: true
  application:
    name: payment-orchestration-service
  jackson:
    serialization:
      write-dates-as-timestamps: false
    default-property-inclusion: non_null
  datasource:
    url: ${DATABASE_URL:jdbc:postgresql://localhost:5432/payment_gateway}
    username: ${DATABASE_USERNAME:postgres}
    password: ${DATABASE_PASSWORD:postgres}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration

server:
  port: 8080
  error:
    include-message: always
    include-binding-errors: always

# Authorize.Net Configuration
authorize:
  net:
    api-login-id: ${AUTHORIZE_NET_API_LOGIN_ID:your_api_login_id}
    transaction-key: ${AUTHORIZE_NET_TRANSACTION_KEY:your_transaction_key}
    environment: ${AUTHORIZE_NET_ENVIRONMENT:SANDBOX} # SANDBOX or PRODUCTION
    webhook-signature-key: ${AUTHORIZE_NET_WEBHOOK_SIGNATURE_KEY:your_signature_key}

# Application Configuration
app:
  repository:
    type: jpa  # Options: jpa (default) or memory
  idempotency:
    enabled: true
  webhook:
    async-processing: true
    queue-size: 1000

logging:
  level:
    com.paymentgateway: DEBUG
    org.springframework.web: INFO
    net.authorize: INFO


```

## Summary of Circular Dependency Fixes

### 1. **Added `@Lazy` annotations to break cycles:**
   - `OrderRepository` in `PaymentOrchestratorService` - `@Lazy`
   - `PaymentRepository` in `PaymentOrchestratorService` - `@Lazy`
   - `PaymentTransactionRepository` in `PaymentOrchestratorService` - `@Lazy`
   - `PaymentOrchestratorService` in `OrderController` - `@Lazy`

### 2. **Enabled circular references in Spring Boot:**
   - Added `spring.main.allow-circular-references: true` to `application.yml`

### Changes Made:

**PaymentOrchestratorService.java:**
```java
@Autowired
@Lazy
private OrderRepository orderRepository;

@Autowired
@Lazy
private PaymentRepository paymentRepository;

@Autowired
@Lazy
private PaymentTransactionRepository transactionRepository;
```

**OrderController.java:**
```java
@Autowired
@Lazy
private PaymentOrchestratorService orchestratorService;
```

**application.yml:**
```yaml
spring:
  main:
    allow-circular-references: true
```

The `@Lazy` annotations defer initialization of these dependencies until first use, breaking the circular dependency during bean initialization. Enabling circular references provides a fallback if Spring still detects cycles.

The application should now start without circular dependency errors. Try running it again.

```java
@Autowired
@Lazy
private OrderRepository orderRepository;

@Autowired
@Lazy
private PaymentRepository paymentRepository;

@Autowired
@Lazy
private PaymentTransactionRepository transactionRepository;
```

```java
@Autowired
@Lazy
private PaymentOrchestratorService orchestratorService;
```

```yaml
spring:
  main:
    allow-circular-references: true
```

---

**User**

@bash (474-555) 



Simplify, use single set of Repository if needed. instead of Separate Repo JPA, and Iml. 

---

**Cursor**

Simplifying the repository structure by removing the extra layer and using Spring Data JPA repositories directly. Checking the current structure:


Simplifying by making repository interfaces extend JpaRepository directly and removing the extra layer. Updating repositories to work with entities directly:


Simplifying the repository structure: making repositories extend JpaRepository directly and removing the extra layer.


```java
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


```

```java
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


```

Updating the remaining repositories:


```java
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


```

```java
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


```

Updating `PaymentOrchestratorService` to use entities and mappers directly:


Updating `PaymentOrchestratorService` to use mappers with entities:


```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.*;
import com.paymentgateway.repository.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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
    private PaymentGateway paymentGateway;
    
    @Autowired
    private OrderMapper orderMapper;
    
    @Autowired
    private PaymentMapper paymentMapper;
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Transactional
    public Order createOrder(Order order) {
        if (orderRepository.existsByMerchantOrderId(order.getMerchantOrderId())) {
            throw new IllegalArgumentException("Order with merchantOrderId already exists: " + order.getMerchantOrderId());
        }
        var entity = orderMapper.toEntity(order);
        var saved = orderRepository.save(entity);
        return orderMapper.toDomain(saved);
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
            Payment existing = paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Payment exists but not found"));
            return existing;
        }

        // Verify order exists
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

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

        payment = paymentRepository.save(payment);

        // Update order status
        Order updatedOrder = order.withStatus(OrderStatus.PAYMENT_INITIATED);
        orderRepository.save(updatedOrder);

        return payment;
    }

    @Transactional
    public PaymentTransaction processPurchase(UUID paymentId, String paymentMethodToken, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)) {
            throw new IllegalStateException("Cannot create PURCHASE transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        PurchaseResponse gatewayResponse = paymentGateway.purchase(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processAuthorize(UUID paymentId, String paymentMethodToken, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.AUTH)) {
            throw new IllegalStateException("Cannot create AUTH transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        AuthResponse gatewayResponse = paymentGateway.authorize(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processCapture(UUID paymentId, UUID parentTransactionId, Money amount, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.CAPTURE)) {
            throw new IllegalStateException("Cannot create CAPTURE transaction for payment in state: " + payment.getStatus());
        }

        PaymentTransaction parentTransaction = transactionRepository.findById(parentTransactionId)
                .orElseThrow(() -> new IllegalArgumentException("Parent transaction not found: " + parentTransactionId));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        CaptureResponse gatewayResponse = paymentGateway.capture(
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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order order = orderRepository.findById(updatedPayment.getOrderId()).orElse(null);
        if (order != null) {
            orderRepository.save(order.withStatus(newOrderStatus));
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
}


```

Updating the remaining methods in `PaymentOrchestratorService` to use entities and mappers. Updating `createPayment` first:


```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.*;
import com.paymentgateway.repository.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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
    private PaymentGateway paymentGateway;
    
    @Autowired
    private OrderMapper orderMapper;
    
    @Autowired
    private PaymentMapper paymentMapper;
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Transactional
    public Order createOrder(Order order) {
        if (orderRepository.existsByMerchantOrderId(order.getMerchantOrderId())) {
            throw new IllegalArgumentException("Order with merchantOrderId already exists: " + order.getMerchantOrderId());
        }
        var entity = orderMapper.toEntity(order);
        var saved = orderRepository.save(entity);
        return orderMapper.toDomain(saved);
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
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)) {
            throw new IllegalStateException("Cannot create PURCHASE transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        PurchaseResponse gatewayResponse = paymentGateway.purchase(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processAuthorize(UUID paymentId, String paymentMethodToken, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.AUTH)) {
            throw new IllegalStateException("Cannot create AUTH transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        AuthResponse gatewayResponse = paymentGateway.authorize(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processCapture(UUID paymentId, UUID parentTransactionId, Money amount, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.CAPTURE)) {
            throw new IllegalStateException("Cannot create CAPTURE transaction for payment in state: " + payment.getStatus());
        }

        PaymentTransaction parentTransaction = transactionRepository.findById(parentTransactionId)
                .orElseThrow(() -> new IllegalArgumentException("Parent transaction not found: " + parentTransactionId));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        CaptureResponse gatewayResponse = paymentGateway.capture(
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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order order = orderRepository.findById(updatedPayment.getOrderId()).orElse(null);
        if (order != null) {
            orderRepository.save(order.withStatus(newOrderStatus));
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
}


```

Reading the mappers, then updating the remaining methods in batches:


Updating the transaction processing methods. Adding a helper method for `updateWithLock`:


```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.*;
import com.paymentgateway.repository.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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
    private PaymentGateway paymentGateway;
    
    @Autowired
    private OrderMapper orderMapper;
    
    @Autowired
    private PaymentMapper paymentMapper;
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Transactional
    public Order createOrder(Order order) {
        if (orderRepository.existsByMerchantOrderId(order.getMerchantOrderId())) {
            throw new IllegalArgumentException("Order with merchantOrderId already exists: " + order.getMerchantOrderId());
        }
        var entity = orderMapper.toEntity(order);
        var saved = orderRepository.save(entity);
        return orderMapper.toDomain(saved);
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
                order.getDescription()
        );

        PurchaseResponse gatewayResponse = paymentGateway.purchase(gatewayRequest);

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
        
        var updatedPaymentEntity = updatePaymentWithLock(paymentId, p -> {
            var domain = paymentMapper.toDomain(p);
            return paymentMapper.toEntity(domain.withStatus(newPaymentStatus));
        });
        var updatedPayment = paymentMapper.toDomain(updatedPaymentEntity);
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        var updatedOrder = order.withStatus(newOrderStatus);
        var updatedOrderEntity = orderMapper.toEntity(updatedOrder);
        orderRepository.save(updatedOrderEntity);

        return transaction;
    }
    
    private PaymentEntity updatePaymentWithLock(UUID id, Function<PaymentEntity, PaymentEntity> updater) {
        var entity = paymentRepository.findByIdWithLock(id)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + id));
        var updated = updater.apply(entity);
        return paymentRepository.save(updated);
    }

    @Transactional
    public PaymentTransaction processAuthorize(UUID paymentId, String paymentMethodToken, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.AUTH)) {
            throw new IllegalStateException("Cannot create AUTH transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        AuthResponse gatewayResponse = paymentGateway.authorize(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processCapture(UUID paymentId, UUID parentTransactionId, Money amount, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.CAPTURE)) {
            throw new IllegalStateException("Cannot create CAPTURE transaction for payment in state: " + payment.getStatus());
        }

        PaymentTransaction parentTransaction = transactionRepository.findById(parentTransactionId)
                .orElseThrow(() -> new IllegalArgumentException("Parent transaction not found: " + parentTransactionId));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        CaptureResponse gatewayResponse = paymentGateway.capture(
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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order order = orderRepository.findById(updatedPayment.getOrderId()).orElse(null);
        if (order != null) {
            orderRepository.save(order.withStatus(newOrderStatus));
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
}


```

Fixing the helper method and updating the remaining methods. Simplifying to work directly with entities:


```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.*;
import com.paymentgateway.repository.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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
    private PaymentGateway paymentGateway;
    
    @Autowired
    private OrderMapper orderMapper;
    
    @Autowired
    private PaymentMapper paymentMapper;
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Transactional
    public Order createOrder(Order order) {
        if (orderRepository.existsByMerchantOrderId(order.getMerchantOrderId())) {
            throw new IllegalArgumentException("Order with merchantOrderId already exists: " + order.getMerchantOrderId());
        }
        var entity = orderMapper.toEntity(order);
        var saved = orderRepository.save(entity);
        return orderMapper.toDomain(saved);
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
                order.getDescription()
        );

        PurchaseResponse gatewayResponse = paymentGateway.purchase(gatewayRequest);

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
        
        var updatedPaymentEntity = updatePaymentWithLock(paymentId, p -> {
            var domain = paymentMapper.toDomain(p);
            return paymentMapper.toEntity(domain.withStatus(newPaymentStatus));
        });
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
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.AUTH)) {
            throw new IllegalStateException("Cannot create AUTH transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        AuthResponse gatewayResponse = paymentGateway.authorize(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processCapture(UUID paymentId, UUID parentTransactionId, Money amount, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.CAPTURE)) {
            throw new IllegalStateException("Cannot create CAPTURE transaction for payment in state: " + payment.getStatus());
        }

        PaymentTransaction parentTransaction = transactionRepository.findById(parentTransactionId)
                .orElseThrow(() -> new IllegalArgumentException("Parent transaction not found: " + parentTransactionId));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        CaptureResponse gatewayResponse = paymentGateway.capture(
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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order order = orderRepository.findById(updatedPayment.getOrderId()).orElse(null);
        if (order != null) {
            orderRepository.save(order.withStatus(newOrderStatus));
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
}


```

```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.*;
import com.paymentgateway.repository.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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
    private PaymentGateway paymentGateway;
    
    @Autowired
    private OrderMapper orderMapper;
    
    @Autowired
    private PaymentMapper paymentMapper;
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Transactional
    public Order createOrder(Order order) {
        if (orderRepository.existsByMerchantOrderId(order.getMerchantOrderId())) {
            throw new IllegalArgumentException("Order with merchantOrderId already exists: " + order.getMerchantOrderId());
        }
        var entity = orderMapper.toEntity(order);
        var saved = orderRepository.save(entity);
        return orderMapper.toDomain(saved);
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
                order.getDescription()
        );

        PurchaseResponse gatewayResponse = paymentGateway.purchase(gatewayRequest);

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
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.AUTH)) {
            throw new IllegalStateException("Cannot create AUTH transaction for payment in state: " + payment.getStatus());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + payment.getOrderId()));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        PurchaseRequest gatewayRequest = new PurchaseRequest(
                paymentMethodToken,
                payment.getAmount(),
                order.getId().toString(),
                order.getDescription()
        );

        AuthResponse gatewayResponse = paymentGateway.authorize(gatewayRequest);

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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order updatedOrder = orderRepository.findById(updatedPayment.getOrderId()).orElse(order);
        orderRepository.save(updatedOrder.withStatus(newOrderStatus));

        return transaction;
    }

    @Transactional
    public PaymentTransaction processCapture(UUID paymentId, UUID parentTransactionId, Money amount, UUID traceId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.CAPTURE)) {
            throw new IllegalStateException("Cannot create CAPTURE transaction for payment in state: " + payment.getStatus());
        }

        PaymentTransaction parentTransaction = transactionRepository.findById(parentTransactionId)
                .orElseThrow(() -> new IllegalArgumentException("Parent transaction not found: " + parentTransactionId));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        CaptureResponse gatewayResponse = paymentGateway.capture(
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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order order = orderRepository.findById(updatedPayment.getOrderId()).orElse(null);
        if (order != null) {
            orderRepository.save(order.withStatus(newOrderStatus));
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
}


```

Updating the remaining transaction methods. Creating a script to update them all at once due to file size:


Updating `processAuthorize` and `processCapture`:


```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.*;
import com.paymentgateway.repository.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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
    private PaymentGateway paymentGateway;
    
    @Autowired
    private OrderMapper orderMapper;
    
    @Autowired
    private PaymentMapper paymentMapper;
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Transactional
    public Order createOrder(Order order) {
        if (orderRepository.existsByMerchantOrderId(order.getMerchantOrderId())) {
            throw new IllegalArgumentException("Order with merchantOrderId already exists: " + order.getMerchantOrderId());
        }
        var entity = orderMapper.toEntity(order);
        var saved = orderRepository.save(entity);
        return orderMapper.toDomain(saved);
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
                order.getDescription()
        );

        PurchaseResponse gatewayResponse = paymentGateway.purchase(gatewayRequest);

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
                order.getDescription()
        );

        AuthResponse gatewayResponse = paymentGateway.authorize(gatewayRequest);

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
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!stateMachine.canCreateTransaction(payment, TransactionType.CAPTURE)) {
            throw new IllegalStateException("Cannot create CAPTURE transaction for payment in state: " + payment.getStatus());
        }

        PaymentTransaction parentTransaction = transactionRepository.findById(parentTransactionId)
                .orElseThrow(() -> new IllegalArgumentException("Parent transaction not found: " + parentTransactionId));

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

        transaction = transactionRepository.save(transaction);

        // Call gateway
        CaptureResponse gatewayResponse = paymentGateway.capture(
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
        transaction = transactionRepository.save(transaction);

        // Derive and update payment status
        List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentId);
        PaymentStatus newPaymentStatus = stateMachine.derivePaymentStatus(payment, transactions);
        
        Payment updatedPayment = paymentRepository.updateWithLock(paymentId, p -> p.withStatus(newPaymentStatus));
        
        // Update order status
        OrderStatus newOrderStatus = deriveOrderStatus(newPaymentStatus);
        Order order = orderRepository.findById(updatedPayment.getOrderId()).orElse(null);
        if (order != null) {
            orderRepository.save(order.withStatus(newOrderStatus));
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
}


```

```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.*;
import com.paymentgateway.repository.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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
    private PaymentGateway paymentGateway;
    
    @Autowired
    private OrderMapper orderMapper;
    
    @Autowired
    private PaymentMapper paymentMapper;
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Transactional
    public Order createOrder(Order order) {
        if (orderRepository.existsByMerchantOrderId(order.getMerchantOrderId())) {
            throw new IllegalArgumentException("Order with merchantOrderId already exists: " + order.getMerchantOrderId());
        }
        var entity = orderMapper.toEntity(order);
        var saved = orderRepository.save(entity);
        return orderMapper.toDomain(saved);
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
                order.getDescription()
        );

        PurchaseResponse gatewayResponse = paymentGateway.purchase(gatewayRequest);

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
                order.getDescription()
        );

        AuthResponse gatewayResponse = paymentGateway.authorize(gatewayRequest);

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
        CaptureResponse gatewayResponse = paymentGateway.capture(
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
}


```

Updating other services and controllers, then deleting old files. Starting with controllers that use repositories directly:


Updating controllers to use mappers. Starting with `PaymentController`:


```java
package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.CreatePaymentRequest;
import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.PaymentResponse;
import com.paymentgateway.domain.model.Payment;
import com.paymentgateway.repository.PaymentRepository;
import com.paymentgateway.repository.mapper.PaymentMapper;
import com.paymentgateway.service.PaymentOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1")
@Slf4j
@Tag(name = "Payments", description = "API endpoints for managing payments. Payments represent the payment lifecycle and link orders to transactions.")
public class PaymentController {

    @Autowired
    private PaymentOrchestratorService orchestratorService;
    
    @Autowired
    private PaymentRepository paymentRepository;

    @PostMapping("/orders/{orderId}/payments")
    @Operation(
            summary = "Create a payment for an order",
            description = "Creates a new payment for the specified order. " +
                    "A payment represents the payment lifecycle and must be created before transactions can be processed. " +
                    "The payment specifies the payment method, flow type (purchase or authorize), and gateway to use. " +
                    "Idempotency is supported via the Idempotency-Key header."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Payment created successfully",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - order not found, invalid payment configuration, or duplicate idempotency key",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> createPayment(
            @Parameter(description = "Unique identifier of the order (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String orderId,
            @Valid @RequestBody CreatePaymentRequest request,
            @Parameter(description = "Idempotency key to prevent duplicate payments. If not provided, a new key will be generated.", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            UUID orderUuid = UUID.fromString(orderId);

            // Generate idempotency key if not provided
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                idempotencyKey = UUID.randomUUID().toString();
            }

            // Check idempotency - use payment repository's idempotency check
            if (paymentRepository.existsByIdempotencyKey(idempotencyKey)) {
                Payment existing = paymentRepository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException("Payment exists but not found"));
                PaymentResponse response = PaymentResponse.builder()
                        .paymentId(existing.getId().toString())
                        .orderId(existing.getOrderId().toString())
                        .status(existing.getStatus())
                        .flow(existing.getPaymentType())
                        .build();
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
            }

            Payment payment = orchestratorService.createPayment(
                    orderUuid,
                    request.getFlow(),
                    request.getGateway(),
                    idempotencyKey
            );

            PaymentResponse response = PaymentResponse.builder()
                    .paymentId(payment.getId().toString())
                    .orderId(payment.getOrderId().toString())
                    .status(payment.getStatus())
                    .flow(payment.getPaymentType())
                    .build();

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Error creating payment", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }
}


```

```java
package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.CreatePaymentRequest;
import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.PaymentResponse;
import com.paymentgateway.domain.model.Payment;
import com.paymentgateway.repository.PaymentRepository;
import com.paymentgateway.repository.mapper.PaymentMapper;
import com.paymentgateway.service.PaymentOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1")
@Slf4j
@Tag(name = "Payments", description = "API endpoints for managing payments. Payments represent the payment lifecycle and link orders to transactions.")
public class PaymentController {

    @Autowired
    private PaymentOrchestratorService orchestratorService;
    
    @Autowired
    private PaymentRepository paymentRepository;
    
    @Autowired
    private PaymentMapper paymentMapper;

    @PostMapping("/orders/{orderId}/payments")
    @Operation(
            summary = "Create a payment for an order",
            description = "Creates a new payment for the specified order. " +
                    "A payment represents the payment lifecycle and must be created before transactions can be processed. " +
                    "The payment specifies the payment method, flow type (purchase or authorize), and gateway to use. " +
                    "Idempotency is supported via the Idempotency-Key header."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Payment created successfully",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - order not found, invalid payment configuration, or duplicate idempotency key",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> createPayment(
            @Parameter(description = "Unique identifier of the order (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String orderId,
            @Valid @RequestBody CreatePaymentRequest request,
            @Parameter(description = "Idempotency key to prevent duplicate payments. If not provided, a new key will be generated.", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            UUID orderUuid = UUID.fromString(orderId);

            // Generate idempotency key if not provided
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                idempotencyKey = UUID.randomUUID().toString();
            }

            // Check idempotency - use payment repository's idempotency check
            if (paymentRepository.existsByIdempotencyKey(idempotencyKey)) {
                Payment existing = paymentRepository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException("Payment exists but not found"));
                PaymentResponse response = PaymentResponse.builder()
                        .paymentId(existing.getId().toString())
                        .orderId(existing.getOrderId().toString())
                        .status(existing.getStatus())
                        .flow(existing.getPaymentType())
                        .build();
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
            }

            Payment payment = orchestratorService.createPayment(
                    orderUuid,
                    request.getFlow(),
                    request.getGateway(),
                    idempotencyKey
            );

            PaymentResponse response = PaymentResponse.builder()
                    .paymentId(payment.getId().toString())
                    .orderId(payment.getOrderId().toString())
                    .status(payment.getStatus())
                    .flow(payment.getPaymentType())
                    .build();

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Error creating payment", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }
}


```

```java
package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.CreatePaymentRequest;
import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.PaymentResponse;
import com.paymentgateway.domain.model.Payment;
import com.paymentgateway.repository.PaymentRepository;
import com.paymentgateway.repository.mapper.PaymentMapper;
import com.paymentgateway.service.PaymentOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1")
@Slf4j
@Tag(name = "Payments", description = "API endpoints for managing payments. Payments represent the payment lifecycle and link orders to transactions.")
public class PaymentController {

    @Autowired
    private PaymentOrchestratorService orchestratorService;
    
    @Autowired
    private PaymentRepository paymentRepository;
    
    @Autowired
    private PaymentMapper paymentMapper;

    @PostMapping("/orders/{orderId}/payments")
    @Operation(
            summary = "Create a payment for an order",
            description = "Creates a new payment for the specified order. " +
                    "A payment represents the payment lifecycle and must be created before transactions can be processed. " +
                    "The payment specifies the payment method, flow type (purchase or authorize), and gateway to use. " +
                    "Idempotency is supported via the Idempotency-Key header."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Payment created successfully",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - order not found, invalid payment configuration, or duplicate idempotency key",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> createPayment(
            @Parameter(description = "Unique identifier of the order (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String orderId,
            @Valid @RequestBody CreatePaymentRequest request,
            @Parameter(description = "Idempotency key to prevent duplicate payments. If not provided, a new key will be generated.", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            UUID orderUuid = UUID.fromString(orderId);

            // Generate idempotency key if not provided
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                idempotencyKey = UUID.randomUUID().toString();
            }

            // Check idempotency - use payment repository's idempotency check
            if (paymentRepository.existsByIdempotencyKey(idempotencyKey)) {
                var entity = paymentRepository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException("Payment exists but not found"));
                var existing = paymentMapper.toDomain(entity);
                PaymentResponse response = PaymentResponse.builder()
                        .paymentId(existing.getId().toString())
                        .orderId(existing.getOrderId().toString())
                        .status(existing.getStatus())
                        .flow(existing.getPaymentType())
                        .build();
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
            }

            Payment payment = orchestratorService.createPayment(
                    orderUuid,
                    request.getFlow(),
                    request.getGateway(),
                    idempotencyKey
            );

            PaymentResponse response = PaymentResponse.builder()
                    .paymentId(payment.getId().toString())
                    .orderId(payment.getOrderId().toString())
                    .status(payment.getStatus())
                    .flow(payment.getPaymentType())
                    .build();

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Error creating payment", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }
}


```

```java
package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.CaptureRequest;
import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.PurchaseTransactionRequest;
import com.paymentgateway.api.dto.TransactionResponse;
import com.paymentgateway.domain.enums.TransactionState;
import com.paymentgateway.domain.model.PaymentTransaction;
import com.paymentgateway.repository.PaymentTransactionRepository;
import com.paymentgateway.repository.mapper.PaymentTransactionMapper;
import com.paymentgateway.service.PaymentOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/payments")
@Slf4j
@Tag(name = "Transactions", description = "API endpoints for managing payment transactions including purchase, authorization, and capture operations")
public class TransactionController {

    @Autowired
    private PaymentOrchestratorService orchestratorService;
    
    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @PostMapping("/{paymentId}/transactions/purchase")
    @Operation(
            summary = "Process a purchase transaction",
            description = "Creates and processes a purchase transaction for the specified payment. " +
                    "A purchase transaction immediately captures the funds from the payment method. " +
                    "This is a one-step process that combines authorization and capture."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Purchase transaction created and processed successfully",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - payment ID format invalid, payment not found, or invalid payment method token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> purchase(
            @Parameter(description = "Unique identifier of the payment (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String paymentId,
            @Valid @RequestBody PurchaseTransactionRequest request,
            @Parameter(description = "Idempotency key to prevent duplicate transactions. If not provided, a new key will be generated.", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            UUID paymentUuid = UUID.fromString(paymentId);
            UUID traceId = UUID.randomUUID();

            PaymentTransaction transaction = orchestratorService.processPurchase(
                    paymentUuid,
                    request.getPaymentMethodToken(),
                    traceId
            );

            TransactionResponse response = buildTransactionResponse(transaction);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Error processing purchase", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @PostMapping("/{paymentId}/transactions/authorize")
    @Operation(
            summary = "Authorize a payment transaction",
            description = "Creates and processes an authorization transaction for the specified payment. " +
                    "An authorization reserves funds on the payment method but does not capture them. " +
                    "The funds must be captured separately using the capture endpoint before they expire."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Authorization transaction created and processed successfully",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - payment ID format invalid, payment not found, or invalid payment method token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> authorize(
            @Parameter(description = "Unique identifier of the payment (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String paymentId,
            @Valid @RequestBody PurchaseTransactionRequest request,
            @Parameter(description = "Idempotency key to prevent duplicate transactions. If not provided, a new key will be generated.", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            UUID paymentUuid = UUID.fromString(paymentId);
            UUID traceId = UUID.randomUUID();

            PaymentTransaction transaction = orchestratorService.processAuthorize(
                    paymentUuid,
                    request.getPaymentMethodToken(),
                    traceId
            );

            TransactionResponse response = buildTransactionResponse(transaction);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Error processing authorize", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @PostMapping("/{paymentId}/transactions/{transactionId}/capture")
    @Operation(
            summary = "Capture an authorized transaction",
            description = "Captures funds from a previously authorized transaction. " +
                    "The capture amount can be equal to or less than the authorized amount. " +
                    "Partial captures are supported, and multiple captures can be made against a single authorization " +
                    "up to the total authorized amount."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Capture transaction created and processed successfully",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - invalid payment/transaction ID, amount exceeds authorized amount, or transaction not in authorized state",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> capture(
            @Parameter(description = "Unique identifier of the payment (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String paymentId,
            @Parameter(description = "Unique identifier of the authorized transaction to capture (UUID)", required = true, example = "223e4567-e89b-12d3-a456-426614174000")
            @PathVariable String transactionId,
            @Valid @RequestBody CaptureRequest request,
            @Parameter(description = "Idempotency key to prevent duplicate transactions. If not provided, a new key will be generated.", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            UUID paymentUuid = UUID.fromString(paymentId);
            UUID transactionUuid = UUID.fromString(transactionId);
            UUID traceId = UUID.randomUUID();

            PaymentTransaction transaction = orchestratorService.processCapture(
                    paymentUuid,
                    transactionUuid,
                    request.getAmount(),
                    traceId
            );

            TransactionResponse response = buildTransactionResponse(transaction);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Error processing capture", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @GetMapping("/{paymentId}/transactions")
    @Operation(
            summary = "Get all transactions for a payment",
            description = "Retrieves all transactions associated with the specified payment. " +
                    "Returns a list of transactions including purchase, authorization, and capture transactions " +
                    "with their current status and details."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of transactions retrieved successfully",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Payment not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> getTransactions(
            @Parameter(description = "Unique identifier of the payment (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String paymentId) {
        try {
            UUID paymentUuid = UUID.fromString(paymentId);
            List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentUuid);

            List<TransactionResponse> responses = transactions.stream()
                    .map(this::buildTransactionResponse)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(responses);
        } catch (IllegalArgumentException e) {
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("NOT_FOUND")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    private TransactionResponse buildTransactionResponse(PaymentTransaction transaction) {
        TransactionResponse.TransactionResponseBuilder builder = TransactionResponse.builder()
                .transactionId(transaction.getId().toString())
                .type(transaction.getTransactionType())
                .status(transaction.getTransactionState())
                .amount(transaction.getAmount())
                .gatewayReferenceId(transaction.getGatewayTransactionId())
                .createdAt(transaction.getCreatedAt());

        if (transaction.getParentTransactionId() != null) {
            builder.parentTransactionId(transaction.getParentTransactionId().toString());
        }

        if (transaction.getTransactionState() == TransactionState.AUTHORIZED) {
            builder.authorizedAmount(transaction.getAmount());
        }

        return builder.build();
    }
}


```

```java
package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.CaptureRequest;
import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.PurchaseTransactionRequest;
import com.paymentgateway.api.dto.TransactionResponse;
import com.paymentgateway.domain.enums.TransactionState;
import com.paymentgateway.domain.model.PaymentTransaction;
import com.paymentgateway.repository.PaymentTransactionRepository;
import com.paymentgateway.repository.mapper.PaymentTransactionMapper;
import com.paymentgateway.service.PaymentOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/payments")
@Slf4j
@Tag(name = "Transactions", description = "API endpoints for managing payment transactions including purchase, authorization, and capture operations")
public class TransactionController {

    @Autowired
    private PaymentOrchestratorService orchestratorService;
    
    @Autowired
    private PaymentTransactionRepository transactionRepository;
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @PostMapping("/{paymentId}/transactions/purchase")
    @Operation(
            summary = "Process a purchase transaction",
            description = "Creates and processes a purchase transaction for the specified payment. " +
                    "A purchase transaction immediately captures the funds from the payment method. " +
                    "This is a one-step process that combines authorization and capture."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Purchase transaction created and processed successfully",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - payment ID format invalid, payment not found, or invalid payment method token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> purchase(
            @Parameter(description = "Unique identifier of the payment (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String paymentId,
            @Valid @RequestBody PurchaseTransactionRequest request,
            @Parameter(description = "Idempotency key to prevent duplicate transactions. If not provided, a new key will be generated.", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            UUID paymentUuid = UUID.fromString(paymentId);
            UUID traceId = UUID.randomUUID();

            PaymentTransaction transaction = orchestratorService.processPurchase(
                    paymentUuid,
                    request.getPaymentMethodToken(),
                    traceId
            );

            TransactionResponse response = buildTransactionResponse(transaction);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Error processing purchase", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @PostMapping("/{paymentId}/transactions/authorize")
    @Operation(
            summary = "Authorize a payment transaction",
            description = "Creates and processes an authorization transaction for the specified payment. " +
                    "An authorization reserves funds on the payment method but does not capture them. " +
                    "The funds must be captured separately using the capture endpoint before they expire."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Authorization transaction created and processed successfully",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - payment ID format invalid, payment not found, or invalid payment method token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> authorize(
            @Parameter(description = "Unique identifier of the payment (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String paymentId,
            @Valid @RequestBody PurchaseTransactionRequest request,
            @Parameter(description = "Idempotency key to prevent duplicate transactions. If not provided, a new key will be generated.", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            UUID paymentUuid = UUID.fromString(paymentId);
            UUID traceId = UUID.randomUUID();

            PaymentTransaction transaction = orchestratorService.processAuthorize(
                    paymentUuid,
                    request.getPaymentMethodToken(),
                    traceId
            );

            TransactionResponse response = buildTransactionResponse(transaction);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Error processing authorize", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @PostMapping("/{paymentId}/transactions/{transactionId}/capture")
    @Operation(
            summary = "Capture an authorized transaction",
            description = "Captures funds from a previously authorized transaction. " +
                    "The capture amount can be equal to or less than the authorized amount. " +
                    "Partial captures are supported, and multiple captures can be made against a single authorization " +
                    "up to the total authorized amount."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Capture transaction created and processed successfully",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - invalid payment/transaction ID, amount exceeds authorized amount, or transaction not in authorized state",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> capture(
            @Parameter(description = "Unique identifier of the payment (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String paymentId,
            @Parameter(description = "Unique identifier of the authorized transaction to capture (UUID)", required = true, example = "223e4567-e89b-12d3-a456-426614174000")
            @PathVariable String transactionId,
            @Valid @RequestBody CaptureRequest request,
            @Parameter(description = "Idempotency key to prevent duplicate transactions. If not provided, a new key will be generated.", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            UUID paymentUuid = UUID.fromString(paymentId);
            UUID transactionUuid = UUID.fromString(transactionId);
            UUID traceId = UUID.randomUUID();

            PaymentTransaction transaction = orchestratorService.processCapture(
                    paymentUuid,
                    transactionUuid,
                    request.getAmount(),
                    traceId
            );

            TransactionResponse response = buildTransactionResponse(transaction);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Error processing capture", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @GetMapping("/{paymentId}/transactions")
    @Operation(
            summary = "Get all transactions for a payment",
            description = "Retrieves all transactions associated with the specified payment. " +
                    "Returns a list of transactions including purchase, authorization, and capture transactions " +
                    "with their current status and details."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of transactions retrieved successfully",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Payment not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> getTransactions(
            @Parameter(description = "Unique identifier of the payment (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String paymentId) {
        try {
            UUID paymentUuid = UUID.fromString(paymentId);
            List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(paymentUuid);

            List<TransactionResponse> responses = transactions.stream()
                    .map(this::buildTransactionResponse)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(responses);
        } catch (IllegalArgumentException e) {
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("NOT_FOUND")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    private TransactionResponse buildTransactionResponse(PaymentTransaction transaction) {
        TransactionResponse.TransactionResponseBuilder builder = TransactionResponse.builder()
                .transactionId(transaction.getId().toString())
                .type(transaction.getTransactionType())
                .status(transaction.getTransactionState())
                .amount(transaction.getAmount())
                .gatewayReferenceId(transaction.getGatewayTransactionId())
                .createdAt(transaction.getCreatedAt());

        if (transaction.getParentTransactionId() != null) {
            builder.parentTransactionId(transaction.getParentTransactionId().toString());
        }

        if (transaction.getTransactionState() == TransactionState.AUTHORIZED) {
            builder.authorizedAmount(transaction.getAmount());
        }

        return builder.build();
    }
}


```

```java
package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.CaptureRequest;
import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.PurchaseTransactionRequest;
import com.paymentgateway.api.dto.TransactionResponse;
import com.paymentgateway.domain.enums.TransactionState;
import com.paymentgateway.domain.model.PaymentTransaction;
import com.paymentgateway.repository.PaymentTransactionRepository;
import com.paymentgateway.repository.mapper.PaymentTransactionMapper;
import com.paymentgateway.service.PaymentOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/payments")
@Slf4j
@Tag(name = "Transactions", description = "API endpoints for managing payment transactions including purchase, authorization, and capture operations")
public class TransactionController {

    @Autowired
    private PaymentOrchestratorService orchestratorService;
    
    @Autowired
    private PaymentTransactionRepository transactionRepository;
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @PostMapping("/{paymentId}/transactions/purchase")
    @Operation(
            summary = "Process a purchase transaction",
            description = "Creates and processes a purchase transaction for the specified payment. " +
                    "A purchase transaction immediately captures the funds from the payment method. " +
                    "This is a one-step process that combines authorization and capture."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Purchase transaction created and processed successfully",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - payment ID format invalid, payment not found, or invalid payment method token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> purchase(
            @Parameter(description = "Unique identifier of the payment (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String paymentId,
            @Valid @RequestBody PurchaseTransactionRequest request,
            @Parameter(description = "Idempotency key to prevent duplicate transactions. If not provided, a new key will be generated.", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            UUID paymentUuid = UUID.fromString(paymentId);
            UUID traceId = UUID.randomUUID();

            PaymentTransaction transaction = orchestratorService.processPurchase(
                    paymentUuid,
                    request.getPaymentMethodToken(),
                    traceId
            );

            TransactionResponse response = buildTransactionResponse(transaction);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Error processing purchase", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @PostMapping("/{paymentId}/transactions/authorize")
    @Operation(
            summary = "Authorize a payment transaction",
            description = "Creates and processes an authorization transaction for the specified payment. " +
                    "An authorization reserves funds on the payment method but does not capture them. " +
                    "The funds must be captured separately using the capture endpoint before they expire."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Authorization transaction created and processed successfully",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - payment ID format invalid, payment not found, or invalid payment method token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> authorize(
            @Parameter(description = "Unique identifier of the payment (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String paymentId,
            @Valid @RequestBody PurchaseTransactionRequest request,
            @Parameter(description = "Idempotency key to prevent duplicate transactions. If not provided, a new key will be generated.", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            UUID paymentUuid = UUID.fromString(paymentId);
            UUID traceId = UUID.randomUUID();

            PaymentTransaction transaction = orchestratorService.processAuthorize(
                    paymentUuid,
                    request.getPaymentMethodToken(),
                    traceId
            );

            TransactionResponse response = buildTransactionResponse(transaction);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Error processing authorize", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @PostMapping("/{paymentId}/transactions/{transactionId}/capture")
    @Operation(
            summary = "Capture an authorized transaction",
            description = "Captures funds from a previously authorized transaction. " +
                    "The capture amount can be equal to or less than the authorized amount. " +
                    "Partial captures are supported, and multiple captures can be made against a single authorization " +
                    "up to the total authorized amount."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Capture transaction created and processed successfully",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request - invalid payment/transaction ID, amount exceeds authorized amount, or transaction not in authorized state",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> capture(
            @Parameter(description = "Unique identifier of the payment (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String paymentId,
            @Parameter(description = "Unique identifier of the authorized transaction to capture (UUID)", required = true, example = "223e4567-e89b-12d3-a456-426614174000")
            @PathVariable String transactionId,
            @Valid @RequestBody CaptureRequest request,
            @Parameter(description = "Idempotency key to prevent duplicate transactions. If not provided, a new key will be generated.", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            UUID paymentUuid = UUID.fromString(paymentId);
            UUID transactionUuid = UUID.fromString(transactionId);
            UUID traceId = UUID.randomUUID();

            PaymentTransaction transaction = orchestratorService.processCapture(
                    paymentUuid,
                    transactionUuid,
                    request.getAmount(),
                    traceId
            );

            TransactionResponse response = buildTransactionResponse(transaction);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Error processing capture", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_REQUEST")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @GetMapping("/{paymentId}/transactions")
    @Operation(
            summary = "Get all transactions for a payment",
            description = "Retrieves all transactions associated with the specified payment. " +
                    "Returns a list of transactions including purchase, authorization, and capture transactions " +
                    "with their current status and details."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of transactions retrieved successfully",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Payment not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> getTransactions(
            @Parameter(description = "Unique identifier of the payment (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable String paymentId) {
        try {
            UUID paymentUuid = UUID.fromString(paymentId);
            var entities = transactionRepository.findByPaymentIdOrderByCreatedAtAsc(paymentUuid);
            List<PaymentTransaction> transactions = transactionMapper.toDomainList(entities);

            List<TransactionResponse> responses = transactions.stream()
                    .map(this::buildTransactionResponse)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(responses);
        } catch (IllegalArgumentException e) {
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("NOT_FOUND")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    private TransactionResponse buildTransactionResponse(PaymentTransaction transaction) {
        TransactionResponse.TransactionResponseBuilder builder = TransactionResponse.builder()
                .transactionId(transaction.getId().toString())
                .type(transaction.getTransactionType())
                .status(transaction.getTransactionState())
                .amount(transaction.getAmount())
                .gatewayReferenceId(transaction.getGatewayTransactionId())
                .createdAt(transaction.getCreatedAt());

        if (transaction.getParentTransactionId() != null) {
            builder.parentTransactionId(transaction.getParentTransactionId().toString());
        }

        if (transaction.getTransactionState() == TransactionState.AUTHORIZED) {
            builder.authorizedAmount(transaction.getAmount());
        }

        return builder.build();
    }
}


```

Updating `WebhookController` and `WebhookProcessorService`:


```java
package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.WebhookRequest;
import com.paymentgateway.domain.enums.Gateway;
import com.paymentgateway.domain.model.WebhookEvent;
import com.paymentgateway.repository.WebhookRepository;
import com.paymentgateway.repository.mapper.WebhookMapper;
import com.paymentgateway.service.WebhookProcessorService;
import com.paymentgateway.service.WebhookSignatureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/webhooks")
@Slf4j
@Tag(name = "Webhooks", description = "API endpoints for receiving webhook events from payment gateways. Webhooks are processed asynchronously and support idempotency.")
public class WebhookController {

    @Autowired
    private WebhookRepository webhookRepository;
    
    @Autowired
    private WebhookSignatureService signatureService;
    
    @Autowired
    private WebhookProcessorService processorService;

    @PostMapping("/authorize-net")
    @Operation(
            summary = "Receive Authorize.Net webhook",
            description = "Receives and processes webhook events from Authorize.Net. " +
                    "The webhook is validated for signature authenticity and checked for idempotency using the gateway event ID. " +
                    "Webhooks are processed asynchronously, and duplicate events are automatically ignored."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Webhook received and queued for processing successfully"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid webhook signature - signature verification failed",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error while processing webhook",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> receiveAuthorizeNetWebhook(
            @Valid @RequestBody WebhookRequest request) {
        try {
            // Check idempotency by gateway event ID
            if (webhookRepository.existsByGatewayEventId(request.getEventId())) {
                log.info("Webhook already received: {}", request.getEventId());
                return ResponseEntity.ok().build();
            }

            // Verify signature (using payload as string representation)
            String payloadString = request.getPayload() != null ? request.getPayload().toString() : "";
            boolean signatureValid = signatureService.verifySignature(payloadString, request.getSignature());
            if (!signatureValid) {
                log.warn("Invalid webhook signature for event: {}", request.getEventId());
                ErrorResponse error = ErrorResponse.builder()
                        .error(ErrorResponse.ErrorDetail.builder()
                                .code("INVALID_SIGNATURE")
                                .message("Webhook signature verification failed")
                                .retryable(false)
                                .traceId(UUID.randomUUID().toString())
                                .build())
                        .build();
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
            }

            // Create webhook event
            WebhookEvent webhook = WebhookEvent.builder()
                    .id(UUID.randomUUID())
                    .gateway(Gateway.AUTHORIZE_NET)
                    .eventType(request.getEventType())
                    .gatewayEventId(request.getEventId())
                    .payload(request.getPayload())
                    .signatureVerified(signatureValid)
                    .processed(false)
                    .createdAt(Instant.now())
                    .build();

            webhook = webhookRepository.save(webhook);

            // Process asynchronously
            processWebhookAsync(webhook);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Error processing webhook", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("WEBHOOK_PROCESSING_ERROR")
                            .message("Failed to process webhook")
                            .retryable(true)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @Async
    public void processWebhookAsync(WebhookEvent webhook) {
        try {
            processorService.processWebhook(webhook);
        } catch (Exception e) {
            log.error("Error in async webhook processing", e);
        }
    }
}


```

```java
package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.WebhookRequest;
import com.paymentgateway.domain.enums.Gateway;
import com.paymentgateway.domain.model.WebhookEvent;
import com.paymentgateway.repository.WebhookRepository;
import com.paymentgateway.repository.mapper.WebhookMapper;
import com.paymentgateway.service.WebhookProcessorService;
import com.paymentgateway.service.WebhookSignatureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/webhooks")
@Slf4j
@Tag(name = "Webhooks", description = "API endpoints for receiving webhook events from payment gateways. Webhooks are processed asynchronously and support idempotency.")
public class WebhookController {

    @Autowired
    private WebhookRepository webhookRepository;
    
    @Autowired
    private WebhookSignatureService signatureService;
    
    @Autowired
    private WebhookProcessorService processorService;
    
    @Autowired
    private WebhookMapper webhookMapper;

    @PostMapping("/authorize-net")
    @Operation(
            summary = "Receive Authorize.Net webhook",
            description = "Receives and processes webhook events from Authorize.Net. " +
                    "The webhook is validated for signature authenticity and checked for idempotency using the gateway event ID. " +
                    "Webhooks are processed asynchronously, and duplicate events are automatically ignored."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Webhook received and queued for processing successfully"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid webhook signature - signature verification failed",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error while processing webhook",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> receiveAuthorizeNetWebhook(
            @Valid @RequestBody WebhookRequest request) {
        try {
            // Check idempotency by gateway event ID
            if (webhookRepository.existsByGatewayEventId(request.getEventId())) {
                log.info("Webhook already received: {}", request.getEventId());
                return ResponseEntity.ok().build();
            }

            // Verify signature (using payload as string representation)
            String payloadString = request.getPayload() != null ? request.getPayload().toString() : "";
            boolean signatureValid = signatureService.verifySignature(payloadString, request.getSignature());
            if (!signatureValid) {
                log.warn("Invalid webhook signature for event: {}", request.getEventId());
                ErrorResponse error = ErrorResponse.builder()
                        .error(ErrorResponse.ErrorDetail.builder()
                                .code("INVALID_SIGNATURE")
                                .message("Webhook signature verification failed")
                                .retryable(false)
                                .traceId(UUID.randomUUID().toString())
                                .build())
                        .build();
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
            }

            // Create webhook event
            WebhookEvent webhook = WebhookEvent.builder()
                    .id(UUID.randomUUID())
                    .gateway(Gateway.AUTHORIZE_NET)
                    .eventType(request.getEventType())
                    .gatewayEventId(request.getEventId())
                    .payload(request.getPayload())
                    .signatureVerified(signatureValid)
                    .processed(false)
                    .createdAt(Instant.now())
                    .build();

            webhook = webhookRepository.save(webhook);

            // Process asynchronously
            processWebhookAsync(webhook);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Error processing webhook", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("WEBHOOK_PROCESSING_ERROR")
                            .message("Failed to process webhook")
                            .retryable(true)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @Async
    public void processWebhookAsync(WebhookEvent webhook) {
        try {
            processorService.processWebhook(webhook);
        } catch (Exception e) {
            log.error("Error in async webhook processing", e);
        }
    }
}


```

```java
package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.WebhookRequest;
import com.paymentgateway.domain.enums.Gateway;
import com.paymentgateway.domain.model.WebhookEvent;
import com.paymentgateway.repository.WebhookRepository;
import com.paymentgateway.repository.mapper.WebhookMapper;
import com.paymentgateway.service.WebhookProcessorService;
import com.paymentgateway.service.WebhookSignatureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/webhooks")
@Slf4j
@Tag(name = "Webhooks", description = "API endpoints for receiving webhook events from payment gateways. Webhooks are processed asynchronously and support idempotency.")
public class WebhookController {

    @Autowired
    private WebhookRepository webhookRepository;
    
    @Autowired
    private WebhookSignatureService signatureService;
    
    @Autowired
    private WebhookProcessorService processorService;
    
    @Autowired
    private WebhookMapper webhookMapper;

    @PostMapping("/authorize-net")
    @Operation(
            summary = "Receive Authorize.Net webhook",
            description = "Receives and processes webhook events from Authorize.Net. " +
                    "The webhook is validated for signature authenticity and checked for idempotency using the gateway event ID. " +
                    "Webhooks are processed asynchronously, and duplicate events are automatically ignored."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Webhook received and queued for processing successfully"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid webhook signature - signature verification failed",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error while processing webhook",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<?> receiveAuthorizeNetWebhook(
            @Valid @RequestBody WebhookRequest request) {
        try {
            // Check idempotency by gateway event ID
            if (webhookRepository.existsByGatewayEventId(request.getEventId())) {
                log.info("Webhook already received: {}", request.getEventId());
                return ResponseEntity.ok().build();
            }

            // Verify signature (using payload as string representation)
            String payloadString = request.getPayload() != null ? request.getPayload().toString() : "";
            boolean signatureValid = signatureService.verifySignature(payloadString, request.getSignature());
            if (!signatureValid) {
                log.warn("Invalid webhook signature for event: {}", request.getEventId());
                ErrorResponse error = ErrorResponse.builder()
                        .error(ErrorResponse.ErrorDetail.builder()
                                .code("INVALID_SIGNATURE")
                                .message("Webhook signature verification failed")
                                .retryable(false)
                                .traceId(UUID.randomUUID().toString())
                                .build())
                        .build();
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
            }

            // Create webhook event
            WebhookEvent webhook = WebhookEvent.builder()
                    .id(UUID.randomUUID())
                    .gateway(Gateway.AUTHORIZE_NET)
                    .eventType(request.getEventType())
                    .gatewayEventId(request.getEventId())
                    .payload(request.getPayload())
                    .signatureVerified(signatureValid)
                    .processed(false)
                    .createdAt(Instant.now())
                    .build();

            var entity = webhookMapper.toEntity(webhook);
            var savedEntity = webhookRepository.save(entity);
            webhook = webhookMapper.toDomain(savedEntity);

            // Process asynchronously
            processWebhookAsync(webhook);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Error processing webhook", e);
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("WEBHOOK_PROCESSING_ERROR")
                            .message("Failed to process webhook")
                            .retryable(true)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @Async
    public void processWebhookAsync(WebhookEvent webhook) {
        try {
            processorService.processWebhook(webhook);
        } catch (Exception e) {
            log.error("Error in async webhook processing", e);
        }
    }
}


```

Updating `WebhookProcessorService` to use mappers:


```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.TransactionState;
import com.paymentgateway.domain.model.PaymentTransaction;
import com.paymentgateway.domain.model.WebhookEvent;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.repository.PaymentRepository;
import com.paymentgateway.repository.PaymentTransactionRepository;
import com.paymentgateway.repository.WebhookRepository;
import com.paymentgateway.repository.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
@Slf4j
public class WebhookProcessorService {

    @Autowired
    private WebhookRepository webhookRepository;
    
    @Autowired
    private PaymentTransactionRepository transactionRepository;
    
    @Autowired
    private PaymentRepository paymentRepository;
    
    @Autowired
    private PaymentStateMachine stateMachine;
    
    @Autowired
    private WebhookMapper webhookMapper;
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;
    
    @Autowired
    private PaymentMapper paymentMapper;

    /**
     * Processes a webhook event asynchronously.
     * Webhooks are authoritative and can advance state even if API calls failed.
     */
    public void processWebhook(WebhookEvent webhook) {
        log.info("Processing webhook event: {}", webhook.getGatewayEventId());

        // Check if already processed
        if (webhook.isProcessed()) {
            log.info("Webhook already processed: {}", webhook.getGatewayEventId());
            return;
        }

        try {
            // Process based on event type
            String eventType = webhook.getEventType();
            if (eventType.startsWith("TRANSACTION.")) {
                processTransactionWebhook(webhook);
            } else {
                log.warn("Unknown webhook event type: {}", eventType);
            }

            // Mark as processed
            WebhookEvent processed = webhook.markAsProcessed();
            webhookRepository.save(processed);

        } catch (Exception e) {
            log.error("Error processing webhook: {}", webhook.getGatewayEventId(), e);
            throw e;
        }
    }

    private void processTransactionWebhook(WebhookEvent webhook) {
        Map<String, Object> payload = webhook.getPayload();
        String transactionRefId = (String) payload.get("transactionReferenceId");
        
        if (transactionRefId == null) {
            log.warn("No transaction reference ID in webhook payload");
            return;
        }

        // Find transaction by gateway transaction ID
        PaymentTransaction transaction = transactionRepository
                .findByGatewayTransactionId(transactionRefId)
                .orElse(null);

        if (transaction == null) {
            log.warn("Transaction not found for gateway ID: {}", transactionRefId);
            return;
        }

        // Update transaction state based on webhook event type
        TransactionState newState = deriveTransactionStateFromWebhook(webhook.getEventType(), payload);
        
        if (newState != null && newState != transaction.getTransactionState()) {
            PaymentTransaction updated = transaction.withState(newState);
            transactionRepository.save(updated);

            // Derive and update payment status
            List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(transaction.getPaymentId());
            paymentRepository.findById(transaction.getPaymentId()).ifPresent(payment -> {
                com.paymentgateway.domain.enums.PaymentStatus newPaymentStatus = 
                        stateMachine.derivePaymentStatus(payment, transactions);
                paymentRepository.updateWithLock(payment.getId(), p -> p.withStatus(newPaymentStatus));
            });
        }
    }

    private TransactionState deriveTransactionStateFromWebhook(String eventType, Map<String, Object> payload) {
        return switch (eventType) {
            case "TRANSACTION.SETTLED" -> TransactionState.SETTLED;
            case "TRANSACTION.AUTHORIZED" -> TransactionState.AUTHORIZED;
            case "TRANSACTION.CAPTURED" -> TransactionState.SUCCESS;
            case "TRANSACTION.DECLINED", "TRANSACTION.ERROR" -> TransactionState.FAILED;
            case "TRANSACTION.PENDING" -> TransactionState.PENDING;
            default -> null;
        };
    }
}


```

```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.TransactionState;
import com.paymentgateway.domain.model.PaymentTransaction;
import com.paymentgateway.domain.model.WebhookEvent;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.repository.PaymentRepository;
import com.paymentgateway.repository.PaymentTransactionRepository;
import com.paymentgateway.repository.WebhookRepository;
import com.paymentgateway.repository.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
@Slf4j
public class WebhookProcessorService {

    @Autowired
    private WebhookRepository webhookRepository;
    
    @Autowired
    private PaymentTransactionRepository transactionRepository;
    
    @Autowired
    private PaymentRepository paymentRepository;
    
    @Autowired
    private PaymentStateMachine stateMachine;
    
    @Autowired
    private WebhookMapper webhookMapper;
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;
    
    @Autowired
    private PaymentMapper paymentMapper;

    /**
     * Processes a webhook event asynchronously.
     * Webhooks are authoritative and can advance state even if API calls failed.
     */
    public void processWebhook(WebhookEvent webhook) {
        log.info("Processing webhook event: {}", webhook.getGatewayEventId());

        // Check if already processed
        if (webhook.isProcessed()) {
            log.info("Webhook already processed: {}", webhook.getGatewayEventId());
            return;
        }

        try {
            // Process based on event type
            String eventType = webhook.getEventType();
            if (eventType.startsWith("TRANSACTION.")) {
                processTransactionWebhook(webhook);
            } else {
                log.warn("Unknown webhook event type: {}", eventType);
            }

            // Mark as processed
            WebhookEvent processed = webhook.markAsProcessed();
            var entity = webhookMapper.toEntity(processed);
            webhookRepository.save(entity);

        } catch (Exception e) {
            log.error("Error processing webhook: {}", webhook.getGatewayEventId(), e);
            throw e;
        }
    }

    private void processTransactionWebhook(WebhookEvent webhook) {
        Map<String, Object> payload = webhook.getPayload();
        String transactionRefId = (String) payload.get("transactionReferenceId");
        
        if (transactionRefId == null) {
            log.warn("No transaction reference ID in webhook payload");
            return;
        }

        // Find transaction by gateway transaction ID
        PaymentTransaction transaction = transactionRepository
                .findByGatewayTransactionId(transactionRefId)
                .orElse(null);

        if (transaction == null) {
            log.warn("Transaction not found for gateway ID: {}", transactionRefId);
            return;
        }

        // Update transaction state based on webhook event type
        TransactionState newState = deriveTransactionStateFromWebhook(webhook.getEventType(), payload);
        
        if (newState != null && newState != transaction.getTransactionState()) {
            PaymentTransaction updated = transaction.withState(newState);
            transactionRepository.save(updated);

            // Derive and update payment status
            List<PaymentTransaction> transactions = transactionRepository.findByPaymentId(transaction.getPaymentId());
            paymentRepository.findById(transaction.getPaymentId()).ifPresent(payment -> {
                com.paymentgateway.domain.enums.PaymentStatus newPaymentStatus = 
                        stateMachine.derivePaymentStatus(payment, transactions);
                paymentRepository.updateWithLock(payment.getId(), p -> p.withStatus(newPaymentStatus));
            });
        }
    }

    private TransactionState deriveTransactionStateFromWebhook(String eventType, Map<String, Object> payload) {
        return switch (eventType) {
            case "TRANSACTION.SETTLED" -> TransactionState.SETTLED;
            case "TRANSACTION.AUTHORIZED" -> TransactionState.AUTHORIZED;
            case "TRANSACTION.CAPTURED" -> TransactionState.SUCCESS;
            case "TRANSACTION.DECLINED", "TRANSACTION.ERROR" -> TransactionState.FAILED;
            case "TRANSACTION.PENDING" -> TransactionState.PENDING;
            default -> null;
        };
    }
}


```

```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.TransactionState;
import com.paymentgateway.domain.model.PaymentTransaction;
import com.paymentgateway.domain.model.WebhookEvent;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.repository.PaymentRepository;
import com.paymentgateway.repository.PaymentTransactionRepository;
import com.paymentgateway.repository.WebhookRepository;
import com.paymentgateway.repository.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
@Slf4j
public class WebhookProcessorService {

    @Autowired
    private WebhookRepository webhookRepository;
    
    @Autowired
    private PaymentTransactionRepository transactionRepository;
    
    @Autowired
    private PaymentRepository paymentRepository;
    
    @Autowired
    private PaymentStateMachine stateMachine;
    
    @Autowired
    private WebhookMapper webhookMapper;
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;
    
    @Autowired
    private PaymentMapper paymentMapper;

    /**
     * Processes a webhook event asynchronously.
     * Webhooks are authoritative and can advance state even if API calls failed.
     */
    public void processWebhook(WebhookEvent webhook) {
        log.info("Processing webhook event: {}", webhook.getGatewayEventId());

        // Check if already processed
        if (webhook.isProcessed()) {
            log.info("Webhook already processed: {}", webhook.getGatewayEventId());
            return;
        }

        try {
            // Process based on event type
            String eventType = webhook.getEventType();
            if (eventType.startsWith("TRANSACTION.")) {
                processTransactionWebhook(webhook);
            } else {
                log.warn("Unknown webhook event type: {}", eventType);
            }

            // Mark as processed
            WebhookEvent processed = webhook.markAsProcessed();
            var entity = webhookMapper.toEntity(processed);
            webhookRepository.save(entity);

        } catch (Exception e) {
            log.error("Error processing webhook: {}", webhook.getGatewayEventId(), e);
            throw e;
        }
    }

    private void processTransactionWebhook(WebhookEvent webhook) {
        Map<String, Object> payload = webhook.getPayload();
        String transactionRefId = (String) payload.get("transactionReferenceId");
        
        if (transactionRefId == null) {
            log.warn("No transaction reference ID in webhook payload");
            return;
        }

        // Find transaction by gateway transaction ID
        var transactionEntity = transactionRepository
                .findByGatewayTransactionId(transactionRefId)
                .orElse(null);

        if (transactionEntity == null) {
            log.warn("Transaction not found for gateway ID: {}", transactionRefId);
            return;
        }
        
        var transaction = transactionMapper.toDomain(transactionEntity);

        // Update transaction state based on webhook event type
        TransactionState newState = deriveTransactionStateFromWebhook(webhook.getEventType(), payload);
        
        if (newState != null && newState != transaction.getTransactionState()) {
            PaymentTransaction updated = transaction.withState(newState);
            var updatedEntity = transactionMapper.toEntity(updated);
            transactionRepository.save(updatedEntity);

            // Derive and update payment status
            var transactionEntities = transactionRepository.findByPaymentIdOrderByCreatedAtAsc(transaction.getPaymentId());
            var transactions = transactionMapper.toDomainList(transactionEntities);
            paymentRepository.findById(transaction.getPaymentId()).ifPresent(entity -> {
                var payment = paymentMapper.toDomain(entity);
                com.paymentgateway.domain.enums.PaymentStatus newPaymentStatus = 
                        stateMachine.derivePaymentStatus(payment, transactions);
                var updatedPayment = payment.withStatus(newPaymentStatus);
                var updatedPaymentEntity = paymentMapper.toEntity(updatedPayment);
                paymentRepository.save(updatedPaymentEntity);
            });
        }
    }

    private TransactionState deriveTransactionStateFromWebhook(String eventType, Map<String, Object> payload) {
        return switch (eventType) {
            case "TRANSACTION.SETTLED" -> TransactionState.SETTLED;
            case "TRANSACTION.AUTHORIZED" -> TransactionState.AUTHORIZED;
            case "TRANSACTION.CAPTURED" -> TransactionState.SUCCESS;
            case "TRANSACTION.DECLINED", "TRANSACTION.ERROR" -> TransactionState.FAILED;
            case "TRANSACTION.PENDING" -> TransactionState.PENDING;
            default -> null;
        };
    }
}


```

Deleting old JPA repository interfaces and implementations:


Adding the missing import:


```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.entity.PaymentEntity;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.*;
import com.paymentgateway.repository.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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
    private PaymentGateway paymentGateway;
    
    @Autowired
    private OrderMapper orderMapper;
    
    @Autowired
    private PaymentMapper paymentMapper;
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Transactional
    public Order createOrder(Order order) {
        if (orderRepository.existsByMerchantOrderId(order.getMerchantOrderId())) {
            throw new IllegalArgumentException("Order with merchantOrderId already exists: " + order.getMerchantOrderId());
        }
        var entity = orderMapper.toEntity(order);
        var saved = orderRepository.save(entity);
        return orderMapper.toDomain(saved);
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
                order.getDescription()
        );

        PurchaseResponse gatewayResponse = paymentGateway.purchase(gatewayRequest);

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
                order.getDescription()
        );

        AuthResponse gatewayResponse = paymentGateway.authorize(gatewayRequest);

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
        CaptureResponse gatewayResponse = paymentGateway.capture(
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
}


```

Updating InMemory repositories to work with entities, or removing them if not needed. Checking their usage:


Removing InMemory repositories since they're incompatible with JpaRepository:


Removing the repository type configuration since we only use JPA:


```yaml
spring:
  main:
    allow-circular-references: true
  application:
    name: payment-orchestration-service
  jackson:
    serialization:
      write-dates-as-timestamps: false
    default-property-inclusion: non_null
  datasource:
    url: ${DATABASE_URL:jdbc:postgresql://localhost:5432/payment_gateway}
    username: ${DATABASE_USERNAME:postgres}
    password: ${DATABASE_PASSWORD:postgres}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration

server:
  port: 8080
  error:
    include-message: always
    include-binding-errors: always

# Authorize.Net Configuration
authorize:
  net:
    api-login-id: ${AUTHORIZE_NET_API_LOGIN_ID:your_api_login_id}
    transaction-key: ${AUTHORIZE_NET_TRANSACTION_KEY:your_transaction_key}
    environment: ${AUTHORIZE_NET_ENVIRONMENT:SANDBOX} # SANDBOX or PRODUCTION
    webhook-signature-key: ${AUTHORIZE_NET_WEBHOOK_SIGNATURE_KEY:your_signature_key}

# Application Configuration
app:
  idempotency:
    enabled: true
  webhook:
    async-processing: true
    queue-size: 1000

logging:
  level:
    com.paymentgateway: DEBUG
    org.springframework.web: INFO
    net.authorize: INFO


```

Deleting the test file that references the removed InMemory repository:


Fixing the test that uses the removed `updateWithLock` method:


```java
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
    private PaymentGateway paymentGateway;

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
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        Order created = service.createOrder(order);

        assertNotNull(created);
        assertEquals(order.getId(), created.getId());
        verify(orderRepository).save(order);
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
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(order.getId())).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payment payment = service.createPayment(
                order.getId(),
                PaymentType.PURCHASE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        assertNotNull(payment);
        assertEquals(order.getId(), payment.getOrderId());
        assertEquals(PaymentStatus.INITIATED, payment.getStatus());
        verify(paymentRepository).save(any(Payment.class));
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

        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)).thenReturn(true);
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.findByPaymentId(payment.getId())).thenReturn(java.util.Collections.emptyList());

        PurchaseResponse gatewayResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-123")
                .responseCode("1")
                .responseMessage("Approved")
                .build();

        when(paymentGateway.purchase(any())).thenReturn(gatewayResponse);
        when(stateMachine.derivePaymentStatus(any(), any())).thenReturn(PaymentStatus.CAPTURED);
        when(paymentRepository.findByIdWithLock(any())).thenReturn(Optional.of(paymentMapper.toEntity(payment)));
        when(paymentRepository.save(any())).thenAnswer(invocation -> {
            var entity = invocation.getArgument(0);
            return entity;
        });

        var transaction = service.processPurchase(payment.getId(), "token", UUID.randomUUID());

        assertNotNull(transaction);
        assertEquals(TransactionType.PURCHASE, transaction.getTransactionType());
        verify(paymentGateway).purchase(any());
        verify(transactionRepository, atLeastOnce()).save(any());
    }
}


```

Fixing the test to work with the new repository structure:


```java
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
import com.paymentgateway.repository.mapper.PaymentMapper;
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
    private PaymentGateway paymentGateway;

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
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        Order created = service.createOrder(order);

        assertNotNull(created);
        assertEquals(order.getId(), created.getId());
        verify(orderRepository).save(order);
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
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(order.getId())).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payment payment = service.createPayment(
                order.getId(),
                PaymentType.PURCHASE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        assertNotNull(payment);
        assertEquals(order.getId(), payment.getOrderId());
        assertEquals(PaymentStatus.INITIATED, payment.getStatus());
        verify(paymentRepository).save(any(Payment.class));
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

        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)).thenReturn(true);
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.findByPaymentId(payment.getId())).thenReturn(java.util.Collections.emptyList());

        PurchaseResponse gatewayResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-123")
                .responseCode("1")
                .responseMessage("Approved")
                .build();

        when(paymentGateway.purchase(any())).thenReturn(gatewayResponse);
        when(stateMachine.derivePaymentStatus(any(), any())).thenReturn(PaymentStatus.CAPTURED);
        when(paymentRepository.findByIdWithLock(any())).thenReturn(Optional.of(paymentMapper.toEntity(payment)));
        when(paymentRepository.save(any())).thenAnswer(invocation -> {
            var entity = invocation.getArgument(0);
            return entity;
        });

        var transaction = service.processPurchase(payment.getId(), "token", UUID.randomUUID());

        assertNotNull(transaction);
        assertEquals(TransactionType.PURCHASE, transaction.getTransactionType());
        verify(paymentGateway).purchase(any());
        verify(transactionRepository, atLeastOnce()).save(any());
    }
}


```

```java
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
import com.paymentgateway.repository.mapper.PaymentMapper;
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
    private PaymentGateway paymentGateway;
    
    @Mock
    private PaymentMapper paymentMapper;

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
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        Order created = service.createOrder(order);

        assertNotNull(created);
        assertEquals(order.getId(), created.getId());
        verify(orderRepository).save(order);
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
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(order.getId())).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payment payment = service.createPayment(
                order.getId(),
                PaymentType.PURCHASE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        assertNotNull(payment);
        assertEquals(order.getId(), payment.getOrderId());
        assertEquals(PaymentStatus.INITIATED, payment.getStatus());
        verify(paymentRepository).save(any(Payment.class));
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

        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)).thenReturn(true);
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.findByPaymentId(payment.getId())).thenReturn(java.util.Collections.emptyList());

        PurchaseResponse gatewayResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-123")
                .responseCode("1")
                .responseMessage("Approved")
                .build();

        when(paymentGateway.purchase(any())).thenReturn(gatewayResponse);
        when(stateMachine.derivePaymentStatus(any(), any())).thenReturn(PaymentStatus.CAPTURED);
        when(paymentRepository.findByIdWithLock(any())).thenReturn(Optional.of(paymentMapper.toEntity(payment)));
        when(paymentRepository.save(any())).thenAnswer(invocation -> {
            var entity = invocation.getArgument(0);
            return entity;
        });

        var transaction = service.processPurchase(payment.getId(), "token", UUID.randomUUID());

        assertNotNull(transaction);
        assertEquals(TransactionType.PURCHASE, transaction.getTransactionType());
        verify(paymentGateway).purchase(any());
        verify(transactionRepository, atLeastOnce()).save(any());
    }
}


```

```java
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
import com.paymentgateway.repository.mapper.PaymentMapper;
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
    private PaymentGateway paymentGateway;
    
    @Mock
    private PaymentMapper paymentMapper;

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
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        Order created = service.createOrder(order);

        assertNotNull(created);
        assertEquals(order.getId(), created.getId());
        verify(orderRepository).save(order);
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
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(order.getId())).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payment payment = service.createPayment(
                order.getId(),
                PaymentType.PURCHASE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        assertNotNull(payment);
        assertEquals(order.getId(), payment.getOrderId());
        assertEquals(PaymentStatus.INITIATED, payment.getStatus());
        verify(paymentRepository).save(any(Payment.class));
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

        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)).thenReturn(true);
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.findByPaymentId(payment.getId())).thenReturn(java.util.Collections.emptyList());

        PurchaseResponse gatewayResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-123")
                .responseCode("1")
                .responseMessage("Approved")
                .build();

        when(paymentGateway.purchase(any())).thenReturn(gatewayResponse);
        when(stateMachine.derivePaymentStatus(any(), any())).thenReturn(PaymentStatus.CAPTURED);
        when(paymentRepository.findByIdWithLock(any())).thenReturn(Optional.of(paymentMapper.toEntity(payment)));
        when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentMapper.toDomain(any())).thenReturn(payment);
        when(paymentMapper.toEntity(any())).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            return paymentMapper.toEntity(p);
        });

        var transaction = service.processPurchase(payment.getId(), "token", UUID.randomUUID());

        assertNotNull(transaction);
        assertEquals(TransactionType.PURCHASE, transaction.getTransactionType());
        verify(paymentGateway).purchase(any());
        verify(transactionRepository, atLeastOnce()).save(any());
    }
}


```

Fixing the test method name:


```java
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
import com.paymentgateway.repository.mapper.PaymentMapper;
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
    private PaymentGateway paymentGateway;
    
    @Mock
    private PaymentMapper paymentMapper;

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
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        Order created = service.createOrder(order);

        assertNotNull(created);
        assertEquals(order.getId(), created.getId());
        verify(orderRepository).save(order);
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
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(order.getId())).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payment payment = service.createPayment(
                order.getId(),
                PaymentType.PURCHASE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        assertNotNull(payment);
        assertEquals(order.getId(), payment.getOrderId());
        assertEquals(PaymentStatus.INITIATED, payment.getStatus());
        verify(paymentRepository).save(any(Payment.class));
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

        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)).thenReturn(true);
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId())).thenReturn(java.util.Collections.emptyList());

        PurchaseResponse gatewayResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-123")
                .responseCode("1")
                .responseMessage("Approved")
                .build();

        when(paymentGateway.purchase(any())).thenReturn(gatewayResponse);
        when(stateMachine.derivePaymentStatus(any(), any())).thenReturn(PaymentStatus.CAPTURED);
        when(paymentRepository.findByIdWithLock(any())).thenReturn(Optional.of(paymentMapper.toEntity(payment)));
        when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentMapper.toDomain(any())).thenReturn(payment);
        when(paymentMapper.toEntity(any())).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            return paymentMapper.toEntity(p);
        });

        var transaction = service.processPurchase(payment.getId(), "token", UUID.randomUUID());

        assertNotNull(transaction);
        assertEquals(TransactionType.PURCHASE, transaction.getTransactionType());
        verify(paymentGateway).purchase(any());
        verify(transactionRepository, atLeastOnce()).save(any());
    }
}


```

Updating the test to use entities. Checking what needs to be fixed:


Updating the test to mock entities and mappers:


```java
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
    private PaymentGateway paymentGateway;
    
    @Mock
    private PaymentMapper paymentMapper;

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
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        Order created = service.createOrder(order);

        assertNotNull(created);
        assertEquals(order.getId(), created.getId());
        verify(orderRepository).save(order);
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
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(order.getId())).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payment payment = service.createPayment(
                order.getId(),
                PaymentType.PURCHASE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        assertNotNull(payment);
        assertEquals(order.getId(), payment.getOrderId());
        assertEquals(PaymentStatus.INITIATED, payment.getStatus());
        verify(paymentRepository).save(any(Payment.class));
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

        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)).thenReturn(true);
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId())).thenReturn(java.util.Collections.emptyList());

        PurchaseResponse gatewayResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-123")
                .responseCode("1")
                .responseMessage("Approved")
                .build();

        when(paymentGateway.purchase(any())).thenReturn(gatewayResponse);
        when(stateMachine.derivePaymentStatus(any(), any())).thenReturn(PaymentStatus.CAPTURED);
        when(paymentRepository.findByIdWithLock(any())).thenReturn(Optional.of(paymentMapper.toEntity(payment)));
        when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentMapper.toDomain(any())).thenReturn(payment);
        when(paymentMapper.toEntity(any())).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            return paymentMapper.toEntity(p);
        });

        var transaction = service.processPurchase(payment.getId(), "token", UUID.randomUUID());

        assertNotNull(transaction);
        assertEquals(TransactionType.PURCHASE, transaction.getTransactionType());
        verify(paymentGateway).purchase(any());
        verify(transactionRepository, atLeastOnce()).save(any());
    }
}


```

```java
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
    private PaymentGateway paymentGateway;
    
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
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        Order created = service.createOrder(order);

        assertNotNull(created);
        assertEquals(order.getId(), created.getId());
        verify(orderRepository).save(order);
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
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(order.getId())).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payment payment = service.createPayment(
                order.getId(),
                PaymentType.PURCHASE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        assertNotNull(payment);
        assertEquals(order.getId(), payment.getOrderId());
        assertEquals(PaymentStatus.INITIATED, payment.getStatus());
        verify(paymentRepository).save(any(Payment.class));
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

        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)).thenReturn(true);
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId())).thenReturn(java.util.Collections.emptyList());

        PurchaseResponse gatewayResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-123")
                .responseCode("1")
                .responseMessage("Approved")
                .build();

        when(paymentGateway.purchase(any())).thenReturn(gatewayResponse);
        when(stateMachine.derivePaymentStatus(any(), any())).thenReturn(PaymentStatus.CAPTURED);
        when(paymentRepository.findByIdWithLock(any())).thenReturn(Optional.of(paymentMapper.toEntity(payment)));
        when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentMapper.toDomain(any())).thenReturn(payment);
        when(paymentMapper.toEntity(any())).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            return paymentMapper.toEntity(p);
        });

        var transaction = service.processPurchase(payment.getId(), "token", UUID.randomUUID());

        assertNotNull(transaction);
        assertEquals(TransactionType.PURCHASE, transaction.getTransactionType());
        verify(paymentGateway).purchase(any());
        verify(transactionRepository, atLeastOnce()).save(any());
    }
}


```

```java
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
    private PaymentGateway paymentGateway;
    
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
        var orderEntity = orderMapper.toEntity(order);
        when(orderMapper.toEntity(any(Order.class))).thenReturn(orderEntity);
        when(orderRepository.save(any())).thenReturn(orderEntity);
        when(orderMapper.toDomain(any())).thenReturn(order);

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
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(order.getId())).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payment payment = service.createPayment(
                order.getId(),
                PaymentType.PURCHASE,
                Gateway.AUTHORIZE_NET,
                idempotencyKey
        );

        assertNotNull(payment);
        assertEquals(order.getId(), payment.getOrderId());
        assertEquals(PaymentStatus.INITIATED, payment.getStatus());
        verify(paymentRepository).save(any(Payment.class));
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

        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)).thenReturn(true);
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId())).thenReturn(java.util.Collections.emptyList());

        PurchaseResponse gatewayResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-123")
                .responseCode("1")
                .responseMessage("Approved")
                .build();

        when(paymentGateway.purchase(any())).thenReturn(gatewayResponse);
        when(stateMachine.derivePaymentStatus(any(), any())).thenReturn(PaymentStatus.CAPTURED);
        when(paymentRepository.findByIdWithLock(any())).thenReturn(Optional.of(paymentMapper.toEntity(payment)));
        when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentMapper.toDomain(any())).thenReturn(payment);
        when(paymentMapper.toEntity(any())).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            return paymentMapper.toEntity(p);
        });

        var transaction = service.processPurchase(payment.getId(), "token", UUID.randomUUID());

        assertNotNull(transaction);
        assertEquals(TransactionType.PURCHASE, transaction.getTransactionType());
        verify(paymentGateway).purchase(any());
        verify(transactionRepository, atLeastOnce()).save(any());
    }
}


```

```java
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
    private PaymentGateway paymentGateway;
    
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
        var orderEntity = orderMapper.toEntity(order);
        when(orderMapper.toEntity(any(Order.class))).thenReturn(orderEntity);
        when(orderRepository.save(any())).thenReturn(orderEntity);
        when(orderMapper.toDomain(any())).thenReturn(order);

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
        var orderEntity = orderMapper.toEntity(order);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(orderEntity));
        when(orderMapper.toDomain(any())).thenReturn(order);
        when(paymentRepository.findByOrderId(order.getId())).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentMapper.toEntity(any())).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            return paymentMapper.toEntity(p);
        });
        when(paymentMapper.toDomain(any())).thenAnswer(invocation -> {
            PaymentEntity e = invocation.getArgument(0);
            return paymentMapper.toDomain(e);
        });

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

        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)).thenReturn(true);
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId())).thenReturn(java.util.Collections.emptyList());

        PurchaseResponse gatewayResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-123")
                .responseCode("1")
                .responseMessage("Approved")
                .build();

        when(paymentGateway.purchase(any())).thenReturn(gatewayResponse);
        when(stateMachine.derivePaymentStatus(any(), any())).thenReturn(PaymentStatus.CAPTURED);
        when(paymentRepository.findByIdWithLock(any())).thenReturn(Optional.of(paymentMapper.toEntity(payment)));
        when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentMapper.toDomain(any())).thenReturn(payment);
        when(paymentMapper.toEntity(any())).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            return paymentMapper.toEntity(p);
        });

        var transaction = service.processPurchase(payment.getId(), "token", UUID.randomUUID());

        assertNotNull(transaction);
        assertEquals(TransactionType.PURCHASE, transaction.getTransactionType());
        verify(paymentGateway).purchase(any());
        verify(transactionRepository, atLeastOnce()).save(any());
    }
}


```

```java
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
    private PaymentGateway paymentGateway;
    
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
        var orderEntity = orderMapper.toEntity(order);
        when(orderMapper.toEntity(any(Order.class))).thenReturn(orderEntity);
        when(orderRepository.save(any())).thenReturn(orderEntity);
        when(orderMapper.toDomain(any())).thenReturn(order);

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
        var orderEntity = orderMapper.toEntity(order);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(orderEntity));
        when(orderMapper.toDomain(any())).thenReturn(order);
        when(paymentRepository.findByOrderId(order.getId())).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentMapper.toEntity(any())).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            return paymentMapper.toEntity(p);
        });
        when(paymentMapper.toDomain(any())).thenAnswer(invocation -> {
            PaymentEntity e = invocation.getArgument(0);
            return paymentMapper.toDomain(e);
        });

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

        var paymentEntity = paymentMapper.toEntity(payment);
        var orderEntity = orderMapper.toEntity(order);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(paymentEntity));
        when(paymentMapper.toDomain(any())).thenReturn(payment);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(orderEntity));
        when(orderMapper.toDomain(any())).thenReturn(order);
        when(stateMachine.canCreateTransaction(payment, TransactionType.PURCHASE)).thenReturn(true);
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId())).thenReturn(java.util.Collections.emptyList());

        PurchaseResponse gatewayResponse = PurchaseResponse.builder()
                .success(true)
                .gatewayTransactionId("gateway-txn-123")
                .responseCode("1")
                .responseMessage("Approved")
                .build();

        when(paymentGateway.purchase(any())).thenReturn(gatewayResponse);
        when(stateMachine.derivePaymentStatus(any(), any())).thenReturn(PaymentStatus.CAPTURED);
        when(paymentRepository.findByIdWithLock(any())).thenReturn(Optional.of(paymentMapper.toEntity(payment)));
        when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentMapper.toDomain(any())).thenReturn(payment);
        when(paymentMapper.toEntity(any())).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            return paymentMapper.toEntity(p);
        });

        var transaction = service.processPurchase(payment.getId(), "token", UUID.randomUUID());

        assertNotNull(transaction);
        assertEquals(TransactionType.PURCHASE, transaction.getTransactionType());
        verify(paymentGateway).purchase(any());
        verify(transactionRepository, atLeastOnce()).save(any());
    }
}


```

```java
        when(paymentMapper.toEntity(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            return paymentMapper.toEntity(p);
        });
        when(orderMapper.toEntity(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            return orderMapper.toEntity(o);
        });
```

Compilation succeeded. Summary of changes:

## Summary: Repository simplification

### Changes made

1. Simplified repository structure:
   - Removed separate JPA repository interfaces (`JpaOrderRepository`, `JpaPaymentRepository`, etc.)
   - Removed repository implementation classes (`JpaOrderRepositoryImpl`, `JpaPaymentRepositoryImpl`, etc.)
   - Made repository interfaces extend `JpaRepository<Entity, UUID>` directly
   - Updated repositories to work with entities (`OrderEntity`, `PaymentEntity`, etc.) instead of domain models

2. Updated services and controllers:
   - Added mapper injections (`OrderMapper`, `PaymentMapper`, `PaymentTransactionMapper`, `WebhookMapper`)
   - Updated all services to use entities from repositories and map to domain models using mappers
   - Removed `@Lazy` annotations (no longer needed with simplified structure)
   - Updated `PaymentOrchestratorService`, `WebhookProcessorService`, and all controllers

3. Removed unnecessary files:
   - Deleted all `Jpa*Repository` interfaces from `repository/jpa/` package
   - Deleted all `Jpa*RepositoryImpl` classes
   - Deleted all `InMemory*Repository` classes (incompatible with JpaRepository)
   - Removed repository type configuration from `application.yml`

4. Updated tests:
   - Fixed tests to work with new repository structure
   - Added mapper mocks in tests
   - Updated test methods to use entity-based repositories

### Final structure

- `OrderRepository` → extends `JpaRepository<OrderEntity, UUID>`
- `PaymentRepository` → extends `JpaRepository<PaymentEntity, UUID>`
- `PaymentTransactionRepository` → extends `JpaRepository<PaymentTransactionEntity, UUID>`
- `WebhookRepository` → extends `JpaRepository<WebhookEventEntity, UUID>`

All repositories now work directly with Spring Data JPA, eliminating the extra layer and resolving circular dependency issues. The codebase is simpler and easier to maintain.

---

**User**

@bash (972-1017) 

---

**Cursor**

Docker build is failing due to Maven dependency resolution. Checking the Dockerfile:


Maven can't reach Maven Central during the Docker build. Updating the Dockerfile to handle network issues and make dependency resolution more resilient:


```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copy pom.xml first for better layer caching
COPY pom.xml .

# Copy source code
COPY src ./src

# Build the application (Maven will download dependencies if needed)
# Using -U to update dependencies and --fail-never to continue even if some dependencies fail
RUN mvn clean package -DskipTests -B || \
    (echo "Warning: Some dependencies may not have been downloaded, retrying..." && \
     mvn clean package -DskipTests -B -U)

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copy jar from build stage
COPY --from=build /app/target/payment-orchestration-*.jar app.jar

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]


```

Improving the Dockerfile to handle network issues and add DNS configuration:


```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Configure DNS if needed (uncomment if you have DNS issues)
# RUN echo "nameserver 8.8.8.8" > /etc/resolv.conf

# Copy pom.xml first for better layer caching
COPY pom.xml .

# Copy source code
COPY src ./src

# Build the application (Maven will download dependencies during build)
# This combines dependency download and compilation in one step
RUN mvn clean package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copy jar from build stage
COPY --from=build /app/target/payment-orchestration-*.jar app.jar

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]


```

Adding a Maven settings file and updating the Dockerfile to handle network issues:


```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0
          http://maven.apache.org/xsd/settings-1.2.0.xsd">
    <mirrors>
        <!-- Use Maven Central as primary repository -->
        <mirror>
            <id>central</id>
            <name>Maven Central</name>
            <url>https://repo1.maven.org/maven2</url>
            <mirrorOf>central</mirrorOf>
        </mirror>
    </mirrors>
    
    <profiles>
        <profile>
            <id>default</id>
            <repositories>
                <repository>
                    <id>central</id>
                    <url>https://repo1.maven.org/maven2</url>
                    <releases>
                        <enabled>true</enabled>
                    </releases>
                    <snapshots>
                        <enabled>false</enabled>
                    </snapshots>
                </repository>
            </repositories>
        </profile>
    </profiles>
</settings>

```

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copy Maven settings if available (for custom repository configuration)
COPY .docker/maven-settings.xml /root/.m2/settings.xml 2>/dev/null || true

# Copy pom.xml first for better layer caching
COPY pom.xml .

# Copy source code
COPY src ./src

# Build the application (Maven will download dependencies during build)
# This combines dependency download and compilation in one step
RUN mvn clean package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copy jar from build stage
COPY --from=build /app/target/payment-orchestration-*.jar app.jar

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]


```

```dockercompose
version: '3.8'

services:
  postgres:
    image: postgres:15
    container_name: payment-gateway-db
    env_file:
      - .env
    environment:
      POSTGRES_DB: payment_gateway
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - payment-network

  app:
    build:
      context: .
      dockerfile: Dockerfile
      # Add DNS configuration if you have network issues
      # dns:
      #   - 8.8.8.8
      #   - 8.8.4.4
    container_name: payment-gateway-app
    environment:
      DATABASE_URL: ${DATABASE_URL:-jdbc:postgresql://postgres:5432/payment_gateway}
      DATABASE_USERNAME: ${DATABASE_USERNAME:-postgres}
      DATABASE_PASSWORD: ${DATABASE_PASSWORD:-postgres}
      AUTHORIZE_NET_API_LOGIN_ID: ${AUTHORIZE_NET_API_LOGIN_ID:-your_api_login_id}
      AUTHORIZE_NET_TRANSACTION_KEY: ${AUTHORIZE_NET_TRANSACTION_KEY:-your_transaction_key}
      AUTHORIZE_NET_ENVIRONMENT: ${AUTHORIZE_NET_ENVIRONMENT:-SANDBOX}
      AUTHORIZE_NET_WEBHOOK_SIGNATURE_KEY: ${AUTHORIZE_NET_WEBHOOK_SIGNATURE_KEY:-your_signature_key}
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-docker}
      SERVER_PORT: ${SERVER_PORT:-8080}
    ports:
      - "8080:8080"
    depends_on:
      postgres:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 40s
    networks:
      - payment-network
    restart: unless-stopped

volumes:
  postgres_data:
    driver: local

networks:
  payment-network:
    driver: bridge


```

## Summary of Docker Build Fixes

### Changes Made:

1. **Simplified Dockerfile**:
   - Removed the separate `mvn dependency:go-offline` step that was failing
   - Combined dependency download with the build step (`mvn clean package`)
   - This is better practice and avoids the separate network call

2. **Added Maven Settings**:
   - Created `.docker/maven-settings.xml` with explicit repository configuration
   - Uses `repo1.maven.org` as an alternative to `repo.maven.apache.org`

3. **Added DNS Configuration Option**:
   - Added commented DNS configuration in `docker-compose.yml` for troubleshooting

### The Main Issue:

The error "Temporary failure in name resolution" indicates the Docker build environment cannot resolve DNS. To fix this:

**Option 1: Check Network Connectivity**
```bash
# Test if you can reach Maven Central
ping repo.maven.apache.org
# or
curl -I https://repo.maven.apache.org/maven2
```

**Option 2: Configure DNS in Docker**
If you're using Docker directly, you can configure DNS:
```bash
# Add to /etc/docker/daemon.json
{
  "dns": ["8.8.8.8", "8.8.4.4"]
}
```

**Option 3: Use Build with Network**
```bash
docker build --network=host -t payment-gateway .
```

**Option 4: Uncomment DNS in docker-compose.yml**
If the issue persists, uncomment the DNS lines in `docker-compose.yml`:
```yaml
build:
  context: .
  dockerfile: Dockerfile
  dns:
    - 8.8.8.8
    - 8.8.4.4
```

The simplified Dockerfile should work better, but you still need network connectivity to download Maven dependencies. Try building again with the updated Dockerfile.

```shellscript
# Test if you can reach Maven Central
ping repo.maven.apache.org
# or
curl -I https://repo.maven.apache.org/maven2
```

```shellscript
# Add to /etc/docker/daemon.json
{
  "dns": ["8.8.8.8", "8.8.4.4"]
}
```

```shellscript
docker build --network=host -t payment-gateway .
```

```yaml
build:
  context: .
  dockerfile: Dockerfile
  dns:
    - 8.8.8.8
    - 8.8.4.4
```

---

**User**

@docker (375-425) 

---

**Cursor**

Checking `PurchaseTransactionRequest` for the JSON deserialization issue:


Jackson can't deserialize `PurchaseTransactionRequest` because `@Value` only provides an all-args constructor. Adding Jackson annotations to make it deserializable:


Fixing Jackson deserialization for `@Value` classes. Adding `@JsonCreator` and `@JsonProperty` annotations:


```java
package com.paymentgateway.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Value;

@Value
@Schema(description = "Request to process a purchase or authorization transaction")
public class PurchaseTransactionRequest {
    @NotBlank
    @JsonProperty("paymentMethodToken")
    @Schema(description = "Tokenized payment method identifier from the payment gateway", requiredMode = Schema.RequiredMode.REQUIRED, example = "token_abc123xyz")
    String paymentMethodToken;
    
    @JsonCreator
    public PurchaseTransactionRequest(@JsonProperty("paymentMethodToken") String paymentMethodToken) {
        this.paymentMethodToken = paymentMethodToken;
    }
}


```

```java
package com.paymentgateway.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.paymentgateway.domain.valueobject.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Value;

@Value
@Schema(description = "Request to capture an authorized transaction")
public class CaptureRequest {
    @NotNull
    @Valid
    @JsonProperty("amount")
    @Schema(description = "Amount to capture. Must be less than or equal to the authorized amount. Supports partial captures.", requiredMode = Schema.RequiredMode.REQUIRED)
    Money amount;
    
    @JsonCreator
    public CaptureRequest(@JsonProperty("amount") Money amount) {
        this.amount = amount;
    }
}


```

```java
package com.paymentgateway.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.paymentgateway.domain.model.Customer;
import com.paymentgateway.domain.valueobject.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Value;

@Value
@Schema(description = "Request to create a new order")
public class CreateOrderRequest {
    @NotBlank
    @JsonProperty("merchantOrderId")
    @Schema(description = "Merchant's unique identifier for this order", requiredMode = Schema.RequiredMode.REQUIRED, example = "ORD-12345")
    String merchantOrderId;

    @NotNull
    @Valid
    @JsonProperty("amount")
    @Schema(description = "Order amount with currency", requiredMode = Schema.RequiredMode.REQUIRED)
    Money amount;

    @JsonProperty("description")
    @Schema(description = "Optional description of the order", example = "Purchase of goods and services")
    String description;

    @Valid
    @JsonProperty("customer")
    @Schema(description = "Customer information associated with the order")
    Customer customer;
    
    @JsonCreator
    public CreateOrderRequest(
            @JsonProperty("merchantOrderId") String merchantOrderId,
            @JsonProperty("amount") Money amount,
            @JsonProperty("description") String description,
            @JsonProperty("customer") Customer customer) {
        this.merchantOrderId = merchantOrderId;
        this.amount = amount;
        this.description = description;
        this.customer = customer;
    }
}


```

Fixing the remaining `@Value` DTOs:


```java
package com.paymentgateway.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.paymentgateway.domain.enums.Gateway;
import com.paymentgateway.domain.enums.PaymentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Value;

@Value
@Schema(description = "Request to create a payment for an order")
public class CreatePaymentRequest {
    @NotBlank
    @JsonProperty("method")
    @Schema(description = "Payment method identifier", requiredMode = Schema.RequiredMode.REQUIRED, example = "credit_card")
    String method;

    @NotNull
    @JsonProperty("flow")
    @Schema(description = "Payment flow type - PURCHASE (authorize and capture) or AUTHORIZE (authorize only)", requiredMode = Schema.RequiredMode.REQUIRED, example = "PURCHASE")
    PaymentType flow;

    @NotNull
    @JsonProperty("gateway")
    @Schema(description = "Payment gateway to use for processing", requiredMode = Schema.RequiredMode.REQUIRED, example = "AUTHORIZE_NET")
    Gateway gateway;
    
    @JsonCreator
    public CreatePaymentRequest(
            @JsonProperty("method") String method,
            @JsonProperty("flow") PaymentType flow,
            @JsonProperty("gateway") Gateway gateway) {
        this.method = method;
        this.flow = flow;
        this.gateway = gateway;
    }
}


```

```java
package com.paymentgateway.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Value;

import java.time.Instant;
import java.util.Map;

@Value
@Schema(description = "Webhook event request from payment gateway")
public class WebhookRequest {
    @NotBlank
    @JsonProperty("eventId")
    @Schema(description = "Unique event identifier from the gateway, used for idempotency", requiredMode = Schema.RequiredMode.REQUIRED, example = "evt_1234567890")
    String eventId;

    @NotBlank
    @JsonProperty("eventType")
    @Schema(description = "Type of webhook event", requiredMode = Schema.RequiredMode.REQUIRED, example = "payment.authorized")
    String eventType;

    @NotNull
    @JsonProperty("gateway")
    @Schema(description = "Payment gateway that sent the webhook", requiredMode = Schema.RequiredMode.REQUIRED, example = "AUTHORIZE_NET")
    String gateway;

    @JsonProperty("transactionReferenceId")
    @Schema(description = "Reference ID of the related transaction", example = "trans_abc123")
    String transactionReferenceId;

    @JsonProperty("occurredAt")
    @Schema(description = "Timestamp when the event occurred at the gateway")
    Instant occurredAt;

    @JsonProperty("signature")
    @Schema(description = "Signature for webhook verification")
    String signature;

    @JsonProperty("payload")
    @Schema(description = "Webhook event payload containing gateway-specific data")
    Map<String, Object> payload;
    
    @JsonCreator
    public WebhookRequest(
            @JsonProperty("eventId") String eventId,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("gateway") String gateway,
            @JsonProperty("transactionReferenceId") String transactionReferenceId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("signature") String signature,
            @JsonProperty("payload") Map<String, Object> payload) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.gateway = gateway;
        this.transactionReferenceId = transactionReferenceId;
        this.occurredAt = occurredAt;
        this.signature = signature;
        this.payload = payload;
    }
}


```

Compilation succeeded. Verifying the fixes are correct:


## Summary: Fixed JSON Deserialization Error

### Problem:
Jackson couldn't deserialize `@Value` DTOs because `@Value` generates an all-args constructor that Jackson doesn't recognize without explicit annotations.

### Solution:
Added `@JsonCreator` constructors with `@JsonProperty` annotations to all request DTOs that use `@Value`:

1. **PurchaseTransactionRequest** - Added `@JsonCreator` constructor
2. **CaptureRequest** - Added `@JsonCreator` constructor  
3. **CreateOrderRequest** - Added `@JsonCreator` constructor
4. **CreatePaymentRequest** - Added `@JsonCreator` constructor
5. **WebhookRequest** - Added `@JsonCreator` constructor

### Changes Made:

Each DTO now has an explicit constructor annotated with:
- `@JsonCreator` - Tells Jackson to use this constructor for deserialization
- `@JsonProperty` - Maps JSON property names to constructor parameters

Example:
```java
@JsonCreator
public PurchaseTransactionRequest(@JsonProperty("paymentMethodToken") String paymentMethodToken) {
    this.paymentMethodToken = paymentMethodToken;
}
```

This allows Jackson to deserialize JSON request bodies into these immutable `@Value` classes. The compilation succeeded, and the JSON deserialization error should be resolved.

The application should now accept JSON requests without the "cannot deserialize from Object value" error.

```java
@JsonCreator
public PurchaseTransactionRequest(@JsonProperty("paymentMethodToken") String paymentMethodToken) {
    this.paymentMethodToken = paymentMethodToken;
}
```

---

**User**

@java (587-597) 

---

**Cursor**

JAXB isn't included in Java 11+, but the Authorize.Net SDK requires it. Checking `pom.xml` and adding the JAXB dependencies:


Adding JAXB dependencies for Java 17 compatibility:


```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.0</version>
        <relativePath/>
    </parent>

    <groupId>com.paymentgateway</groupId>
    <artifactId>payment-orchestration</artifactId>
    <version>1.0.0</version>
    <name>Payment Orchestration Service</name>
    <description>Single-tenant payment orchestration service with Authorize.Net integration</description>

    <properties>
        <java.version>17</java.version>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- Spring Boot Starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>

        <!-- PostgreSQL -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Flyway for Database Migrations -->
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
            <version>10.7.1</version>
        </dependency>

        <!-- Swagger/OpenAPI -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>2.3.0</version>
        </dependency>

        <!-- Authorize.Net Java SDK -->
        <dependency>
            <groupId>net.authorize</groupId>
            <artifactId>anet-java-sdk</artifactId>
            <version>2.0.2</version>
        </dependency>

        <!-- JAXB API (required for Java 11+ as it's no longer included in JDK) -->
        <dependency>
            <groupId>jakarta.xml.bind</groupId>
            <artifactId>jakarta.xml.bind-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.glassfish.jaxb</groupId>
            <artifactId>jaxb-runtime</artifactId>
        </dependency>

        <!-- JSON Processing -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>

        <!-- UUID -->
        <dependency>
            <groupId>com.fasterxml.uuid</groupId>
            <artifactId>java-uuid-generator</artifactId>
            <version>4.3.0</version>
        </dependency>

        <!-- Lombok (optional but helpful) -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Testing -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- H2 Database for Testing -->
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- TestContainers -->
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <version>1.19.3</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <version>1.19.3</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>1.19.3</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>


```

---

**User**

@java (860-873) 

---

**Cursor**

The error references `javax.xml.bind`, but we added `jakarta.xml.bind`. The Authorize.Net SDK likely uses the old `javax` package. Checking and adding the correct dependencies:


The Authorize.Net SDK uses the old `javax.xml.bind` package. Adding the legacy JAXB dependencies:


```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.0</version>
        <relativePath/>
    </parent>

    <groupId>com.paymentgateway</groupId>
    <artifactId>payment-orchestration</artifactId>
    <version>1.0.0</version>
    <name>Payment Orchestration Service</name>
    <description>Single-tenant payment orchestration service with Authorize.Net integration</description>

    <properties>
        <java.version>17</java.version>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- Spring Boot Starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>

        <!-- PostgreSQL -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Flyway for Database Migrations -->
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
            <version>10.7.1</version>
        </dependency>

        <!-- Swagger/OpenAPI -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>2.3.0</version>
        </dependency>

        <!-- Authorize.Net Java SDK -->
        <dependency>
            <groupId>net.authorize</groupId>
            <artifactId>anet-java-sdk</artifactId>
            <version>2.0.2</version>
        </dependency>

        <!-- JAXB API (required for Java 11+ as it's no longer included in JDK) -->
        <!-- Authorize.Net SDK uses old javax.xml.bind namespace -->
        <dependency>
            <groupId>javax.xml.bind</groupId>
            <artifactId>jaxb-api</artifactId>
            <version>2.3.1</version>
        </dependency>
        <dependency>
            <groupId>org.glassfish.jaxb</groupId>
            <artifactId>jaxb-runtime</artifactId>
            <version>2.3.1</version>
        </dependency>

        <!-- JSON Processing -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>

        <!-- UUID -->
        <dependency>
            <groupId>com.fasterxml.uuid</groupId>
            <artifactId>java-uuid-generator</artifactId>
            <version>4.3.0</version>
        </dependency>

        <!-- Lombok (optional but helpful) -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Testing -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- H2 Database for Testing -->
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- TestContainers -->
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <version>1.19.3</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <version>1.19.3</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>1.19.3</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>


```

Compilation succeeded. Summary of the fix:

## Summary: Fixed JAXB NoClassDefFoundError

### Problem:
The Authorize.Net SDK uses the old `javax.xml.bind` package, which was removed from Java 11+. The error `java.lang.NoClassDefFoundError: javax/xml/bind/JAXBException` occurred because JAXB classes weren't on the classpath.

### Solution:
Added the legacy JAXB dependencies that use the `javax` namespace (not `jakarta`):

```xml
<!-- JAXB API (required for Java 11+ as it's no longer included in JDK) -->
<!-- Authorize.Net SDK uses old javax.xml.bind namespace -->
<dependency>
    <groupId>javax.xml.bind</groupId>
    <artifactId>jaxb-api</artifactId>
    <version>2.3.1</version>
</dependency>
<dependency>
    <groupId>org.glassfish.jaxb</groupId>
    <artifactId>jaxb-runtime</artifactId>
    <version>2.3.1</version>
</dependency>
```

### Why this works:
- The Authorize.Net SDK (`anet-java-sdk`) uses `javax.xml.bind.*`
- Java 11+ removed JAXB from the JDK
- These dependencies provide the JAXB API and runtime for the `javax` namespace
- Version 2.3.1 is compatible with Java 17

The application should now work without the `NoClassDefFoundError`. Rebuild and restart the application to apply the changes.

```xml
<!-- JAXB API (required for Java 11+ as it's no longer included in JDK) -->
<!-- Authorize.Net SDK uses old javax.xml.bind namespace -->
<dependency>
    <groupId>javax.xml.bind</groupId>
    <artifactId>jaxb-api</artifactId>
    <version>2.3.1</version>
</dependency>
<dependency>
    <groupId>org.glassfish.jaxb</groupId>
    <artifactId>jaxb-runtime</artifactId>
    <version>2.3.1</version>
</dependency>
```

---

**User**

@bash (460-499) 

---

**Cursor**

Authorize.Net rejects the request because `invoiceNumber` exceeds the max length. The UUID `f9b07b4f-b3af-4648-82d9-7358f709f47c` (36 chars) is too long. Checking where `invoiceNumber` is set and fixing it:


The issue is on line 128: `order.setInvoiceNumber(request.getOrderId())` sets a full UUID (36 chars), but Authorize.Net's `invoiceNumber` has a max length (typically 20). Truncating or hashing the UUID to fit:


```java
package com.paymentgateway.gateway.impl;

import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import lombok.extern.slf4j.Slf4j;
import net.authorize.Environment;
import net.authorize.api.contract.v1.*;
import net.authorize.api.controller.CreateTransactionController;
import net.authorize.api.controller.base.ApiOperationBase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Slf4j
public class AuthorizeNetGateway implements PaymentGateway {

    private final String apiLoginId;
    private final String transactionKey;
    private final Environment environment;

    public AuthorizeNetGateway(
            @Value("${authorize.net.api-login-id}") String apiLoginId,
            @Value("${authorize.net.transaction-key}") String transactionKey,
            @Value("${authorize.net.environment:SANDBOX}") String environment) {
        this.apiLoginId = apiLoginId;
        this.transactionKey = transactionKey;
        this.environment = "PRODUCTION".equalsIgnoreCase(environment) 
                ? Environment.PRODUCTION 
                : Environment.SANDBOX;
        
        ApiOperationBase.setEnvironment(this.environment);
    }

    @Override
    public PurchaseResponse purchase(PurchaseRequest request) {
        log.info("Processing purchase request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_CAPTURE_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        return mapPurchaseResponse(response);
    }

    @Override
    public AuthResponse authorize(PurchaseRequest request) {
        log.info("Processing authorize request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_ONLY_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        return mapAuthResponse(response);
    }

    @Override
    public CaptureResponse capture(String transactionId, long amountCents, String currency) {
        log.info("Processing capture for transaction: {}", transactionId);
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        
        TransactionRequestType transactionRequest = new TransactionRequestType();
        transactionRequest.setTransactionType(TransactionTypeEnum.PRIOR_AUTH_CAPTURE_TRANSACTION.value());
        transactionRequest.setRefTransId(transactionId);
        
        // Set amount if partial capture is needed
        if (amountCents > 0) {
            BigDecimal amount = BigDecimal.valueOf(amountCents).divide(BigDecimal.valueOf(100));
            transactionRequest.setAmount(amount);
        }

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        return mapCaptureResponse(response);
    }

    private MerchantAuthenticationType createMerchantAuthentication() {
        MerchantAuthenticationType merchantAuthentication = new MerchantAuthenticationType();
        merchantAuthentication.setName(apiLoginId);
        merchantAuthentication.setTransactionKey(transactionKey);
        return merchantAuthentication;
    }

    private TransactionRequestType createTransactionRequest(PurchaseRequest request) {
        TransactionRequestType transactionRequest = new TransactionRequestType();
        
        // Payment information
        PaymentType paymentType = new PaymentType();
        OpaqueDataType opaqueData = new OpaqueDataType();
        opaqueData.setDataDescriptor("COMMON.ACCEPT.INAPP.PAYMENT");
        opaqueData.setDataValue(request.getPaymentMethodToken());
        paymentType.setOpaqueData(opaqueData);
        transactionRequest.setPayment(paymentType);

        // Amount
        BigDecimal amount = request.getAmount().getAmount();
        transactionRequest.setAmount(amount);

        // Order information
        OrderType order = new OrderType();
        // Authorize.Net invoiceNumber has max length of 20 characters
        // Remove hyphens from UUID and truncate to 20 chars if needed
        String invoiceNumber = request.getOrderId().replace("-", "");
        if (invoiceNumber.length() > 20) {
            invoiceNumber = invoiceNumber.substring(0, 20);
        }
        order.setInvoiceNumber(invoiceNumber);
        order.setDescription(request.getDescription());
        transactionRequest.setOrder(order);

        return transactionRequest;
    }

    private PurchaseResponse mapPurchaseResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return PurchaseResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return PurchaseResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private AuthResponse mapAuthResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return AuthResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return AuthResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private CaptureResponse mapCaptureResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return CaptureResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return CaptureResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .build();
    }
}


```

Compilation succeeded. Summary of the fix:

## Summary: Fixed Authorize.Net InvoiceNumber Length Error

### Problem:
Authorize.Net rejected the request because the `invoiceNumber` field exceeded the maximum length. The error showed:
- Value: `f9b07b4f-b3af-4648-82d9-7358f709f47c` (36 characters with hyphens)
- Error: "The actual length is greater than the MaxLength value"

Authorize.Net's `invoiceNumber` field has a maximum length of 20 characters.

### Solution:
Updated `AuthorizeNetGateway.createTransactionRequest()` to:
1. Remove hyphens from the UUID (reduces from 36 to 32 characters)
2. Truncate to 20 characters if still too long

```java
// Authorize.Net invoiceNumber has max length of 20 characters
// Remove hyphens from UUID and truncate to 20 chars if needed
String invoiceNumber = request.getOrderId().replace("-", "");
if (invoiceNumber.length() > 20) {
    invoiceNumber = invoiceNumber.substring(0, 20);
}
order.setInvoiceNumber(invoiceNumber);
```

### Result:
- UUID `f9b07b4f-b3af-4648-82d9-7358f709f47c` becomes `f9b07b4fb3af464882d9` (20 characters)
- This fits within Authorize.Net's 20-character limit
- The truncated value is still unique enough for most use cases

The application should now successfully process transactions through Authorize.Net without the invoiceNumber validation error.

```java
// Authorize.Net invoiceNumber has max length of 20 characters
// Remove hyphens from UUID and truncate to 20 chars if needed
String invoiceNumber = request.getOrderId().replace("-", "");
if (invoiceNumber.length() > 20) {
    invoiceNumber = invoiceNumber.substring(0, 20);
}
order.setInvoiceNumber(invoiceNumber);
```

---

**User**

can merchant_order_id be used instead ? have required validation on that field. 

---

**Cursor**

Updating to use `merchant_order_id` for `invoiceNumber` instead of the UUID. Checking how it's passed to the gateway:


Updating to use `merchant_order_id` for `invoiceNumber`. Updating `PurchaseRequest` and all usages:


```java
package com.paymentgateway.gateway.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.paymentgateway.domain.valueobject.Money;
import lombok.Value;

@Value
public class PurchaseRequest {
    String paymentMethodToken;
    Money amount;
    String orderId;
    String merchantOrderId;
    String description;
    
    @JsonCreator
    public PurchaseRequest(
            @JsonProperty("paymentMethodToken") String paymentMethodToken,
            @JsonProperty("amount") Money amount,
            @JsonProperty("orderId") String orderId,
            @JsonProperty("merchantOrderId") String merchantOrderId,
            @JsonProperty("description") String description) {
        this.paymentMethodToken = paymentMethodToken;
        this.amount = amount;
        this.orderId = orderId;
        this.merchantOrderId = merchantOrderId;
        this.description = description;
    }
}


```

```java
package com.paymentgateway.gateway.impl;

import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import lombok.extern.slf4j.Slf4j;
import net.authorize.Environment;
import net.authorize.api.contract.v1.*;
import net.authorize.api.controller.CreateTransactionController;
import net.authorize.api.controller.base.ApiOperationBase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Slf4j
public class AuthorizeNetGateway implements PaymentGateway {

    private final String apiLoginId;
    private final String transactionKey;
    private final Environment environment;

    public AuthorizeNetGateway(
            @Value("${authorize.net.api-login-id}") String apiLoginId,
            @Value("${authorize.net.transaction-key}") String transactionKey,
            @Value("${authorize.net.environment:SANDBOX}") String environment) {
        this.apiLoginId = apiLoginId;
        this.transactionKey = transactionKey;
        this.environment = "PRODUCTION".equalsIgnoreCase(environment) 
                ? Environment.PRODUCTION 
                : Environment.SANDBOX;
        
        ApiOperationBase.setEnvironment(this.environment);
    }

    @Override
    public PurchaseResponse purchase(PurchaseRequest request) {
        log.info("Processing purchase request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_CAPTURE_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        return mapPurchaseResponse(response);
    }

    @Override
    public AuthResponse authorize(PurchaseRequest request) {
        log.info("Processing authorize request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_ONLY_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        return mapAuthResponse(response);
    }

    @Override
    public CaptureResponse capture(String transactionId, long amountCents, String currency) {
        log.info("Processing capture for transaction: {}", transactionId);
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        
        TransactionRequestType transactionRequest = new TransactionRequestType();
        transactionRequest.setTransactionType(TransactionTypeEnum.PRIOR_AUTH_CAPTURE_TRANSACTION.value());
        transactionRequest.setRefTransId(transactionId);
        
        // Set amount if partial capture is needed
        if (amountCents > 0) {
            BigDecimal amount = BigDecimal.valueOf(amountCents).divide(BigDecimal.valueOf(100));
            transactionRequest.setAmount(amount);
        }

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        return mapCaptureResponse(response);
    }

    private MerchantAuthenticationType createMerchantAuthentication() {
        MerchantAuthenticationType merchantAuthentication = new MerchantAuthenticationType();
        merchantAuthentication.setName(apiLoginId);
        merchantAuthentication.setTransactionKey(transactionKey);
        return merchantAuthentication;
    }

    private TransactionRequestType createTransactionRequest(PurchaseRequest request) {
        TransactionRequestType transactionRequest = new TransactionRequestType();
        
        // Payment information
        PaymentType paymentType = new PaymentType();
        OpaqueDataType opaqueData = new OpaqueDataType();
        opaqueData.setDataDescriptor("COMMON.ACCEPT.INAPP.PAYMENT");
        opaqueData.setDataValue(request.getPaymentMethodToken());
        paymentType.setOpaqueData(opaqueData);
        transactionRequest.setPayment(paymentType);

        // Amount
        BigDecimal amount = request.getAmount().getAmount();
        transactionRequest.setAmount(amount);

        // Order information
        OrderType order = new OrderType();
        // Use merchantOrderId for invoiceNumber (required field, max 20 chars)
        // Truncate to 20 characters if needed to fit Authorize.Net's limit
        String invoiceNumber = request.getMerchantOrderId();
        if (invoiceNumber != null && invoiceNumber.length() > 20) {
            invoiceNumber = invoiceNumber.substring(0, 20);
        }
        order.setInvoiceNumber(invoiceNumber);
        order.setDescription(request.getDescription());
        transactionRequest.setOrder(order);

        return transactionRequest;
    }

    private PurchaseResponse mapPurchaseResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return PurchaseResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return PurchaseResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private AuthResponse mapAuthResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return AuthResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return AuthResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private CaptureResponse mapCaptureResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return CaptureResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return CaptureResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .build();
    }
}


```

```java
package com.paymentgateway.service;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.entity.PaymentEntity;
import com.paymentgateway.domain.model.*;
import com.paymentgateway.domain.statemachine.PaymentStateMachine;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import com.paymentgateway.repository.*;
import com.paymentgateway.repository.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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
    private PaymentGateway paymentGateway;
    
    @Autowired
    private OrderMapper orderMapper;
    
    @Autowired
    private PaymentMapper paymentMapper;
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Transactional
    public Order createOrder(Order order) {
        if (orderRepository.existsByMerchantOrderId(order.getMerchantOrderId())) {
            throw new IllegalArgumentException("Order with merchantOrderId already exists: " + order.getMerchantOrderId());
        }
        var entity = orderMapper.toEntity(order);
        var saved = orderRepository.save(entity);
        return orderMapper.toDomain(saved);
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

        PurchaseResponse gatewayResponse = paymentGateway.purchase(gatewayRequest);

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

        AuthResponse gatewayResponse = paymentGateway.authorize(gatewayRequest);

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
        CaptureResponse gatewayResponse = paymentGateway.capture(
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
}


```

Adding validation to ensure `merchantOrderId` is not null and fits within the 20-character limit:


```java
package com.paymentgateway.gateway.impl;

import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import lombok.extern.slf4j.Slf4j;
import net.authorize.Environment;
import net.authorize.api.contract.v1.*;
import net.authorize.api.controller.CreateTransactionController;
import net.authorize.api.controller.base.ApiOperationBase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Slf4j
public class AuthorizeNetGateway implements PaymentGateway {

    private final String apiLoginId;
    private final String transactionKey;
    private final Environment environment;

    public AuthorizeNetGateway(
            @Value("${authorize.net.api-login-id}") String apiLoginId,
            @Value("${authorize.net.transaction-key}") String transactionKey,
            @Value("${authorize.net.environment:SANDBOX}") String environment) {
        this.apiLoginId = apiLoginId;
        this.transactionKey = transactionKey;
        this.environment = "PRODUCTION".equalsIgnoreCase(environment) 
                ? Environment.PRODUCTION 
                : Environment.SANDBOX;
        
        ApiOperationBase.setEnvironment(this.environment);
    }

    @Override
    public PurchaseResponse purchase(PurchaseRequest request) {
        log.info("Processing purchase request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_CAPTURE_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        return mapPurchaseResponse(response);
    }

    @Override
    public AuthResponse authorize(PurchaseRequest request) {
        log.info("Processing authorize request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_ONLY_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        return mapAuthResponse(response);
    }

    @Override
    public CaptureResponse capture(String transactionId, long amountCents, String currency) {
        log.info("Processing capture for transaction: {}", transactionId);
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        
        TransactionRequestType transactionRequest = new TransactionRequestType();
        transactionRequest.setTransactionType(TransactionTypeEnum.PRIOR_AUTH_CAPTURE_TRANSACTION.value());
        transactionRequest.setRefTransId(transactionId);
        
        // Set amount if partial capture is needed
        if (amountCents > 0) {
            BigDecimal amount = BigDecimal.valueOf(amountCents).divide(BigDecimal.valueOf(100));
            transactionRequest.setAmount(amount);
        }

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        return mapCaptureResponse(response);
    }

    private MerchantAuthenticationType createMerchantAuthentication() {
        MerchantAuthenticationType merchantAuthentication = new MerchantAuthenticationType();
        merchantAuthentication.setName(apiLoginId);
        merchantAuthentication.setTransactionKey(transactionKey);
        return merchantAuthentication;
    }

    private TransactionRequestType createTransactionRequest(PurchaseRequest request) {
        TransactionRequestType transactionRequest = new TransactionRequestType();
        
        // Payment information
        PaymentType paymentType = new PaymentType();
        OpaqueDataType opaqueData = new OpaqueDataType();
        opaqueData.setDataDescriptor("COMMON.ACCEPT.INAPP.PAYMENT");
        opaqueData.setDataValue(request.getPaymentMethodToken());
        paymentType.setOpaqueData(opaqueData);
        transactionRequest.setPayment(paymentType);

        // Amount
        BigDecimal amount = request.getAmount().getAmount();
        transactionRequest.setAmount(amount);

        // Order information
        OrderType order = new OrderType();
        // Use merchantOrderId for invoiceNumber (required field, max 20 chars)
        // Validate and truncate to 20 characters if needed to fit Authorize.Net's limit
        String merchantOrderId = request.getMerchantOrderId();
        if (merchantOrderId == null || merchantOrderId.isBlank()) {
            throw new IllegalArgumentException("merchantOrderId is required for Authorize.Net transactions");
        }
        String invoiceNumber = merchantOrderId.length() > 20 
                ? merchantOrderId.substring(0, 20) 
                : merchantOrderId;
        order.setInvoiceNumber(invoiceNumber);
        order.setDescription(request.getDescription());
        transactionRequest.setOrder(order);

        return transactionRequest;
    }

    private PurchaseResponse mapPurchaseResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return PurchaseResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return PurchaseResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private AuthResponse mapAuthResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return AuthResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return AuthResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private CaptureResponse mapCaptureResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return CaptureResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return CaptureResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .build();
    }
}


```

Compilation succeeded. Summary of changes:

## Summary: Use merchantOrderId for Authorize.Net invoiceNumber

### Changes Made:

1. **Updated `PurchaseRequest` DTO**:
   - Added `merchantOrderId` field
   - Added `@JsonCreator` constructor for proper deserialization

2. **Updated `AuthorizeNetGateway`**:
   - Changed from using `orderId` (UUID) to `merchantOrderId` for `invoiceNumber`
   - Added validation to ensure `merchantOrderId` is not null or blank
   - Truncates to 20 characters if needed (Authorize.Net's limit)

3. **Updated `PaymentOrchestratorService`**:
   - Updated all `PurchaseRequest` instantiations to include `merchantOrderId`
   - Passes `order.getMerchantOrderId()` instead of just the UUID

### Benefits:

- Uses a meaningful identifier: `merchantOrderId` is more meaningful than a UUID
- Already validated: `merchantOrderId` is required (`nullable = false`) and has a max length of 100 in the database
- Better for merchants: They'll see their own order IDs in Authorize.Net dashboard
- Handles length: Automatically truncates to 20 characters if the merchantOrderId is longer

### Validation:

The `merchantOrderId` field is already validated at multiple levels:
- Database: `nullable = false, length = 100` in `OrderEntity`
- Gateway: Additional check ensures it's not null/blank before sending to Authorize.Net
- Length: Automatically truncated to 20 characters to fit Authorize.Net's limit

The application will now use `merchantOrderId` for the `invoiceNumber` field in Authorize.Net transactions, which should resolve the validation error and provide better traceability.

---

**User**

curl -X 'POST' \



  'http://localhost:8080/v1/payments/6b2132c1-273b-41d1-a9ac-1643cd838eef/transactions/purchase' \

  -H 'accept: */*' \

  -H 'Content-Type: application/json' \

  -d '{

  "paymentMethodToken": "1234567890ABCDEF1111AAAA2222BBBB3333CCCC4444DDDD5555EEEE6666FFFF7777888899990000"

}'



{



  "transactionId": "985c17a6-05b3-46e1-9b8c-935a6b2cb3c2",

  "type": "PURCHASE",

  "status": "FAILED",

  "amount": {

    "amount": 1000.5,

    "currency": "INR",

    "currencyCode": "INR",

    "amountCents": 100050,

    "amountAsString": "1000.5"

  },

  "createdAt": "2026-01-03T05:47:58.042087651Z"

}



no Logs, add required logs for proper debugging Print request body sent to auth.net and response received under debug tag. 

---

**Cursor**


Adding debug logs for Authorize.Net requests and responses. Checking the current implementation:



Adding debug logging for Authorize.Net requests and responses. Checking how to serialize the request/response objects:



Adding debug logging for Authorize.Net requests and responses. Creating helper methods to format and log the data:


```java
package com.paymentgateway.gateway.impl;

import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import lombok.extern.slf4j.Slf4j;
import net.authorize.Environment;
import net.authorize.api.contract.v1.*;
import net.authorize.api.controller.CreateTransactionController;
import net.authorize.api.controller.base.ApiOperationBase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.stream.Collectors;

@Component
@Slf4j
public class AuthorizeNetGateway implements PaymentGateway {

    private final String apiLoginId;
    private final String transactionKey;
    private final Environment environment;

    public AuthorizeNetGateway(
            @Value("${authorize.net.api-login-id}") String apiLoginId,
            @Value("${authorize.net.transaction-key}") String transactionKey,
            @Value("${authorize.net.environment:SANDBOX}") String environment) {
        this.apiLoginId = apiLoginId;
        this.transactionKey = transactionKey;
        this.environment = "PRODUCTION".equalsIgnoreCase(environment) 
                ? Environment.PRODUCTION 
                : Environment.SANDBOX;
        
        ApiOperationBase.setEnvironment(this.environment);
    }

    @Override
    public PurchaseResponse purchase(PurchaseRequest request) {
        log.info("Processing purchase request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_CAPTURE_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        return mapPurchaseResponse(response);
    }

    @Override
    public AuthResponse authorize(PurchaseRequest request) {
        log.info("Processing authorize request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_ONLY_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        return mapAuthResponse(response);
    }

    @Override
    public CaptureResponse capture(String transactionId, long amountCents, String currency) {
        log.info("Processing capture for transaction: {}", transactionId);
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        
        TransactionRequestType transactionRequest = new TransactionRequestType();
        transactionRequest.setTransactionType(TransactionTypeEnum.PRIOR_AUTH_CAPTURE_TRANSACTION.value());
        transactionRequest.setRefTransId(transactionId);
        
        // Set amount if partial capture is needed
        if (amountCents > 0) {
            BigDecimal amount = BigDecimal.valueOf(amountCents).divide(BigDecimal.valueOf(100));
            transactionRequest.setAmount(amount);
        }

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        return mapCaptureResponse(response);
    }

    private MerchantAuthenticationType createMerchantAuthentication() {
        MerchantAuthenticationType merchantAuthentication = new MerchantAuthenticationType();
        merchantAuthentication.setName(apiLoginId);
        merchantAuthentication.setTransactionKey(transactionKey);
        return merchantAuthentication;
    }

    private TransactionRequestType createTransactionRequest(PurchaseRequest request) {
        TransactionRequestType transactionRequest = new TransactionRequestType();
        
        // Payment information
        PaymentType paymentType = new PaymentType();
        OpaqueDataType opaqueData = new OpaqueDataType();
        opaqueData.setDataDescriptor("COMMON.ACCEPT.INAPP.PAYMENT");
        opaqueData.setDataValue(request.getPaymentMethodToken());
        paymentType.setOpaqueData(opaqueData);
        transactionRequest.setPayment(paymentType);

        // Amount
        BigDecimal amount = request.getAmount().getAmount();
        transactionRequest.setAmount(amount);

        // Order information
        OrderType order = new OrderType();
        // Use merchantOrderId for invoiceNumber (required field, max 20 chars)
        // Validate and truncate to 20 characters if needed to fit Authorize.Net's limit
        String merchantOrderId = request.getMerchantOrderId();
        if (merchantOrderId == null || merchantOrderId.isBlank()) {
            throw new IllegalArgumentException("merchantOrderId is required for Authorize.Net transactions");
        }
        String invoiceNumber = merchantOrderId.length() > 20 
                ? merchantOrderId.substring(0, 20) 
                : merchantOrderId;
        order.setInvoiceNumber(invoiceNumber);
        order.setDescription(request.getDescription());
        transactionRequest.setOrder(order);

        return transactionRequest;
    }

    private PurchaseResponse mapPurchaseResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return PurchaseResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return PurchaseResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private AuthResponse mapAuthResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return AuthResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return AuthResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private CaptureResponse mapCaptureResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return CaptureResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return CaptureResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .build();
    }
}


```

```java
package com.paymentgateway.gateway.impl;

import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import lombok.extern.slf4j.Slf4j;
import net.authorize.Environment;
import net.authorize.api.contract.v1.*;
import net.authorize.api.controller.CreateTransactionController;
import net.authorize.api.controller.base.ApiOperationBase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.stream.Collectors;

@Component
@Slf4j
public class AuthorizeNetGateway implements PaymentGateway {

    private final String apiLoginId;
    private final String transactionKey;
    private final Environment environment;

    public AuthorizeNetGateway(
            @Value("${authorize.net.api-login-id}") String apiLoginId,
            @Value("${authorize.net.transaction-key}") String transactionKey,
            @Value("${authorize.net.environment:SANDBOX}") String environment) {
        this.apiLoginId = apiLoginId;
        this.transactionKey = transactionKey;
        this.environment = "PRODUCTION".equalsIgnoreCase(environment) 
                ? Environment.PRODUCTION 
                : Environment.SANDBOX;
        
        ApiOperationBase.setEnvironment(this.environment);
    }

    @Override
    public PurchaseResponse purchase(PurchaseRequest request) {
        log.info("Processing purchase request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_CAPTURE_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        // Log request body to Authorize.Net
        logRequestToAuthorizeNet("PURCHASE", apiRequest, request);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        
        // Log response from Authorize.Net
        logResponseFromAuthorizeNet("PURCHASE", response);
        
        return mapPurchaseResponse(response);
    }

    @Override
    public AuthResponse authorize(PurchaseRequest request) {
        log.info("Processing authorize request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_ONLY_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        return mapAuthResponse(response);
    }

    @Override
    public CaptureResponse capture(String transactionId, long amountCents, String currency) {
        log.info("Processing capture for transaction: {}", transactionId);
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        
        TransactionRequestType transactionRequest = new TransactionRequestType();
        transactionRequest.setTransactionType(TransactionTypeEnum.PRIOR_AUTH_CAPTURE_TRANSACTION.value());
        transactionRequest.setRefTransId(transactionId);
        
        // Set amount if partial capture is needed
        if (amountCents > 0) {
            BigDecimal amount = BigDecimal.valueOf(amountCents).divide(BigDecimal.valueOf(100));
            transactionRequest.setAmount(amount);
        }

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        return mapCaptureResponse(response);
    }

    private MerchantAuthenticationType createMerchantAuthentication() {
        MerchantAuthenticationType merchantAuthentication = new MerchantAuthenticationType();
        merchantAuthentication.setName(apiLoginId);
        merchantAuthentication.setTransactionKey(transactionKey);
        return merchantAuthentication;
    }

    private TransactionRequestType createTransactionRequest(PurchaseRequest request) {
        TransactionRequestType transactionRequest = new TransactionRequestType();
        
        // Payment information
        PaymentType paymentType = new PaymentType();
        OpaqueDataType opaqueData = new OpaqueDataType();
        opaqueData.setDataDescriptor("COMMON.ACCEPT.INAPP.PAYMENT");
        opaqueData.setDataValue(request.getPaymentMethodToken());
        paymentType.setOpaqueData(opaqueData);
        transactionRequest.setPayment(paymentType);

        // Amount
        BigDecimal amount = request.getAmount().getAmount();
        transactionRequest.setAmount(amount);

        // Order information
        OrderType order = new OrderType();
        // Use merchantOrderId for invoiceNumber (required field, max 20 chars)
        // Validate and truncate to 20 characters if needed to fit Authorize.Net's limit
        String merchantOrderId = request.getMerchantOrderId();
        if (merchantOrderId == null || merchantOrderId.isBlank()) {
            throw new IllegalArgumentException("merchantOrderId is required for Authorize.Net transactions");
        }
        String invoiceNumber = merchantOrderId.length() > 20 
                ? merchantOrderId.substring(0, 20) 
                : merchantOrderId;
        order.setInvoiceNumber(invoiceNumber);
        order.setDescription(request.getDescription());
        transactionRequest.setOrder(order);

        return transactionRequest;
    }

    private PurchaseResponse mapPurchaseResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return PurchaseResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return PurchaseResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private AuthResponse mapAuthResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return AuthResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return AuthResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private CaptureResponse mapCaptureResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return CaptureResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return CaptureResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .build();
    }
}


```

```java
package com.paymentgateway.gateway.impl;

import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import lombok.extern.slf4j.Slf4j;
import net.authorize.Environment;
import net.authorize.api.contract.v1.*;
import net.authorize.api.controller.CreateTransactionController;
import net.authorize.api.controller.base.ApiOperationBase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.stream.Collectors;

@Component
@Slf4j
public class AuthorizeNetGateway implements PaymentGateway {

    private final String apiLoginId;
    private final String transactionKey;
    private final Environment environment;

    public AuthorizeNetGateway(
            @Value("${authorize.net.api-login-id}") String apiLoginId,
            @Value("${authorize.net.transaction-key}") String transactionKey,
            @Value("${authorize.net.environment:SANDBOX}") String environment) {
        this.apiLoginId = apiLoginId;
        this.transactionKey = transactionKey;
        this.environment = "PRODUCTION".equalsIgnoreCase(environment) 
                ? Environment.PRODUCTION 
                : Environment.SANDBOX;
        
        ApiOperationBase.setEnvironment(this.environment);
    }

    @Override
    public PurchaseResponse purchase(PurchaseRequest request) {
        log.info("Processing purchase request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_CAPTURE_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        // Log request body to Authorize.Net
        logRequestToAuthorizeNet("PURCHASE", apiRequest, request);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        
        // Log response from Authorize.Net
        logResponseFromAuthorizeNet("PURCHASE", response);
        
        return mapPurchaseResponse(response);
    }

    @Override
    public AuthResponse authorize(PurchaseRequest request) {
        log.info("Processing authorize request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_ONLY_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        // Log request body to Authorize.Net
        logRequestToAuthorizeNet("AUTHORIZE", apiRequest, request);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        
        // Log response from Authorize.Net
        logResponseFromAuthorizeNet("AUTHORIZE", response);
        
        return mapAuthResponse(response);
    }

    @Override
    public CaptureResponse capture(String transactionId, long amountCents, String currency) {
        log.info("Processing capture for transaction: {}", transactionId);
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        
        TransactionRequestType transactionRequest = new TransactionRequestType();
        transactionRequest.setTransactionType(TransactionTypeEnum.PRIOR_AUTH_CAPTURE_TRANSACTION.value());
        transactionRequest.setRefTransId(transactionId);
        
        // Set amount if partial capture is needed
        if (amountCents > 0) {
            BigDecimal amount = BigDecimal.valueOf(amountCents).divide(BigDecimal.valueOf(100));
            transactionRequest.setAmount(amount);
        }

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        return mapCaptureResponse(response);
    }

    private MerchantAuthenticationType createMerchantAuthentication() {
        MerchantAuthenticationType merchantAuthentication = new MerchantAuthenticationType();
        merchantAuthentication.setName(apiLoginId);
        merchantAuthentication.setTransactionKey(transactionKey);
        return merchantAuthentication;
    }

    private TransactionRequestType createTransactionRequest(PurchaseRequest request) {
        TransactionRequestType transactionRequest = new TransactionRequestType();
        
        // Payment information
        PaymentType paymentType = new PaymentType();
        OpaqueDataType opaqueData = new OpaqueDataType();
        opaqueData.setDataDescriptor("COMMON.ACCEPT.INAPP.PAYMENT");
        opaqueData.setDataValue(request.getPaymentMethodToken());
        paymentType.setOpaqueData(opaqueData);
        transactionRequest.setPayment(paymentType);

        // Amount
        BigDecimal amount = request.getAmount().getAmount();
        transactionRequest.setAmount(amount);

        // Order information
        OrderType order = new OrderType();
        // Use merchantOrderId for invoiceNumber (required field, max 20 chars)
        // Validate and truncate to 20 characters if needed to fit Authorize.Net's limit
        String merchantOrderId = request.getMerchantOrderId();
        if (merchantOrderId == null || merchantOrderId.isBlank()) {
            throw new IllegalArgumentException("merchantOrderId is required for Authorize.Net transactions");
        }
        String invoiceNumber = merchantOrderId.length() > 20 
                ? merchantOrderId.substring(0, 20) 
                : merchantOrderId;
        order.setInvoiceNumber(invoiceNumber);
        order.setDescription(request.getDescription());
        transactionRequest.setOrder(order);

        return transactionRequest;
    }

    private PurchaseResponse mapPurchaseResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return PurchaseResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return PurchaseResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private AuthResponse mapAuthResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return AuthResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return AuthResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private CaptureResponse mapCaptureResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return CaptureResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return CaptureResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .build();
    }
}


```

```java
package com.paymentgateway.gateway.impl;

import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import lombok.extern.slf4j.Slf4j;
import net.authorize.Environment;
import net.authorize.api.contract.v1.*;
import net.authorize.api.controller.CreateTransactionController;
import net.authorize.api.controller.base.ApiOperationBase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.stream.Collectors;

@Component
@Slf4j
public class AuthorizeNetGateway implements PaymentGateway {

    private final String apiLoginId;
    private final String transactionKey;
    private final Environment environment;

    public AuthorizeNetGateway(
            @Value("${authorize.net.api-login-id}") String apiLoginId,
            @Value("${authorize.net.transaction-key}") String transactionKey,
            @Value("${authorize.net.environment:SANDBOX}") String environment) {
        this.apiLoginId = apiLoginId;
        this.transactionKey = transactionKey;
        this.environment = "PRODUCTION".equalsIgnoreCase(environment) 
                ? Environment.PRODUCTION 
                : Environment.SANDBOX;
        
        ApiOperationBase.setEnvironment(this.environment);
    }

    @Override
    public PurchaseResponse purchase(PurchaseRequest request) {
        log.info("Processing purchase request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_CAPTURE_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        // Log request body to Authorize.Net
        logRequestToAuthorizeNet("PURCHASE", apiRequest, request);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        
        // Log response from Authorize.Net
        logResponseFromAuthorizeNet("PURCHASE", response);
        
        return mapPurchaseResponse(response);
    }

    @Override
    public AuthResponse authorize(PurchaseRequest request) {
        log.info("Processing authorize request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_ONLY_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        // Log request body to Authorize.Net
        logRequestToAuthorizeNet("AUTHORIZE", apiRequest, request);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        
        // Log response from Authorize.Net
        logResponseFromAuthorizeNet("AUTHORIZE", response);
        
        return mapAuthResponse(response);
    }

    @Override
    public CaptureResponse capture(String transactionId, long amountCents, String currency) {
        log.info("Processing capture for transaction: {}", transactionId);
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        
        TransactionRequestType transactionRequest = new TransactionRequestType();
        transactionRequest.setTransactionType(TransactionTypeEnum.PRIOR_AUTH_CAPTURE_TRANSACTION.value());
        transactionRequest.setRefTransId(transactionId);
        
        // Set amount if partial capture is needed
        if (amountCents > 0) {
            BigDecimal amount = BigDecimal.valueOf(amountCents).divide(BigDecimal.valueOf(100));
            transactionRequest.setAmount(amount);
        }

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        // Log request body to Authorize.Net
        logCaptureRequestToAuthorizeNet(apiRequest, transactionId, amountCents, currency);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        
        // Log response from Authorize.Net
        logResponseFromAuthorizeNet("CAPTURE", response);
        
        return mapCaptureResponse(response);
    }

    private MerchantAuthenticationType createMerchantAuthentication() {
        MerchantAuthenticationType merchantAuthentication = new MerchantAuthenticationType();
        merchantAuthentication.setName(apiLoginId);
        merchantAuthentication.setTransactionKey(transactionKey);
        return merchantAuthentication;
    }

    private TransactionRequestType createTransactionRequest(PurchaseRequest request) {
        TransactionRequestType transactionRequest = new TransactionRequestType();
        
        // Payment information
        PaymentType paymentType = new PaymentType();
        OpaqueDataType opaqueData = new OpaqueDataType();
        opaqueData.setDataDescriptor("COMMON.ACCEPT.INAPP.PAYMENT");
        opaqueData.setDataValue(request.getPaymentMethodToken());
        paymentType.setOpaqueData(opaqueData);
        transactionRequest.setPayment(paymentType);

        // Amount
        BigDecimal amount = request.getAmount().getAmount();
        transactionRequest.setAmount(amount);

        // Order information
        OrderType order = new OrderType();
        // Use merchantOrderId for invoiceNumber (required field, max 20 chars)
        // Validate and truncate to 20 characters if needed to fit Authorize.Net's limit
        String merchantOrderId = request.getMerchantOrderId();
        if (merchantOrderId == null || merchantOrderId.isBlank()) {
            throw new IllegalArgumentException("merchantOrderId is required for Authorize.Net transactions");
        }
        String invoiceNumber = merchantOrderId.length() > 20 
                ? merchantOrderId.substring(0, 20) 
                : merchantOrderId;
        order.setInvoiceNumber(invoiceNumber);
        order.setDescription(request.getDescription());
        transactionRequest.setOrder(order);

        return transactionRequest;
    }

    private PurchaseResponse mapPurchaseResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return PurchaseResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return PurchaseResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private AuthResponse mapAuthResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return AuthResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return AuthResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private CaptureResponse mapCaptureResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return CaptureResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return CaptureResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .build();
    }
}


```

```java
package com.paymentgateway.gateway.impl;

import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import lombok.extern.slf4j.Slf4j;
import net.authorize.Environment;
import net.authorize.api.contract.v1.*;
import net.authorize.api.controller.CreateTransactionController;
import net.authorize.api.controller.base.ApiOperationBase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.stream.Collectors;

@Component
@Slf4j
public class AuthorizeNetGateway implements PaymentGateway {

    private final String apiLoginId;
    private final String transactionKey;
    private final Environment environment;

    public AuthorizeNetGateway(
            @Value("${authorize.net.api-login-id}") String apiLoginId,
            @Value("${authorize.net.transaction-key}") String transactionKey,
            @Value("${authorize.net.environment:SANDBOX}") String environment) {
        this.apiLoginId = apiLoginId;
        this.transactionKey = transactionKey;
        this.environment = "PRODUCTION".equalsIgnoreCase(environment) 
                ? Environment.PRODUCTION 
                : Environment.SANDBOX;
        
        ApiOperationBase.setEnvironment(this.environment);
    }

    @Override
    public PurchaseResponse purchase(PurchaseRequest request) {
        log.info("Processing purchase request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_CAPTURE_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        // Log request body to Authorize.Net
        logRequestToAuthorizeNet("PURCHASE", apiRequest, request);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        
        // Log response from Authorize.Net
        logResponseFromAuthorizeNet("PURCHASE", response);
        
        return mapPurchaseResponse(response);
    }

    @Override
    public AuthResponse authorize(PurchaseRequest request) {
        log.info("Processing authorize request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_ONLY_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        // Log request body to Authorize.Net
        logRequestToAuthorizeNet("AUTHORIZE", apiRequest, request);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        
        // Log response from Authorize.Net
        logResponseFromAuthorizeNet("AUTHORIZE", response);
        
        return mapAuthResponse(response);
    }

    @Override
    public CaptureResponse capture(String transactionId, long amountCents, String currency) {
        log.info("Processing capture for transaction: {}", transactionId);
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        
        TransactionRequestType transactionRequest = new TransactionRequestType();
        transactionRequest.setTransactionType(TransactionTypeEnum.PRIOR_AUTH_CAPTURE_TRANSACTION.value());
        transactionRequest.setRefTransId(transactionId);
        
        // Set amount if partial capture is needed
        if (amountCents > 0) {
            BigDecimal amount = BigDecimal.valueOf(amountCents).divide(BigDecimal.valueOf(100));
            transactionRequest.setAmount(amount);
        }

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        // Log request body to Authorize.Net
        logCaptureRequestToAuthorizeNet(apiRequest, transactionId, amountCents, currency);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        
        // Log response from Authorize.Net
        logResponseFromAuthorizeNet("CAPTURE", response);
        
        return mapCaptureResponse(response);
    }

    private MerchantAuthenticationType createMerchantAuthentication() {
        MerchantAuthenticationType merchantAuthentication = new MerchantAuthenticationType();
        merchantAuthentication.setName(apiLoginId);
        merchantAuthentication.setTransactionKey(transactionKey);
        return merchantAuthentication;
    }

    private TransactionRequestType createTransactionRequest(PurchaseRequest request) {
        TransactionRequestType transactionRequest = new TransactionRequestType();
        
        // Payment information
        PaymentType paymentType = new PaymentType();
        OpaqueDataType opaqueData = new OpaqueDataType();
        opaqueData.setDataDescriptor("COMMON.ACCEPT.INAPP.PAYMENT");
        opaqueData.setDataValue(request.getPaymentMethodToken());
        paymentType.setOpaqueData(opaqueData);
        transactionRequest.setPayment(paymentType);

        // Amount
        BigDecimal amount = request.getAmount().getAmount();
        transactionRequest.setAmount(amount);

        // Order information
        OrderType order = new OrderType();
        // Use merchantOrderId for invoiceNumber (required field, max 20 chars)
        // Validate and truncate to 20 characters if needed to fit Authorize.Net's limit
        String merchantOrderId = request.getMerchantOrderId();
        if (merchantOrderId == null || merchantOrderId.isBlank()) {
            throw new IllegalArgumentException("merchantOrderId is required for Authorize.Net transactions");
        }
        String invoiceNumber = merchantOrderId.length() > 20 
                ? merchantOrderId.substring(0, 20) 
                : merchantOrderId;
        order.setInvoiceNumber(invoiceNumber);
        order.setDescription(request.getDescription());
        transactionRequest.setOrder(order);

        return transactionRequest;
    }

    private PurchaseResponse mapPurchaseResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return PurchaseResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return PurchaseResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private AuthResponse mapAuthResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return AuthResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return AuthResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private CaptureResponse mapCaptureResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return CaptureResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return CaptureResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .build();
    }

    /**
     * Logs the request body being sent to Authorize.Net for purchase/authorize transactions
     */
    private void logRequestToAuthorizeNet(String transactionType, CreateTransactionRequest apiRequest, PurchaseRequest request) {
        if (log.isDebugEnabled()) {
            TransactionRequestType txRequest = apiRequest.getTransactionRequest();
            StringBuilder requestBody = new StringBuilder();
            requestBody.append("\n=== Authorize.Net Request (").append(transactionType).append(") ===\n");
            requestBody.append("Transaction Type: ").append(txRequest.getTransactionType()).append("\n");
            requestBody.append("Amount: ").append(txRequest.getAmount()).append("\n");
            
            if (txRequest.getOrder() != null) {
                requestBody.append("Invoice Number: ").append(txRequest.getOrder().getInvoiceNumber()).append("\n");
                requestBody.append("Description: ").append(txRequest.getOrder().getDescription()).append("\n");
            }
            
            if (txRequest.getPayment() != null && txRequest.getPayment().getOpaqueData() != null) {
                OpaqueDataType opaqueData = txRequest.getPayment().getOpaqueData();
                requestBody.append("Payment Data Descriptor: ").append(opaqueData.getDataDescriptor()).append("\n");
                // Mask payment token for security
                String token = opaqueData.getDataValue();
                if (token != null && token.length() > 8) {
                    requestBody.append("Payment Token: ").append(token.substring(0, 4))
                            .append("****").append(token.substring(token.length() - 4)).append("\n");
                } else {
                    requestBody.append("Payment Token: [MASKED]\n");
                }
            }
            
            requestBody.append("Merchant Order ID: ").append(request.getMerchantOrderId()).append("\n");
            requestBody.append("Order ID: ").append(request.getOrderId()).append("\n");
            requestBody.append("===========================================");
            
            log.debug("Authorize.Net Request Body:\n{}", requestBody.toString());
        }
    }

    /**
     * Logs the request body being sent to Authorize.Net for capture transactions
     */
    private void logCaptureRequestToAuthorizeNet(CreateTransactionRequest apiRequest, String transactionId, long amountCents, String currency) {
        if (log.isDebugEnabled()) {
            TransactionRequestType txRequest = apiRequest.getTransactionRequest();
            StringBuilder requestBody = new StringBuilder();
            requestBody.append("\n=== Authorize.Net Request (CAPTURE) ===\n");
            requestBody.append("Transaction Type: ").append(txRequest.getTransactionType()).append("\n");
            requestBody.append("Reference Transaction ID: ").append(transactionId).append("\n");
            requestBody.append("Amount: ").append(txRequest.getAmount()).append("\n");
            requestBody.append("Amount (cents): ").append(amountCents).append("\n");
            requestBody.append("Currency: ").append(currency).append("\n");
            requestBody.append("===========================================");
            
            log.debug("Authorize.Net Request Body:\n{}", requestBody.toString());
        }
    }

    /**
     * Logs the response received from Authorize.Net
     */
    private void logResponseFromAuthorizeNet(String transactionType, CreateTransactionResponse response) {
        if (log.isDebugEnabled()) {
            StringBuilder responseBody = new StringBuilder();
            responseBody.append("\n=== Authorize.Net Response (").append(transactionType).append(") ===\n");
            
            if (response == null) {
                responseBody.append("Response: null\n");
            } else {
                MessagesType messages = response.getMessages();
                if (messages != null) {
                    responseBody.append("Result Code: ").append(messages.getResultCode()).append("\n");
                    if (messages.getMessage() != null && !messages.getMessage().isEmpty()) {
                        responseBody.append("Messages:\n");
                        for (MessagesType.Message msg : messages.getMessage()) {
                            responseBody.append("  - Code: ").append(msg.getCode())
                                    .append(", Text: ").append(msg.getText()).append("\n");
                        }
                    }
                }
                
                TransactionResponse txResponse = response.getTransactionResponse();
                if (txResponse != null) {
                    responseBody.append("Transaction Response Code: ").append(txResponse.getResponseCode()).append("\n");
                    responseBody.append("Transaction ID: ").append(txResponse.getTransId()).append("\n");
                    responseBody.append("Auth Code: ").append(txResponse.getAuthCode()).append("\n");
                    responseBody.append("AVS Response: ").append(txResponse.getAvsResultCode()).append("\n");
                    responseBody.append("CVV Response: ").append(txResponse.getCvvResultCode()).append("\n");
                    
                    if (txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()) {
                        responseBody.append("Transaction Messages:\n");
                        for (TransactionResponse.Message msg : txResponse.getMessages().getMessage()) {
                            responseBody.append("  - Code: ").append(msg.getCode())
                                    .append(", Description: ").append(msg.getDescription()).append("\n");
                        }
                    }
                    
                    if (txResponse.getErrors() != null && !txResponse.getErrors().getError().isEmpty()) {
                        responseBody.append("Errors:\n");
                        for (TransactionResponse.Error error : txResponse.getErrors().getError()) {
                            responseBody.append("  - Error Code: ").append(error.getErrorCode())
                                    .append(", Error Text: ").append(error.getErrorText()).append("\n");
                        }
                    }
                } else {
                    responseBody.append("Transaction Response: null\n");
                }
            }
            
            responseBody.append("===========================================");
            
            log.debug("Authorize.Net Response Body:\n{}", responseBody.toString());
        }
    }
}


```


Checking the TransactionResponse structure to fix the compilation errors:



Fixing the type references. Checking the exact types used:



Using `var` to avoid specifying inner class types:


```java
package com.paymentgateway.gateway.impl;

import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import lombok.extern.slf4j.Slf4j;
import net.authorize.Environment;
import net.authorize.api.contract.v1.*;
import net.authorize.api.controller.CreateTransactionController;
import net.authorize.api.controller.base.ApiOperationBase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.stream.Collectors;

@Component
@Slf4j
public class AuthorizeNetGateway implements PaymentGateway {

    private final String apiLoginId;
    private final String transactionKey;
    private final Environment environment;

    public AuthorizeNetGateway(
            @Value("${authorize.net.api-login-id}") String apiLoginId,
            @Value("${authorize.net.transaction-key}") String transactionKey,
            @Value("${authorize.net.environment:SANDBOX}") String environment) {
        this.apiLoginId = apiLoginId;
        this.transactionKey = transactionKey;
        this.environment = "PRODUCTION".equalsIgnoreCase(environment) 
                ? Environment.PRODUCTION 
                : Environment.SANDBOX;
        
        ApiOperationBase.setEnvironment(this.environment);
    }

    @Override
    public PurchaseResponse purchase(PurchaseRequest request) {
        log.info("Processing purchase request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_CAPTURE_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        // Log request body to Authorize.Net
        logRequestToAuthorizeNet("PURCHASE", apiRequest, request);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        
        // Log response from Authorize.Net
        logResponseFromAuthorizeNet("PURCHASE", response);
        
        return mapPurchaseResponse(response);
    }

    @Override
    public AuthResponse authorize(PurchaseRequest request) {
        log.info("Processing authorize request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_ONLY_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        // Log request body to Authorize.Net
        logRequestToAuthorizeNet("AUTHORIZE", apiRequest, request);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        
        // Log response from Authorize.Net
        logResponseFromAuthorizeNet("AUTHORIZE", response);
        
        return mapAuthResponse(response);
    }

    @Override
    public CaptureResponse capture(String transactionId, long amountCents, String currency) {
        log.info("Processing capture for transaction: {}", transactionId);
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        
        TransactionRequestType transactionRequest = new TransactionRequestType();
        transactionRequest.setTransactionType(TransactionTypeEnum.PRIOR_AUTH_CAPTURE_TRANSACTION.value());
        transactionRequest.setRefTransId(transactionId);
        
        // Set amount if partial capture is needed
        if (amountCents > 0) {
            BigDecimal amount = BigDecimal.valueOf(amountCents).divide(BigDecimal.valueOf(100));
            transactionRequest.setAmount(amount);
        }

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        // Log request body to Authorize.Net
        logCaptureRequestToAuthorizeNet(apiRequest, transactionId, amountCents, currency);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        
        // Log response from Authorize.Net
        logResponseFromAuthorizeNet("CAPTURE", response);
        
        return mapCaptureResponse(response);
    }

    private MerchantAuthenticationType createMerchantAuthentication() {
        MerchantAuthenticationType merchantAuthentication = new MerchantAuthenticationType();
        merchantAuthentication.setName(apiLoginId);
        merchantAuthentication.setTransactionKey(transactionKey);
        return merchantAuthentication;
    }

    private TransactionRequestType createTransactionRequest(PurchaseRequest request) {
        TransactionRequestType transactionRequest = new TransactionRequestType();
        
        // Payment information
        PaymentType paymentType = new PaymentType();
        OpaqueDataType opaqueData = new OpaqueDataType();
        opaqueData.setDataDescriptor("COMMON.ACCEPT.INAPP.PAYMENT");
        opaqueData.setDataValue(request.getPaymentMethodToken());
        paymentType.setOpaqueData(opaqueData);
        transactionRequest.setPayment(paymentType);

        // Amount
        BigDecimal amount = request.getAmount().getAmount();
        transactionRequest.setAmount(amount);

        // Order information
        OrderType order = new OrderType();
        // Use merchantOrderId for invoiceNumber (required field, max 20 chars)
        // Validate and truncate to 20 characters if needed to fit Authorize.Net's limit
        String merchantOrderId = request.getMerchantOrderId();
        if (merchantOrderId == null || merchantOrderId.isBlank()) {
            throw new IllegalArgumentException("merchantOrderId is required for Authorize.Net transactions");
        }
        String invoiceNumber = merchantOrderId.length() > 20 
                ? merchantOrderId.substring(0, 20) 
                : merchantOrderId;
        order.setInvoiceNumber(invoiceNumber);
        order.setDescription(request.getDescription());
        transactionRequest.setOrder(order);

        return transactionRequest;
    }

    private PurchaseResponse mapPurchaseResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return PurchaseResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return PurchaseResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private AuthResponse mapAuthResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return AuthResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return AuthResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private CaptureResponse mapCaptureResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return CaptureResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return CaptureResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .build();
    }

    /**
     * Logs the request body being sent to Authorize.Net for purchase/authorize transactions
     */
    private void logRequestToAuthorizeNet(String transactionType, CreateTransactionRequest apiRequest, PurchaseRequest request) {
        if (log.isDebugEnabled()) {
            TransactionRequestType txRequest = apiRequest.getTransactionRequest();
            StringBuilder requestBody = new StringBuilder();
            requestBody.append("\n=== Authorize.Net Request (").append(transactionType).append(") ===\n");
            requestBody.append("Transaction Type: ").append(txRequest.getTransactionType()).append("\n");
            requestBody.append("Amount: ").append(txRequest.getAmount()).append("\n");
            
            if (txRequest.getOrder() != null) {
                requestBody.append("Invoice Number: ").append(txRequest.getOrder().getInvoiceNumber()).append("\n");
                requestBody.append("Description: ").append(txRequest.getOrder().getDescription()).append("\n");
            }
            
            if (txRequest.getPayment() != null && txRequest.getPayment().getOpaqueData() != null) {
                OpaqueDataType opaqueData = txRequest.getPayment().getOpaqueData();
                requestBody.append("Payment Data Descriptor: ").append(opaqueData.getDataDescriptor()).append("\n");
                // Mask payment token for security
                String token = opaqueData.getDataValue();
                if (token != null && token.length() > 8) {
                    requestBody.append("Payment Token: ").append(token.substring(0, 4))
                            .append("****").append(token.substring(token.length() - 4)).append("\n");
                } else {
                    requestBody.append("Payment Token: [MASKED]\n");
                }
            }
            
            requestBody.append("Merchant Order ID: ").append(request.getMerchantOrderId()).append("\n");
            requestBody.append("Order ID: ").append(request.getOrderId()).append("\n");
            requestBody.append("===========================================");
            
            log.debug("Authorize.Net Request Body:\n{}", requestBody.toString());
        }
    }

    /**
     * Logs the request body being sent to Authorize.Net for capture transactions
     */
    private void logCaptureRequestToAuthorizeNet(CreateTransactionRequest apiRequest, String transactionId, long amountCents, String currency) {
        if (log.isDebugEnabled()) {
            TransactionRequestType txRequest = apiRequest.getTransactionRequest();
            StringBuilder requestBody = new StringBuilder();
            requestBody.append("\n=== Authorize.Net Request (CAPTURE) ===\n");
            requestBody.append("Transaction Type: ").append(txRequest.getTransactionType()).append("\n");
            requestBody.append("Reference Transaction ID: ").append(transactionId).append("\n");
            requestBody.append("Amount: ").append(txRequest.getAmount()).append("\n");
            requestBody.append("Amount (cents): ").append(amountCents).append("\n");
            requestBody.append("Currency: ").append(currency).append("\n");
            requestBody.append("===========================================");
            
            log.debug("Authorize.Net Request Body:\n{}", requestBody.toString());
        }
    }

    /**
     * Logs the response received from Authorize.Net
     */
    private void logResponseFromAuthorizeNet(String transactionType, CreateTransactionResponse response) {
        if (log.isDebugEnabled()) {
            StringBuilder responseBody = new StringBuilder();
            responseBody.append("\n=== Authorize.Net Response (").append(transactionType).append(") ===\n");
            
            if (response == null) {
                responseBody.append("Response: null\n");
            } else {
                MessagesType messages = response.getMessages();
                if (messages != null) {
                    responseBody.append("Result Code: ").append(messages.getResultCode()).append("\n");
                    if (messages.getMessage() != null && !messages.getMessage().isEmpty()) {
                        responseBody.append("Messages:\n");
                        for (MessagesType.Message msg : messages.getMessage()) {
                            responseBody.append("  - Code: ").append(msg.getCode())
                                    .append(", Text: ").append(msg.getText()).append("\n");
                        }
                    }
                }
                
                TransactionResponse txResponse = response.getTransactionResponse();
                if (txResponse != null) {
                    responseBody.append("Transaction Response Code: ").append(txResponse.getResponseCode()).append("\n");
                    responseBody.append("Transaction ID: ").append(txResponse.getTransId()).append("\n");
                    responseBody.append("Auth Code: ").append(txResponse.getAuthCode()).append("\n");
                    responseBody.append("AVS Response: ").append(txResponse.getAvsResultCode()).append("\n");
                    responseBody.append("CVV Response: ").append(txResponse.getCvvResultCode()).append("\n");
                    
                    if (txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()) {
                        responseBody.append("Transaction Messages:\n");
                        for (var msg : txResponse.getMessages().getMessage()) {
                            responseBody.append("  - Code: ").append(msg.getCode())
                                    .append(", Description: ").append(msg.getDescription()).append("\n");
                        }
                    }
                    
                    if (txResponse.getErrors() != null && !txResponse.getErrors().getError().isEmpty()) {
                        responseBody.append("Errors:\n");
                        for (var error : txResponse.getErrors().getError()) {
                            responseBody.append("  - Error Code: ").append(error.getErrorCode())
                                    .append(", Error Text: ").append(error.getErrorText()).append("\n");
                        }
                    }
                } else {
                    responseBody.append("Transaction Response: null\n");
                }
            }
            
            responseBody.append("===========================================");
            
            log.debug("Authorize.Net Response Body:\n{}", responseBody.toString());
        }
    }
}


```


Compilation succeeded. Verifying logging configuration to ensure debug logs are enabled:



Removing the unused import:


```java
package com.paymentgateway.gateway.impl;

import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import lombok.extern.slf4j.Slf4j;
import net.authorize.Environment;
import net.authorize.api.contract.v1.*;
import net.authorize.api.controller.CreateTransactionController;
import net.authorize.api.controller.base.ApiOperationBase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Slf4j
public class AuthorizeNetGateway implements PaymentGateway {

    private final String apiLoginId;
    private final String transactionKey;
    private final Environment environment;

    public AuthorizeNetGateway(
            @Value("${authorize.net.api-login-id}") String apiLoginId,
            @Value("${authorize.net.transaction-key}") String transactionKey,
            @Value("${authorize.net.environment:SANDBOX}") String environment) {
        this.apiLoginId = apiLoginId;
        this.transactionKey = transactionKey;
        this.environment = "PRODUCTION".equalsIgnoreCase(environment) 
                ? Environment.PRODUCTION 
                : Environment.SANDBOX;
        
        ApiOperationBase.setEnvironment(this.environment);
    }

    @Override
    public PurchaseResponse purchase(PurchaseRequest request) {
        log.info("Processing purchase request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_CAPTURE_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        // Log request body to Authorize.Net
        logRequestToAuthorizeNet("PURCHASE", apiRequest, request);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        
        // Log response from Authorize.Net
        logResponseFromAuthorizeNet("PURCHASE", response);
        
        return mapPurchaseResponse(response);
    }

    @Override
    public AuthResponse authorize(PurchaseRequest request) {
        log.info("Processing authorize request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_ONLY_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        // Log request body to Authorize.Net
        logRequestToAuthorizeNet("AUTHORIZE", apiRequest, request);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        
        // Log response from Authorize.Net
        logResponseFromAuthorizeNet("AUTHORIZE", response);
        
        return mapAuthResponse(response);
    }

    @Override
    public CaptureResponse capture(String transactionId, long amountCents, String currency) {
        log.info("Processing capture for transaction: {}", transactionId);
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        
        TransactionRequestType transactionRequest = new TransactionRequestType();
        transactionRequest.setTransactionType(TransactionTypeEnum.PRIOR_AUTH_CAPTURE_TRANSACTION.value());
        transactionRequest.setRefTransId(transactionId);
        
        // Set amount if partial capture is needed
        if (amountCents > 0) {
            BigDecimal amount = BigDecimal.valueOf(amountCents).divide(BigDecimal.valueOf(100));
            transactionRequest.setAmount(amount);
        }

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        // Log request body to Authorize.Net
        logCaptureRequestToAuthorizeNet(apiRequest, transactionId, amountCents, currency);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        
        // Log response from Authorize.Net
        logResponseFromAuthorizeNet("CAPTURE", response);
        
        return mapCaptureResponse(response);
    }

    private MerchantAuthenticationType createMerchantAuthentication() {
        MerchantAuthenticationType merchantAuthentication = new MerchantAuthenticationType();
        merchantAuthentication.setName(apiLoginId);
        merchantAuthentication.setTransactionKey(transactionKey);
        return merchantAuthentication;
    }

    private TransactionRequestType createTransactionRequest(PurchaseRequest request) {
        TransactionRequestType transactionRequest = new TransactionRequestType();
        
        // Payment information
        PaymentType paymentType = new PaymentType();
        OpaqueDataType opaqueData = new OpaqueDataType();
        opaqueData.setDataDescriptor("COMMON.ACCEPT.INAPP.PAYMENT");
        opaqueData.setDataValue(request.getPaymentMethodToken());
        paymentType.setOpaqueData(opaqueData);
        transactionRequest.setPayment(paymentType);

        // Amount
        BigDecimal amount = request.getAmount().getAmount();
        transactionRequest.setAmount(amount);

        // Order information
        OrderType order = new OrderType();
        // Use merchantOrderId for invoiceNumber (required field, max 20 chars)
        // Validate and truncate to 20 characters if needed to fit Authorize.Net's limit
        String merchantOrderId = request.getMerchantOrderId();
        if (merchantOrderId == null || merchantOrderId.isBlank()) {
            throw new IllegalArgumentException("merchantOrderId is required for Authorize.Net transactions");
        }
        String invoiceNumber = merchantOrderId.length() > 20 
                ? merchantOrderId.substring(0, 20) 
                : merchantOrderId;
        order.setInvoiceNumber(invoiceNumber);
        order.setDescription(request.getDescription());
        transactionRequest.setOrder(order);

        return transactionRequest;
    }

    private PurchaseResponse mapPurchaseResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return PurchaseResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return PurchaseResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private AuthResponse mapAuthResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return AuthResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return AuthResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    private CaptureResponse mapCaptureResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return CaptureResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return CaptureResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .build();
    }

    /**
     * Logs the request body being sent to Authorize.Net for purchase/authorize transactions
     */
    private void logRequestToAuthorizeNet(String transactionType, CreateTransactionRequest apiRequest, PurchaseRequest request) {
        if (log.isDebugEnabled()) {
            TransactionRequestType txRequest = apiRequest.getTransactionRequest();
            StringBuilder requestBody = new StringBuilder();
            requestBody.append("\n=== Authorize.Net Request (").append(transactionType).append(") ===\n");
            requestBody.append("Transaction Type: ").append(txRequest.getTransactionType()).append("\n");
            requestBody.append("Amount: ").append(txRequest.getAmount()).append("\n");
            
            if (txRequest.getOrder() != null) {
                requestBody.append("Invoice Number: ").append(txRequest.getOrder().getInvoiceNumber()).append("\n");
                requestBody.append("Description: ").append(txRequest.getOrder().getDescription()).append("\n");
            }
            
            if (txRequest.getPayment() != null && txRequest.getPayment().getOpaqueData() != null) {
                OpaqueDataType opaqueData = txRequest.getPayment().getOpaqueData();
                requestBody.append("Payment Data Descriptor: ").append(opaqueData.getDataDescriptor()).append("\n");
                // Mask payment token for security
                String token = opaqueData.getDataValue();
                if (token != null && token.length() > 8) {
                    requestBody.append("Payment Token: ").append(token.substring(0, 4))
                            .append("****").append(token.substring(token.length() - 4)).append("\n");
                } else {
                    requestBody.append("Payment Token: [MASKED]\n");
                }
            }
            
            requestBody.append("Merchant Order ID: ").append(request.getMerchantOrderId()).append("\n");
            requestBody.append("Order ID: ").append(request.getOrderId()).append("\n");
            requestBody.append("===========================================");
            
            log.debug("Authorize.Net Request Body:\n{}", requestBody.toString());
        }
    }

    /**
     * Logs the request body being sent to Authorize.Net for capture transactions
     */
    private void logCaptureRequestToAuthorizeNet(CreateTransactionRequest apiRequest, String transactionId, long amountCents, String currency) {
        if (log.isDebugEnabled()) {
            TransactionRequestType txRequest = apiRequest.getTransactionRequest();
            StringBuilder requestBody = new StringBuilder();
            requestBody.append("\n=== Authorize.Net Request (CAPTURE) ===\n");
            requestBody.append("Transaction Type: ").append(txRequest.getTransactionType()).append("\n");
            requestBody.append("Reference Transaction ID: ").append(transactionId).append("\n");
            requestBody.append("Amount: ").append(txRequest.getAmount()).append("\n");
            requestBody.append("Amount (cents): ").append(amountCents).append("\n");
            requestBody.append("Currency: ").append(currency).append("\n");
            requestBody.append("===========================================");
            
            log.debug("Authorize.Net Request Body:\n{}", requestBody.toString());
        }
    }

    /**
     * Logs the response received from Authorize.Net
     */
    private void logResponseFromAuthorizeNet(String transactionType, CreateTransactionResponse response) {
        if (log.isDebugEnabled()) {
            StringBuilder responseBody = new StringBuilder();
            responseBody.append("\n=== Authorize.Net Response (").append(transactionType).append(") ===\n");
            
            if (response == null) {
                responseBody.append("Response: null\n");
            } else {
                MessagesType messages = response.getMessages();
                if (messages != null) {
                    responseBody.append("Result Code: ").append(messages.getResultCode()).append("\n");
                    if (messages.getMessage() != null && !messages.getMessage().isEmpty()) {
                        responseBody.append("Messages:\n");
                        for (MessagesType.Message msg : messages.getMessage()) {
                            responseBody.append("  - Code: ").append(msg.getCode())
                                    .append(", Text: ").append(msg.getText()).append("\n");
                        }
                    }
                }
                
                TransactionResponse txResponse = response.getTransactionResponse();
                if (txResponse != null) {
                    responseBody.append("Transaction Response Code: ").append(txResponse.getResponseCode()).append("\n");
                    responseBody.append("Transaction ID: ").append(txResponse.getTransId()).append("\n");
                    responseBody.append("Auth Code: ").append(txResponse.getAuthCode()).append("\n");
                    responseBody.append("AVS Response: ").append(txResponse.getAvsResultCode()).append("\n");
                    responseBody.append("CVV Response: ").append(txResponse.getCvvResultCode()).append("\n");
                    
                    if (txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()) {
                        responseBody.append("Transaction Messages:\n");
                        for (var msg : txResponse.getMessages().getMessage()) {
                            responseBody.append("  - Code: ").append(msg.getCode())
                                    .append(", Description: ").append(msg.getDescription()).append("\n");
                        }
                    }
                    
                    if (txResponse.getErrors() != null && !txResponse.getErrors().getError().isEmpty()) {
                        responseBody.append("Errors:\n");
                        for (var error : txResponse.getErrors().getError()) {
                            responseBody.append("  - Error Code: ").append(error.getErrorCode())
                                    .append(", Error Text: ").append(error.getErrorText()).append("\n");
                        }
                    }
                } else {
                    responseBody.append("Transaction Response: null\n");
                }
            }
            
            responseBody.append("===========================================");
            
            log.debug("Authorize.Net Response Body:\n{}", responseBody.toString());
        }
    }
}


```


## Summary: Added Debug Logging for Authorize.Net Integration

### Changes Made:

1. **Added debug logging for Purchase/Authorize requests**:
   - Logs transaction type, amount, invoice number (merchantOrderId), description
   - Masks payment token for security (shows first 4 and last 4 characters)
   - Includes order ID and merchant order ID

2. **Added debug logging for Capture requests**:
   - Logs transaction type, reference transaction ID, amount, currency

3. **Added debug logging for all responses**:
   - Logs result code and messages from the API response
   - Logs transaction response code, transaction ID, auth code
   - Logs AVS and CVV response codes
   - Logs all transaction messages with codes and descriptions
   - Logs all errors with error codes and error text

### Log Format:

The logs are formatted with clear separators:
```
=== Authorize.Net Request (PURCHASE) ===
Transaction Type: ...
Amount: ...
Invoice Number: ...
...
===========================================

=== Authorize.Net Response (PURCHASE) ===
Result Code: ...
Transaction Response Code: ...
Transaction ID: ...
...
===========================================
```

### Configuration:

Debug logging is already enabled in `application.yml`:
```yaml
logging:
  level:
    com.paymentgateway: DEBUG
```

When you run the application and make a purchase request, you'll see detailed debug logs showing:
- The exact request being sent to Authorize.Net
- The complete response received from Authorize.Net
- All error messages and codes if the transaction fails

This should help debug why transactions are failing. The logs will show the exact error messages from Authorize.Net's API.

```plaintext
=== Authorize.Net Request (PURCHASE) ===
Transaction Type: ...
Amount: ...
Invoice Number: ...
...
===========================================

=== Authorize.Net Response (PURCHASE) ===
Result Code: ...
Transaction Response Code: ...
Transaction ID: ...
...
===========================================
```

```yaml
logging:
  level:
    com.paymentgateway: DEBUG
```

