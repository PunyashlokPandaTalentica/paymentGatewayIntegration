package com.paymentgateway.api.util;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Utility class for sanitizing and validating user input to prevent XSS, SQL injection, and other attacks.
 */
@Component
public class InputSanitizer {

    private static final PolicyFactory HTML_POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "strong", "em", "u")
            .allowTextIn("p", "br", "strong", "em", "u")
            .toFactory();

    // Patterns for validation
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern ALPHANUMERIC_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"
    );

    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[1-9]\\d{1,14}$");

    // Dangerous patterns to detect SQL injection attempts
    private static final Pattern[] SQL_INJECTION_PATTERNS = {
            Pattern.compile("(?i).*((\\%27)|(\\'))((\\%6F)|o|(\\%4F))((\\%72)|r|(\\%52)).*"), // ' OR
            Pattern.compile("(?i).*((\\%27)|(\\'))((\\%55)|u|(\\%75))((\\%4E)|n|(\\%6E))((\\%49)|i|(\\%69))((\\%4F)|o|(\\%6F))((\\%4E)|n|(\\%6E)).*"), // ' UNION
            Pattern.compile("(?i).*((\\%27)|(\\'))((\\%2D)|-|(\\%2D))((\\%2D)|-|(\\%2D)).*"), // '--
            Pattern.compile("(?i).*((\\%27)|(\\'))((\\%2F)|/|(\\%2F))((\\%2A)|\\*|(\\%2A)).*"), // '/*
            Pattern.compile("(?i).*((\\%27)|(\\'))((\\%3B)|;|(\\%3B)).*"), // ';
            Pattern.compile("(?i).*(exec|execute|select|insert|update|delete|drop|create|alter|truncate|grant|revoke).*")
    };

    // XSS patterns
    private static final Pattern[] XSS_PATTERNS = {
            Pattern.compile("(?i).*<script.*>.*</script>.*"),
            Pattern.compile("(?i).*javascript:.*"),
            Pattern.compile("(?i).*onerror=.*"),
            Pattern.compile("(?i).*onload=.*"),
            Pattern.compile("(?i).*onclick=.*"),
            Pattern.compile("(?i).*<iframe.*>.*</iframe>.*"),
            Pattern.compile("(?i).*<object.*>.*</object>.*"),
            Pattern.compile("(?i).*<embed.*>.*</embed>.*")
    };

    /**
     * Sanitizes HTML content to prevent XSS attacks.
     */
    public String sanitizeHtml(String input) {
        if (input == null) {
            return null;
        }
        return HTML_POLICY.sanitize(input);
    }

    /**
     * Validates and sanitizes a string input.
     * Removes leading/trailing whitespace and checks for dangerous patterns.
     */
    public String sanitizeString(String input) {
        if (input == null) {
            return null;
        }

        String trimmed = input.trim();

        // Check for SQL injection patterns
        if (containsSqlInjection(trimmed)) {
            throw new IllegalArgumentException("Input contains potentially dangerous SQL injection patterns");
        }

        // Check for XSS patterns
        if (containsXss(trimmed)) {
            throw new IllegalArgumentException("Input contains potentially dangerous XSS patterns");
        }

        return trimmed;
    }

    /**
     * Validates UUID format.
     */
    public boolean isValidUuid(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return false;
        }
        return UUID_PATTERN.matcher(uuid).matches();
    }

    /**
     * Validates alphanumeric string (with underscores and hyphens).
     */
    public boolean isValidAlphanumeric(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        return ALPHANUMERIC_PATTERN.matcher(input).matches();
    }

    /**
     * Validates email format.
     */
    public boolean isValidEmail(String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email).matches();
    }

    /**
     * Validates phone number format (E.164).
     */
    public boolean isValidPhone(String phone) {
        if (phone == null || phone.isEmpty()) {
            return false;
        }
        return PHONE_PATTERN.matcher(phone).matches();
    }

    /**
     * Sanitizes and validates a merchant order ID.
     */
    public String sanitizeMerchantOrderId(String merchantOrderId) {
        if (merchantOrderId == null || merchantOrderId.isEmpty()) {
            throw new IllegalArgumentException("Merchant order ID cannot be null or empty");
        }

        String sanitized = sanitizeString(merchantOrderId);

        // Additional validation: max length
        if (sanitized.length() > 100) {
            throw new IllegalArgumentException("Merchant order ID exceeds maximum length of 100 characters");
        }

        return sanitized;
    }

    /**
     * Sanitizes and validates a description field.
     */
    public String sanitizeDescription(String description) {
        if (description == null) {
            return null;
        }

        // Remove HTML tags and sanitize
        String sanitized = sanitizeHtml(description);

        // Additional length check
        if (sanitized.length() > 1000) {
            throw new IllegalArgumentException("Description exceeds maximum length of 1000 characters");
        }

        return sanitized;
    }

    /**
     * Sanitizes customer email.
     */
    public String sanitizeEmail(String email) {
        if (email == null) {
            return null;
        }

        String sanitized = sanitizeString(email.toLowerCase());

        if (!isValidEmail(sanitized)) {
            throw new IllegalArgumentException("Invalid email format");
        }

        return sanitized;
    }

    /**
     * Sanitizes customer phone number.
     */
    public String sanitizePhone(String phone) {
        if (phone == null) {
            return null;
        }

        String sanitized = sanitizeString(phone);

        if (!isValidPhone(sanitized)) {
            throw new IllegalArgumentException("Invalid phone number format. Expected E.164 format (e.g., +1234567890)");
        }

        return sanitized;
    }

    /**
     * Checks if input contains SQL injection patterns.
     */
    private boolean containsSqlInjection(String input) {
        for (Pattern pattern : SQL_INJECTION_PATTERNS) {
            if (pattern.matcher(input).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if input contains XSS patterns.
     */
    private boolean containsXss(String input) {
        for (Pattern pattern : XSS_PATTERNS) {
            if (pattern.matcher(input).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates that a string doesn't exceed maximum length.
     */
    public String validateMaxLength(String input, int maxLength, String fieldName) {
        if (input == null) {
            return null;
        }

        if (input.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds maximum length of " + maxLength + " characters");
        }

        return sanitizeString(input);
    }

    /**
     * Validates that a string is not empty after sanitization.
     */
    public String validateNonEmpty(String input, String fieldName) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be null or empty");
        }
        return sanitizeString(input);
    }
}

