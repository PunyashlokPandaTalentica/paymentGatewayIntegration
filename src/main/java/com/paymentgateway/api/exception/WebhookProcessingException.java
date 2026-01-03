package com.paymentgateway.api.exception;

import java.util.UUID;

/**
 * Exception thrown when webhook processing fails.
 */
public class WebhookProcessingException extends RuntimeException {
    private final UUID webhookId;
    private final String gatewayEventId;
    private final boolean retryable;

    public WebhookProcessingException(String message, UUID webhookId, String gatewayEventId, boolean retryable) {
        super(message);
        this.webhookId = webhookId;
        this.gatewayEventId = gatewayEventId;
        this.retryable = retryable;
    }

    public WebhookProcessingException(String message, UUID webhookId, String gatewayEventId, boolean retryable, Throwable cause) {
        super(message, cause);
        this.webhookId = webhookId;
        this.gatewayEventId = gatewayEventId;
        this.retryable = retryable;
    }

    public UUID getWebhookId() {
        return webhookId;
    }

    public String getGatewayEventId() {
        return gatewayEventId;
    }

    public boolean isRetryable() {
        return retryable;
    }
}

