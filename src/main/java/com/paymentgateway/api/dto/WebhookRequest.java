package com.paymentgateway.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
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
    
    @JsonCreator
    public WebhookRequest(
            @JsonProperty("eventId") String eventId,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("gateway") String gateway,
            @JsonProperty("transactionReferenceId") String transactionReferenceId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("signature") String signature,
            @JsonProperty("payload") Map<String, Object> payload) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.gateway = gateway;
        this.transactionReferenceId = transactionReferenceId;
        this.occurredAt = occurredAt;
        this.signature = signature;
        this.payload = payload;
    }
}

