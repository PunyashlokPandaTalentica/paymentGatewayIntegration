package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.CreateOrderRequest;
import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.OrderResponse;
import com.paymentgateway.api.service.RequestSanitizationService;
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

    @Autowired
    private RequestSanitizationService sanitizationService;

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
            // Sanitize input to prevent XSS and injection attacks
            CreateOrderRequest sanitizedRequest = sanitizationService.sanitize(request);

            Order order = Order.builder()
                    .id(UUID.randomUUID())
                    .merchantOrderId(sanitizedRequest.getMerchantOrderId())
                    .amount(sanitizedRequest.getAmount())
                    .description(sanitizedRequest.getDescription())
                    .customer(sanitizedRequest.getCustomer())
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
            // Validate and sanitize UUID
            String sanitizedOrderId = sanitizationService.validateUuid(orderId, "orderId");
            UUID id = UUID.fromString(sanitizedOrderId);
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

