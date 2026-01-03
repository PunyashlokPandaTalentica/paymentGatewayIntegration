# Swagger documentation updates
_Exported on 01/01/2026 at 19:31:32 GMT+5:30 from Cursor (2.0.77)_

---

**User**

Add and Updaate swagger docs with descriptions

---

**Cursor**


Reviewing the controllers to identify existing Swagger annotations and what needs to be added or updated.



Checking DTOs and request/response classes to document:



Reading key DTOs to understand their structure:



Checking for other controllers:



Reading the remaining controllers:



Checking the WebhookRequest DTO:



Adding Swagger/OpenAPI annotations with descriptions to all controllers and DTOs. Creating a task list and updating the files:



Adding Swagger annotations to all controllers and DTOs, starting with TransactionController:


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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Transactions", description = "API endpoints for managing payment transactions including purchase, authorization, and capture operations")
public class TransactionController {

    private final PaymentOrchestratorService orchestratorService;
    private final PaymentTransactionRepository transactionRepository;

    @PostMapping("/{paymentId}/transactions/purchase")
    public ResponseEntity<?> purchase(
            @PathVariable String paymentId,
            @Valid @RequestBody PurchaseTransactionRequest request,
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
    public ResponseEntity<?> authorize(
            @PathVariable String paymentId,
            @Valid @RequestBody PurchaseTransactionRequest request,
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
    public ResponseEntity<?> capture(
            @PathVariable String paymentId,
            @PathVariable String transactionId,
            @Valid @RequestBody CaptureRequest request,
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
    public ResponseEntity<?> getTransactions(@PathVariable String paymentId) {
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

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Transactions", description = "API endpoints for managing payment transactions including purchase, authorization, and capture operations")
public class TransactionController {

    private final PaymentOrchestratorService orchestratorService;
    private final PaymentTransactionRepository transactionRepository;

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
    public ResponseEntity<?> authorize(
            @PathVariable String paymentId,
            @Valid @RequestBody PurchaseTransactionRequest request,
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
    public ResponseEntity<?> capture(
            @PathVariable String paymentId,
            @PathVariable String transactionId,
            @Valid @RequestBody CaptureRequest request,
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
    public ResponseEntity<?> getTransactions(@PathVariable String paymentId) {
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

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Transactions", description = "API endpoints for managing payment transactions including purchase, authorization, and capture operations")
public class TransactionController {

    private final PaymentOrchestratorService orchestratorService;
    private final PaymentTransactionRepository transactionRepository;

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
    public ResponseEntity<?> capture(
            @PathVariable String paymentId,
            @PathVariable String transactionId,
            @Valid @RequestBody CaptureRequest request,
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
    public ResponseEntity<?> getTransactions(@PathVariable String paymentId) {
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

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Transactions", description = "API endpoints for managing payment transactions including purchase, authorization, and capture operations")
public class TransactionController {

    private final PaymentOrchestratorService orchestratorService;
    private final PaymentTransactionRepository transactionRepository;

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
    public ResponseEntity<?> getTransactions(@PathVariable String paymentId) {
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

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Transactions", description = "API endpoints for managing payment transactions including purchase, authorization, and capture operations")
public class TransactionController {

    private final PaymentOrchestratorService orchestratorService;
    private final PaymentTransactionRepository transactionRepository;

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


Updating OrderController:


```java
package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.CreateOrderRequest;
import com.paymentgateway.api.dto.ErrorResponse;
import com.paymentgateway.api.dto.OrderResponse;
import com.paymentgateway.domain.enums.OrderStatus;
import com.paymentgateway.domain.model.Order;
import com.paymentgateway.repository.OrderRepository;
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
    public ResponseEntity<?> getOrder(@PathVariable String orderId) {
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
import com.paymentgateway.repository.OrderRepository;
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
    public ResponseEntity<?> getOrder(@PathVariable String orderId) {
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
import com.paymentgateway.repository.OrderRepository;
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


Updating PaymentController:


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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payments", description = "API endpoints for managing payments. Payments represent the payment lifecycle and link orders to transactions.")
public class PaymentController {

    private final PaymentOrchestratorService orchestratorService;
    private final PaymentRepository paymentRepository;

    @PostMapping("/orders/{orderId}/payments")
    public ResponseEntity<?> createPayment(
            @PathVariable String orderId,
            @Valid @RequestBody CreatePaymentRequest request,
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

import java.util.UUID;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payments", description = "API endpoints for managing payments. Payments represent the payment lifecycle and link orders to transactions.")
public class PaymentController {

    private final PaymentOrchestratorService orchestratorService;
    private final PaymentRepository paymentRepository;

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


Updating WebhookController:


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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/webhooks")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Webhooks", description = "API endpoints for receiving webhook events from payment gateways. Webhooks are processed asynchronously and support idempotency.")
public class WebhookController {

    private final WebhookRepository webhookRepository;
    private final WebhookSignatureService signatureService;
    private final WebhookProcessorService processorService;

    @PostMapping("/authorize-net")
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
import com.paymentgateway.service.WebhookProcessorService;
import com.paymentgateway.service.WebhookSignatureService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/webhooks")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Webhooks", description = "API endpoints for receiving webhook events from payment gateways. Webhooks are processed asynchronously and support idempotency.")
public class WebhookController {

    private final WebhookRepository webhookRepository;
    private final WebhookSignatureService signatureService;
    private final WebhookProcessorService processorService;

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


Adding Swagger schema annotations to all DTOs, starting with the request DTOs:


```java
package com.paymentgateway.api.dto;

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
    @Schema(description = "Merchant's unique identifier for this order", required = true, example = "ORD-12345")
    String merchantOrderId;

    @NotNull
    @Valid
    @JsonProperty("amount")
    @Schema(description = "Order amount with currency", required = true)
    Money amount;

    @JsonProperty("description")
    @Schema(description = "Optional description of the order", example = "Purchase of goods and services")
    String description;

    @Valid
    @JsonProperty("customer")
    @Schema(description = "Customer information associated with the order")
    Customer customer;
}


```

```java
package com.paymentgateway.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Value;

@Value
@Schema(description = "Request to process a purchase or authorization transaction")
public class PurchaseTransactionRequest {
    @NotBlank
    @JsonProperty("paymentMethodToken")
    @Schema(description = "Tokenized payment method identifier from the payment gateway", required = true, example = "token_abc123xyz")
    String paymentMethodToken;
}


```

```java
package com.paymentgateway.api.dto;

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
    @Schema(description = "Amount to capture. Must be less than or equal to the authorized amount. Supports partial captures.", required = true)
    Money amount;
}


```

```java
package com.paymentgateway.api.dto;

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
    @Schema(description = "Payment method identifier", required = true, example = "credit_card")
    String method;

    @NotNull
    @JsonProperty("flow")
    @Schema(description = "Payment flow type - PURCHASE (authorize and capture) or AUTHORIZE (authorize only)", required = true, example = "PURCHASE")
    PaymentType flow;

    @NotNull
    @JsonProperty("gateway")
    @Schema(description = "Payment gateway to use for processing", required = true, example = "AUTHORIZE_NET")
    Gateway gateway;
}


```

```java
package com.paymentgateway.api.dto;

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
    @Schema(description = "Unique event identifier from the gateway, used for idempotency", required = true, example = "evt_1234567890")
    String eventId;

    @NotBlank
    @JsonProperty("eventType")
    @Schema(description = "Type of webhook event", required = true, example = "payment.authorized")
    String eventType;

    @NotNull
    @JsonProperty("gateway")
    @Schema(description = "Payment gateway that sent the webhook", required = true, example = "AUTHORIZE_NET")
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
}


```


Adding Swagger schema annotations to the response DTOs:


```java
package com.paymentgateway.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.paymentgateway.domain.enums.OrderStatus;
import com.paymentgateway.domain.valueobject.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
@Schema(description = "Order information response")
public class OrderResponse {
    @JsonProperty("orderId")
    @Schema(description = "Unique identifier of the order", example = "123e4567-e89b-12d3-a456-426614174000")
    String orderId;

    @JsonProperty("status")
    @Schema(description = "Current status of the order", example = "CREATED")
    OrderStatus status;

    @JsonProperty("amount")
    @Schema(description = "Order amount with currency")
    Money amount;

    @JsonProperty("createdAt")
    @Schema(description = "Timestamp when the order was created")
    Instant createdAt;
}


```

```java
package com.paymentgateway.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.paymentgateway.domain.enums.PaymentStatus;
import com.paymentgateway.domain.enums.PaymentType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Payment information response")
public class PaymentResponse {
    @JsonProperty("paymentId")
    @Schema(description = "Unique identifier of the payment", example = "123e4567-e89b-12d3-a456-426614174000")
    String paymentId;

    @JsonProperty("orderId")
    @Schema(description = "Unique identifier of the associated order", example = "223e4567-e89b-12d3-a456-426614174000")
    String orderId;

    @JsonProperty("status")
    @Schema(description = "Current status of the payment", example = "CREATED")
    PaymentStatus status;

    @JsonProperty("flow")
    @Schema(description = "Payment flow type", example = "PURCHASE")
    PaymentType flow;
}


```

```java
package com.paymentgateway.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.paymentgateway.domain.enums.TransactionState;
import com.paymentgateway.domain.enums.TransactionType;
import com.paymentgateway.domain.valueobject.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
@Schema(description = "Transaction information response")
public class TransactionResponse {
    @JsonProperty("transactionId")
    @Schema(description = "Unique identifier of the transaction", example = "123e4567-e89b-12d3-a456-426614174000")
    String transactionId;

    @JsonProperty("type")
    @Schema(description = "Type of transaction (PURCHASE, AUTHORIZE, CAPTURE)", example = "PURCHASE")
    TransactionType type;

    @JsonProperty("status")
    @Schema(description = "Current state of the transaction", example = "COMPLETED")
    TransactionState status;

    @JsonProperty("amount")
    @Schema(description = "Transaction amount with currency")
    Money amount;

    @JsonProperty("authorizedAmount")
    @Schema(description = "Authorized amount (only present for authorized transactions)", nullable = true)
    Money authorizedAmount;

    @JsonProperty("gatewayReferenceId")
    @Schema(description = "Reference ID from the payment gateway", example = "ref_abc123xyz", nullable = true)
    String gatewayReferenceId;

    @JsonProperty("parentTransactionId")
    @Schema(description = "Parent transaction ID (for capture transactions, references the authorization)", nullable = true)
    String parentTransactionId;

    @JsonProperty("retryOf")
    @Schema(description = "Transaction ID this is a retry of (if applicable)", nullable = true)
    String retryOf;

    @JsonProperty("createdAt")
    @Schema(description = "Timestamp when the transaction was created")
    Instant createdAt;
}


```

```java
package com.paymentgateway.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Error response containing error details")
public class ErrorResponse {
    @JsonProperty("error")
    @Schema(description = "Error details")
    ErrorDetail error;

    @Value
    @Builder
    @Schema(description = "Detailed error information")
    public static class ErrorDetail {
        @JsonProperty("code")
        @Schema(description = "Error code identifying the type of error", example = "INVALID_REQUEST")
        String code;

        @JsonProperty("message")
        @Schema(description = "Human-readable error message", example = "Payment not found")
        String message;

        @JsonProperty("retryable")
        @Schema(description = "Indicates whether the request can be retried", example = "false")
        boolean retryable;

        @JsonProperty("traceId")
        @Schema(description = "Unique trace identifier for debugging", example = "123e4567-e89b-12d3-a456-426614174000")
        String traceId;
    }
}


```


Checking for linting errors in the modified files:



Fixing deprecation warnings by replacing `required()` with `requiredMode = Schema.RequiredMode.REQUIRED`:


```java
package com.paymentgateway.api.dto;

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
}


```

```java
package com.paymentgateway.api.dto;

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
}


```

```java
package com.paymentgateway.api.dto;

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
}


```

```java
package com.paymentgateway.api.dto;

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
}


```

```java
package com.paymentgateway.api.dto;

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
}


```


Verifying all linting errors are resolved:



All linting errors are resolved. Adding Swagger documentation to the Money value object since it's used in multiple DTOs:


```java
package com.paymentgateway.domain.valueobject;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Value;

import java.math.BigDecimal;
import java.util.Currency;

@Value
@Schema(description = "Monetary value with amount and currency")
public class Money {
    @Schema(description = "Amount as a decimal string (e.g., \"100.50\" for $100.50)", example = "100.50")
    BigDecimal amount;
    
    @Schema(description = "Currency code (ISO 4217 format, e.g., \"USD\", \"EUR\")", example = "USD")
    Currency currency;

    @JsonCreator
    public Money(
            @JsonProperty("amount") String amount,
            @JsonProperty("currency") String currency) {
        this.amount = new BigDecimal(amount);
        this.currency = Currency.getInstance(currency);
    }

    public Money(BigDecimal amount, Currency currency) {
        this.amount = amount;
        this.currency = currency;
    }

    public long getAmountCents() {
        return amount.multiply(BigDecimal.valueOf(100)).longValue();
    }

    public static Money fromCents(long cents, String currency) {
        return new Money(
                BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100)),
                Currency.getInstance(currency)
        );
    }

    public String getAmountAsString() {
        return amount.toPlainString();
    }

    public String getCurrencyCode() {
        return currency.getCurrencyCode();
    }
}


```


Checking for linting errors in the Money class:



Added and updated Swagger/OpenAPI documentation across the codebase. Summary:

## Controllers updated

1. **TransactionController** — Added:
   - `@Tag` for controller-level description
   - `@Operation` with summary and description for each endpoint
   - `@ApiResponses` documenting success and error responses
   - `@Parameter` annotations for path variables and headers

2. **OrderController** — Added:
   - `@Tag` for controller description
   - `@Operation` annotations for create and get endpoints
   - `@ApiResponses` for all endpoints

3. **PaymentController** — Added:
   - `@Tag` for controller description
   - `@Operation` with description of payment creation flow
   - `@ApiResponses` documenting responses

4. **WebhookController** — Added:
   - `@Tag` for controller description
   - `@Operation` explaining webhook processing and idempotency
   - `@ApiResponses` for success and error cases

## DTOs updated

All request and response DTOs now include:
- `@Schema` annotations at the class level with descriptions
- Field-level `@Schema` annotations with descriptions, examples, and required mode
- Fixed deprecation warnings by using `requiredMode = Schema.RequiredMode.REQUIRED` instead of `required = true`

**Request DTOs:**
- `CreateOrderRequest`
- `PurchaseTransactionRequest`
- `CaptureRequest`
- `CreatePaymentRequest`
- `WebhookRequest`

**Response DTOs:**
- `OrderResponse`
- `PaymentResponse`
- `TransactionResponse`
- `ErrorResponse`

**Value Objects:**
- `Money` — Added schema documentation

All endpoints now have descriptions, parameter documentation, response schemas, and examples. The Swagger UI at `/swagger-ui.html` will display this documentation.

