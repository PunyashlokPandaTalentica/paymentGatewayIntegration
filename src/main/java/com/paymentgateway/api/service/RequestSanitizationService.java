package com.paymentgateway.api.service;

import com.paymentgateway.api.dto.CreateOrderRequest;
import com.paymentgateway.api.dto.CreateSubscriptionRequest;
import com.paymentgateway.api.util.InputSanitizer;
import com.paymentgateway.domain.model.Customer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service for sanitizing and validating request DTOs.
 */
@Service
@Slf4j
public class RequestSanitizationService {

    @Autowired
    private InputSanitizer inputSanitizer;

    /**
     * Sanitizes a CreateOrderRequest.
     */
    public CreateOrderRequest sanitize(CreateOrderRequest request) {
        if (request == null) {
            return null;
        }

        String sanitizedMerchantOrderId = inputSanitizer.sanitizeMerchantOrderId(request.getMerchantOrderId());
        String sanitizedDescription = request.getDescription() != null 
                ? inputSanitizer.sanitizeDescription(request.getDescription()) 
                : null;

        Customer sanitizedCustomer = null;
        if (request.getCustomer() != null) {
            String sanitizedEmail = request.getCustomer().getEmail() != null
                    ? inputSanitizer.sanitizeEmail(request.getCustomer().getEmail())
                    : null;
            String sanitizedPhone = request.getCustomer().getPhone() != null
                    ? inputSanitizer.sanitizePhone(request.getCustomer().getPhone())
                    : null;

            sanitizedCustomer = new Customer(sanitizedEmail, sanitizedPhone);
        }

        return new CreateOrderRequest(
                sanitizedMerchantOrderId,
                request.getAmount(), // Money is already validated
                sanitizedDescription,
                sanitizedCustomer
        );
    }

    /**
     * Sanitizes a CreateSubscriptionRequest.
     */
    public CreateSubscriptionRequest sanitize(CreateSubscriptionRequest request) {
        if (request == null) {
            return null;
        }

        String sanitizedMerchantSubscriptionId = inputSanitizer.validateNonEmpty(
                request.getMerchantSubscriptionId(), "merchantSubscriptionId");
        sanitizedMerchantSubscriptionId = inputSanitizer.validateMaxLength(
                sanitizedMerchantSubscriptionId, 100, "merchantSubscriptionId");

        String sanitizedDescription = request.getDescription() != null
                ? inputSanitizer.sanitizeDescription(request.getDescription())
                : null;

        String sanitizedPaymentMethodToken = inputSanitizer.validateNonEmpty(
                request.getPaymentMethodToken(), "paymentMethodToken");
        sanitizedPaymentMethodToken = inputSanitizer.sanitizeString(sanitizedPaymentMethodToken);

        return new CreateSubscriptionRequest(
                request.getCustomerId(), // UUID is validated
                sanitizedMerchantSubscriptionId,
                request.getAmount(), // Money is already validated
                request.getInterval(), // Enum is validated
                request.getIntervalCount(),
                sanitizedPaymentMethodToken,
                request.getGateway(), // Enum is validated
                sanitizedDescription,
                request.getStartDate(),
                request.getEndDate(),
                request.getMaxBillingCycles()
        );
    }

    /**
     * Sanitizes a payment method token.
     */
    public String sanitizePaymentMethodToken(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("Payment method token cannot be null or empty");
        }
        return inputSanitizer.sanitizeString(token);
    }

    /**
     * Validates and sanitizes a UUID string.
     */
    public String validateUuid(String uuid, String fieldName) {
        if (uuid == null || uuid.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be null or empty");
        }

        String sanitized = inputSanitizer.sanitizeString(uuid);

        if (!inputSanitizer.isValidUuid(sanitized)) {
            throw new IllegalArgumentException("Invalid UUID format for " + fieldName);
        }

        return sanitized;
    }
}

