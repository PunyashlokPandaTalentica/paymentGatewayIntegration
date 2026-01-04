package com.paymentgateway.domain.enums;

/**
 * Status of a subscription.
 */
public enum SubscriptionStatus {
    ACTIVE,           // Subscription is active and recurring payments are scheduled
    PAUSED,           // Subscription is temporarily paused
    CANCELLED,        // Subscription has been cancelled
    EXPIRED,          // Subscription has expired (e.g., reached end date)
    FAILED            // Subscription failed due to payment failures
}

