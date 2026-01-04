package com.paymentgateway.service;

import com.paymentgateway.domain.enums.*;
import com.paymentgateway.domain.model.Subscription;
import com.paymentgateway.domain.valueobject.Money;
import com.paymentgateway.repository.*;
import com.paymentgateway.repository.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing subscriptions and recurring payments.
 */
@Service
@Slf4j
public class SubscriptionService {

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private SubscriptionMapper subscriptionMapper;

    /**
     * Creates a new subscription.
     */
    @Transactional
    public Subscription createSubscription(
            UUID customerId,
            String merchantSubscriptionId,
            Money amount,
            RecurrenceInterval interval,
            Integer intervalCount,
            String paymentMethodToken,
            Gateway gateway,
            String description,
            String idempotencyKey,
            Instant startDate,
            Instant endDate,
            Integer maxBillingCycles) {

        String traceId = getOrCreateTraceId();
        MDC.put("traceId", traceId);
        MDC.put("operation", "createSubscription");
        MDC.put("merchantSubscriptionId", merchantSubscriptionId);

        try {
            log.info("Creating subscription: {}", merchantSubscriptionId);

            // Check idempotency
            if (subscriptionRepository.existsByIdempotencyKey(idempotencyKey)) {
                var entity = subscriptionRepository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException("Subscription exists but not found"));
                log.info("Subscription already exists with idempotency key: {}", idempotencyKey);
                return subscriptionMapper.toDomain(entity);
            }

            // Check uniqueness of merchant subscription ID
            if (subscriptionRepository.existsByMerchantSubscriptionId(merchantSubscriptionId)) {
                throw new IllegalArgumentException("Subscription with merchantSubscriptionId already exists: " + merchantSubscriptionId);
            }

            // Use current time if start date is not provided
            Instant actualStartDate = startDate != null ? startDate : Instant.now();
            
            // Calculate next billing date
            Instant nextBillingDate = calculateNextBillingDate(actualStartDate, interval, intervalCount);

            Subscription subscription = Subscription.builder()
                    .id(UUID.randomUUID())
                    .customerId(customerId)
                    .merchantSubscriptionId(merchantSubscriptionId)
                    .amount(amount)
                    .interval(interval)
                    .intervalCount(intervalCount != null ? intervalCount : 1)
                    .status(SubscriptionStatus.ACTIVE)
                    .gateway(gateway)
                    .paymentMethodToken(paymentMethodToken)
                    .startDate(actualStartDate)
                    .nextBillingDate(nextBillingDate)
                    .endDate(endDate)
                    .maxBillingCycles(maxBillingCycles)
                    .currentBillingCycle(0)
                    .description(description)
                    .idempotencyKey(idempotencyKey)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            var entity = subscriptionMapper.toEntity(subscription);
            var savedEntity = subscriptionRepository.save(entity);
            var saved = subscriptionMapper.toDomain(savedEntity);

            log.info("Subscription created successfully: {} (id: {})", merchantSubscriptionId, saved.getId());
            return saved;

        } finally {
            MDC.clear();
        }
    }

    /**
     * Gets a subscription by ID.
     */
    public Subscription getSubscriptionById(UUID subscriptionId) {
        return subscriptionRepository.findById(subscriptionId)
                .map(subscriptionMapper::toDomain)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + subscriptionId));
    }

    /**
     * Gets a subscription by merchant subscription ID.
     */
    public Subscription getSubscriptionByMerchantId(String merchantSubscriptionId) {
        return subscriptionRepository.findByMerchantSubscriptionId(merchantSubscriptionId)
                .map(subscriptionMapper::toDomain)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + merchantSubscriptionId));
    }

    /**
     * Gets all subscriptions for a customer.
     */
    public List<Subscription> getSubscriptionsByCustomerId(UUID customerId) {
        return subscriptionMapper.toDomainList(subscriptionRepository.findByCustomerId(customerId));
    }

    /**
     * Cancels a subscription.
     */
    @Transactional
    public Subscription cancelSubscription(UUID subscriptionId) {
        String traceId = getOrCreateTraceId();
        MDC.put("traceId", traceId);
        MDC.put("operation", "cancelSubscription");
        MDC.put("subscriptionId", subscriptionId.toString());

        try {
            log.info("Cancelling subscription: {}", subscriptionId);

            var entity = subscriptionRepository.findByIdWithLock(subscriptionId)
                    .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + subscriptionId));

            var subscription = subscriptionMapper.toDomain(entity);

            if (subscription.getStatus() == SubscriptionStatus.CANCELLED) {
                log.warn("Subscription already cancelled: {}", subscriptionId);
                return subscription;
            }

            var cancelled = subscription.withStatus(SubscriptionStatus.CANCELLED);
            var cancelledEntity = subscriptionMapper.toEntity(cancelled);
            var savedEntity = subscriptionRepository.save(cancelledEntity);

            log.info("Subscription cancelled successfully: {}", subscriptionId);
            return subscriptionMapper.toDomain(savedEntity);

        } finally {
            MDC.clear();
        }
    }

    /**
     * Pauses a subscription.
     */
    @Transactional
    public Subscription pauseSubscription(UUID subscriptionId) {
        String traceId = getOrCreateTraceId();
        MDC.put("traceId", traceId);
        MDC.put("operation", "pauseSubscription");
        MDC.put("subscriptionId", subscriptionId.toString());

        try {
            log.info("Pausing subscription: {}", subscriptionId);

            var entity = subscriptionRepository.findByIdWithLock(subscriptionId)
                    .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + subscriptionId));

            var subscription = subscriptionMapper.toDomain(entity);

            if (subscription.getStatus() != SubscriptionStatus.ACTIVE) {
                throw new IllegalStateException("Only active subscriptions can be paused");
            }

            var paused = subscription.withStatus(SubscriptionStatus.PAUSED);
            var pausedEntity = subscriptionMapper.toEntity(paused);
            var savedEntity = subscriptionRepository.save(pausedEntity);

            log.info("Subscription paused successfully: {}", subscriptionId);
            return subscriptionMapper.toDomain(savedEntity);

        } finally {
            MDC.clear();
        }
    }

    /**
     * Resumes a paused subscription.
     */
    @Transactional
    public Subscription resumeSubscription(UUID subscriptionId) {
        String traceId = getOrCreateTraceId();
        MDC.put("traceId", traceId);
        MDC.put("operation", "resumeSubscription");
        MDC.put("subscriptionId", subscriptionId.toString());

        try {
            log.info("Resuming subscription: {}", subscriptionId);

            var entity = subscriptionRepository.findByIdWithLock(subscriptionId)
                    .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + subscriptionId));

            var subscription = subscriptionMapper.toDomain(entity);

            if (subscription.getStatus() != SubscriptionStatus.PAUSED) {
                throw new IllegalStateException("Only paused subscriptions can be resumed");
            }

            var resumed = subscription.withStatus(SubscriptionStatus.ACTIVE);
            var resumedEntity = subscriptionMapper.toEntity(resumed);
            var savedEntity = subscriptionRepository.save(resumedEntity);

            log.info("Subscription resumed successfully: {}", subscriptionId);
            return subscriptionMapper.toDomain(savedEntity);

        } finally {
            MDC.clear();
        }
    }

    /**
     * Updates the next billing date after a successful payment.
     */
    @Transactional
    public Subscription updateNextBillingDate(UUID subscriptionId) {
        var entity = subscriptionRepository.findByIdWithLock(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + subscriptionId));

        var subscription = subscriptionMapper.toDomain(entity);

        Instant nextBillingDate = calculateNextBillingDate(
                subscription.getNextBillingDate(),
                subscription.getInterval(),
                subscription.getIntervalCount());

        var updated = subscription.withNextBillingDate(nextBillingDate)
                .incrementBillingCycle();

        var updatedEntity = subscriptionMapper.toEntity(updated);
        var savedEntity = subscriptionRepository.save(updatedEntity);

        return subscriptionMapper.toDomain(savedEntity);
    }

    /**
     * Calculates the next billing date based on interval.
     * Uses ZonedDateTime for month/year calculations since Instant doesn't support variable-length periods.
     */
    private Instant calculateNextBillingDate(Instant fromDate, RecurrenceInterval interval, Integer intervalCount) {
        if (fromDate == null) {
            throw new IllegalArgumentException("fromDate cannot be null");
        }
        
        int count = intervalCount != null ? intervalCount : 1;

        return switch (interval) {
            case DAILY -> fromDate.plus(count, ChronoUnit.DAYS);
            case WEEKLY -> fromDate.plus(count * 7L, ChronoUnit.DAYS);
            case MONTHLY -> {
                // Use ZonedDateTime for month calculations (handles variable month lengths)
                java.time.ZonedDateTime zonedDateTime = fromDate.atZone(java.time.ZoneId.systemDefault());
                yield zonedDateTime.plusMonths(count).toInstant();
            }
            case YEARLY -> {
                // Use ZonedDateTime for year calculations (handles leap years)
                java.time.ZonedDateTime zonedDateTime = fromDate.atZone(java.time.ZoneId.systemDefault());
                yield zonedDateTime.plusYears(count).toInstant();
            }
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

