package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.CreatePaymentRequest;
import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.PaymentResponse;
import com.paymentgateway.api.service.RequestSanitizationService;
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

    @Autowired
    private RequestSanitizationService sanitizationService;

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
            // Validate and sanitize UUID
            String sanitizedOrderId = sanitizationService.validateUuid(orderId, "orderId");
            UUID orderUuid = UUID.fromString(sanitizedOrderId);

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

