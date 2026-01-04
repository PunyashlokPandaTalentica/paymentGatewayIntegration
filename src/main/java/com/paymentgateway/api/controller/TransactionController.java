package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.CaptureRequest;
import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.PurchaseTransactionRequest;
import com.paymentgateway.api.dto.TransactionResponse;
import com.paymentgateway.api.service.RequestSanitizationService;
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
import org.springframework.context.annotation.Lazy;
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

    @Autowired
    @Lazy
    private RequestSanitizationService sanitizationService;

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
            // Validate and sanitize UUID
            String sanitizedPaymentId = sanitizationService.validateUuid(paymentId, "paymentId");
            UUID paymentUuid = UUID.fromString(sanitizedPaymentId);
            
            // Sanitize payment method token
            String sanitizedToken = sanitizationService.sanitizePaymentMethodToken(request.getPaymentMethodToken());
            
            UUID traceId = UUID.randomUUID();

            PaymentTransaction transaction = orchestratorService.processPurchase(
                    paymentUuid,
                    sanitizedToken,
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
            // Validate and sanitize UUID
            String sanitizedPaymentId = sanitizationService.validateUuid(paymentId, "paymentId");
            UUID paymentUuid = UUID.fromString(sanitizedPaymentId);
            
            // Sanitize payment method token
            String sanitizedToken = sanitizationService.sanitizePaymentMethodToken(request.getPaymentMethodToken());
            
            UUID traceId = UUID.randomUUID();

            PaymentTransaction transaction = orchestratorService.processAuthorize(
                    paymentUuid,
                    sanitizedToken,
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
            // Validate and sanitize UUIDs
            String sanitizedPaymentId = sanitizationService.validateUuid(paymentId, "paymentId");
            String sanitizedTransactionId = sanitizationService.validateUuid(transactionId, "transactionId");
            UUID paymentUuid = UUID.fromString(sanitizedPaymentId);
            UUID transactionUuid = UUID.fromString(sanitizedTransactionId);
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
            // Validate and sanitize UUID
            String sanitizedPaymentId = sanitizationService.validateUuid(paymentId, "paymentId");
            UUID paymentUuid = UUID.fromString(sanitizedPaymentId);
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

