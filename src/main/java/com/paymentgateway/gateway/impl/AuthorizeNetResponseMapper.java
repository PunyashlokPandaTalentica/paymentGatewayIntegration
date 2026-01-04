package com.paymentgateway.gateway.impl;

import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import net.authorize.api.contract.v1.CreateTransactionResponse;
import net.authorize.api.contract.v1.MessagesType;
import net.authorize.api.contract.v1.TransactionResponse;
import org.springframework.stereotype.Service;

@Service
public class AuthorizeNetResponseMapper {

    public PurchaseResponse mapPurchaseResponse(CreateTransactionResponse response) {
        if (response == null) {
            return PurchaseResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        if (txResponse == null) {
            MessagesType messages = response.getMessages();
            String resultCode = messages != null && messages.getResultCode() != null
                    ? messages.getResultCode().toString() : null;
            String messageText = null;
            if (messages != null && messages.getMessage() != null && !messages.getMessage().isEmpty()) {
                messageText = messages.getMessage().get(0).getText();
            }
            return PurchaseResponse.builder()
                    .success(false)
                    .gatewayTransactionId(null)
                    .responseCode(resultCode)
                    .responseMessage(messageText != null ? messageText : "No response from gateway")
                    .authCode(null)
                    .build();
        }

        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return PurchaseResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    public AuthResponse mapAuthResponse(CreateTransactionResponse response) {
        if (response == null) {
            return AuthResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        if (txResponse == null) {
            MessagesType messages = response.getMessages();
            String resultCode = messages != null && messages.getResultCode() != null
                    ? messages.getResultCode().toString() : null;
            String messageText = null;
            if (messages != null && messages.getMessage() != null && !messages.getMessage().isEmpty()) {
                messageText = messages.getMessage().get(0).getText();
            }
            return AuthResponse.builder()
                    .success(false)
                    .gatewayTransactionId(null)
                    .responseCode(resultCode)
                    .responseMessage(messageText != null ? messageText : "No response from gateway")
                    .authCode(null)
                    .build();
        }

        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return AuthResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .authCode(txResponse.getAuthCode())
                .build();
    }

    public CaptureResponse mapCaptureResponse(CreateTransactionResponse response) {
        if (response == null) {
            return CaptureResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
        if (txResponse == null) {
            MessagesType messages = response.getMessages();
            String resultCode = messages != null && messages.getResultCode() != null
                    ? messages.getResultCode().toString() : null;
            String messageText = null;
            if (messages != null && messages.getMessage() != null && !messages.getMessage().isEmpty()) {
                messageText = messages.getMessage().get(0).getText();
            }
            return CaptureResponse.builder()
                    .success(false)
                    .gatewayTransactionId(null)
                    .responseCode(resultCode)
                    .responseMessage(messageText != null ? messageText : "No response from gateway")
                    .build();
        }

        String responseCode = txResponse.getResponseCode();
        boolean success = "1".equals(responseCode);

        return CaptureResponse.builder()
                .success(success)
                .gatewayTransactionId(txResponse.getTransId())
                .responseCode(responseCode)
                .responseMessage(txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()
                        ? txResponse.getMessages().getMessage().get(0).getDescription()
                        : "Unknown error")
                .build();
    }
}
