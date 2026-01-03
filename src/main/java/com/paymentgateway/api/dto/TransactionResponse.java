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

