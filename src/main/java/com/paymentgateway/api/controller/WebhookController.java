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

