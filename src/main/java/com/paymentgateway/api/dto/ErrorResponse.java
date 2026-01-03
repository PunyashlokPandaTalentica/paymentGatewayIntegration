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

        @JsonProperty("errorCode")
        @Schema(description = "Specific error code from gateway or system", example = "GATEWAY_TIMEOUT")
        String errorCode;

        @JsonProperty("gatewayName")
        @Schema(description = "Name of the payment gateway if error is gateway-related", example = "AUTHORIZE_NET")
        String gatewayName;

        @JsonProperty("details")
        @Schema(description = "Additional error details or context")
        String details;
    }
}

