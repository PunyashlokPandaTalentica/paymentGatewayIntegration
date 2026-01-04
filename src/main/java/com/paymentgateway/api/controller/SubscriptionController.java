package com.paymentgateway.api.controller;

import com.paymentgateway.api.dto.*;
import com.paymentgateway.api.service.RequestSanitizationService;
import com.paymentgateway.domain.enums.Gateway;
import com.paymentgateway.domain.model.Subscription;
import com.paymentgateway.domain.model.SubscriptionPayment;
import com.paymentgateway.service.RecurringPaymentService;
import com.paymentgateway.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
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
@RequestMapping("/v1/subscriptions")
@Tag(name = "Subscriptions", description = "Subscription and recurring payment management APIs")
@Slf4j
public class SubscriptionController {

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private RecurringPaymentService recurringPaymentService;

    @Autowired
    private RequestSanitizationService sanitizationService;

    @PostMapping
    @Operation(summary = "Create subscription", description = "Creates a new subscription for recurring payments")
    public ResponseEntity<SubscriptionResponse> createSubscription(
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody CreateSubscriptionRequest request) {

        log.info("CreateSubscription called merchantSubscriptionId={} idempotencyKey={}", request.getMerchantSubscriptionId(), idempotencyKey);
        log.debug("CreateSubscription request raw: {}", request);

        // Sanitize input to prevent XSS and injection attacks
        CreateSubscriptionRequest sanitizedRequest = sanitizationService.sanitize(request);
        log.debug("CreateSubscription request sanitized: merchantSubscriptionId={}, customerId={}",
                sanitizedRequest.getMerchantSubscriptionId(), sanitizedRequest.getCustomerId());

        Subscription subscription = subscriptionService.createSubscription(
                sanitizedRequest.getCustomerId(),
                sanitizedRequest.getMerchantSubscriptionId(),
                sanitizedRequest.getAmount(),
                sanitizedRequest.getInterval(),
                sanitizedRequest.getIntervalCount(),
                sanitizedRequest.getPaymentMethodToken(),
                sanitizedRequest.getGateway() != null ? sanitizedRequest.getGateway() : Gateway.AUTHORIZE_NET,
                sanitizedRequest.getDescription(),
                idempotencyKey,
                sanitizedRequest.getStartDate(),
                sanitizedRequest.getEndDate(),
                sanitizedRequest.getMaxBillingCycles());

        log.info("Subscription created via controller: id={}, merchantSubscriptionId={}", subscription.getId(), subscription.getMerchantSubscriptionId());
        log.debug("Created subscription details: {}", subscription);

        SubscriptionResponse response = SubscriptionResponse.builder()
                .subscriptionId(subscription.getId())
                .merchantSubscriptionId(subscription.getMerchantSubscriptionId())
                .customerId(subscription.getCustomerId())
                .amount(subscription.getAmount())
                .interval(subscription.getInterval())
                .intervalCount(subscription.getIntervalCount())
                .status(subscription.getStatus())
                .nextBillingDate(subscription.getNextBillingDate())
                .currentBillingCycle(subscription.getCurrentBillingCycle())
                .createdAt(subscription.getCreatedAt())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{subscriptionId}")
    @Operation(summary = "Get subscription", description = "Retrieves subscription details by ID")
    public ResponseEntity<SubscriptionResponse> getSubscription(@PathVariable String subscriptionId) {
        log.info("GetSubscription called for subscriptionId={}", subscriptionId);
        UUID id = UUID.fromString(sanitizationService.validateUuid(subscriptionId, "subscriptionId"));
        Subscription subscription = subscriptionService.getSubscriptionById(id);

        SubscriptionResponse response = SubscriptionResponse.builder()
                .subscriptionId(subscription.getId())
                .merchantSubscriptionId(subscription.getMerchantSubscriptionId())
                .customerId(subscription.getCustomerId())
                .amount(subscription.getAmount())
                .interval(subscription.getInterval())
                .intervalCount(subscription.getIntervalCount())
                .status(subscription.getStatus())
                .nextBillingDate(subscription.getNextBillingDate())
                .currentBillingCycle(subscription.getCurrentBillingCycle())
                .createdAt(subscription.getCreatedAt())
                .build();

        log.info("Subscription retrieved: id={}, merchantSubscriptionId={}", subscription.getId(), subscription.getMerchantSubscriptionId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/customer/{customerId}")
    @Operation(summary = "Get customer subscriptions", description = "Retrieves all subscriptions for a customer")
    public ResponseEntity<List<SubscriptionResponse>> getCustomerSubscriptions(@PathVariable String customerId) {
        log.info("GetCustomerSubscriptions called for customerId={}", customerId);
        String sanitizedCustomerId = customerId;
        log.debug("Sanitized customerId={}", sanitizedCustomerId);

        List<Subscription> subscriptions = subscriptionService.getSubscriptionsByCustomerId(sanitizedCustomerId);
        log.info("Found {} subscriptions for customerId={}", subscriptions.size(), sanitizedCustomerId);

        List<SubscriptionResponse> responses = subscriptions.stream()
                .map(s -> SubscriptionResponse.builder()
                        .subscriptionId(s.getId())
                        .merchantSubscriptionId(s.getMerchantSubscriptionId())
                        .customerId(s.getCustomerId())
                        .amount(s.getAmount())
                        .interval(s.getInterval())
                        .intervalCount(s.getIntervalCount())
                        .status(s.getStatus())
                        .nextBillingDate(s.getNextBillingDate())
                        .currentBillingCycle(s.getCurrentBillingCycle())
                        .createdAt(s.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        log.debug("Returning {} SubscriptionResponse items for customerId={}", responses.size(), sanitizedCustomerId);
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/{subscriptionId}/cancel")
    @Operation(summary = "Cancel subscription", description = "Cancels an active subscription")
    public ResponseEntity<SubscriptionResponse> cancelSubscription(@PathVariable String subscriptionId) {
        log.info("CancelSubscription called for subscriptionId={}", subscriptionId);
        UUID id = UUID.fromString(sanitizationService.validateUuid(subscriptionId, "subscriptionId"));
        Subscription subscription = subscriptionService.cancelSubscription(id);

        SubscriptionResponse response = SubscriptionResponse.builder()
                .subscriptionId(subscription.getId())
                .merchantSubscriptionId(subscription.getMerchantSubscriptionId())
                .customerId(subscription.getCustomerId())
                .amount(subscription.getAmount())
                .interval(subscription.getInterval())
                .intervalCount(subscription.getIntervalCount())
                .status(subscription.getStatus())
                .nextBillingDate(subscription.getNextBillingDate())
                .currentBillingCycle(subscription.getCurrentBillingCycle())
                .createdAt(subscription.getCreatedAt())
                .build();

        log.info("Subscription canceled: id={}, merchantSubscriptionId={}", subscription.getId(), subscription.getMerchantSubscriptionId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{subscriptionId}/pause")
    @Operation(summary = "Pause subscription", description = "Pauses an active subscription")
    public ResponseEntity<SubscriptionResponse> pauseSubscription(@PathVariable String subscriptionId) {
        log.info("PauseSubscription called for subscriptionId={}", subscriptionId);
        UUID id = UUID.fromString(sanitizationService.validateUuid(subscriptionId, "subscriptionId"));
        Subscription subscription = subscriptionService.pauseSubscription(id);

        SubscriptionResponse response = SubscriptionResponse.builder()
                .subscriptionId(subscription.getId())
                .merchantSubscriptionId(subscription.getMerchantSubscriptionId())
                .customerId(subscription.getCustomerId())
                .amount(subscription.getAmount())
                .interval(subscription.getInterval())
                .intervalCount(subscription.getIntervalCount())
                .status(subscription.getStatus())
                .nextBillingDate(subscription.getNextBillingDate())
                .currentBillingCycle(subscription.getCurrentBillingCycle())
                .createdAt(subscription.getCreatedAt())
                .build();

        log.info("Subscription paused: id={}, merchantSubscriptionId={}", subscription.getId(), subscription.getMerchantSubscriptionId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{subscriptionId}/resume")
    @Operation(summary = "Resume subscription", description = "Resumes a paused subscription")
    public ResponseEntity<SubscriptionResponse> resumeSubscription(@PathVariable String subscriptionId) {
        log.info("ResumeSubscription called for subscriptionId={}", subscriptionId);
        UUID id = UUID.fromString(sanitizationService.validateUuid(subscriptionId, "subscriptionId"));
        Subscription subscription = subscriptionService.resumeSubscription(id);

        SubscriptionResponse response = SubscriptionResponse.builder()
                .subscriptionId(subscription.getId())
                .merchantSubscriptionId(subscription.getMerchantSubscriptionId())
                .customerId(subscription.getCustomerId())
                .amount(subscription.getAmount())
                .interval(subscription.getInterval())
                .intervalCount(subscription.getIntervalCount())
                .status(subscription.getStatus())
                .nextBillingDate(subscription.getNextBillingDate())
                .currentBillingCycle(subscription.getCurrentBillingCycle())
                .createdAt(subscription.getCreatedAt())
                .build();

        log.info("Subscription resumed: id={}, merchantSubscriptionId={}", subscription.getId(), subscription.getMerchantSubscriptionId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{subscriptionId}/trigger-billing")
    @Operation(summary = "Trigger billing", description = "Manually triggers billing for a subscription")
    public ResponseEntity<?> triggerBilling(@PathVariable String subscriptionId) {
        log.info("TriggerBilling called for subscriptionId={}", subscriptionId);
        try {
            UUID id = UUID.fromString(sanitizationService.validateUuid(subscriptionId, "subscriptionId"));
            SubscriptionPayment payment = recurringPaymentService.triggerBilling(id);

            SubscriptionPaymentResponse response = SubscriptionPaymentResponse.builder()
                    .id(payment.getId())
                    .subscriptionId(payment.getSubscriptionId())
                    .paymentId(payment.getPaymentId())
                    .orderId(payment.getOrderId())
                    .billingCycle(payment.getBillingCycle())
                    .amount(payment.getAmount())
                    .scheduledDate(payment.getScheduledDate())
                    .processedAt(payment.getProcessedAt())
                    .createdAt(payment.getCreatedAt())
                    .build();

            log.info("Billing triggered successfully: paymentId={}, amount={}", payment.getId(), payment.getAmount());
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            log.warn("Cannot trigger billing: {}", e.getMessage());
            ErrorResponse error = ErrorResponse.builder()
                    .error(ErrorResponse.ErrorDetail.builder()
                            .code("INVALID_STATE")
                            .message(e.getMessage())
                            .retryable(false)
                            .traceId(UUID.randomUUID().toString())
                            .build())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }
}

