package com.paymentgateway.gateway.impl;

import com.paymentgateway.gateway.dto.PurchaseRequest;
import lombok.extern.slf4j.Slf4j;
import net.authorize.api.contract.v1.*;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AuthorizeNetLoggingService {

    public void logRequestToAuthorizeNet(String transactionType, CreateTransactionRequest apiRequest, PurchaseRequest request) {
        if (log.isDebugEnabled()) {
            TransactionRequestType txRequest = apiRequest.getTransactionRequest();
            StringBuilder requestBody = new StringBuilder();
            requestBody.append("\n=== Authorize.Net Request (").append(transactionType).append(") ===\n");
            requestBody.append("Transaction Type: ").append(txRequest.getTransactionType()).append("\n");
            requestBody.append("Amount: ").append(txRequest.getAmount()).append("\n");
            
            if (txRequest.getOrder() != null) {
                requestBody.append("Invoice Number: ").append(txRequest.getOrder().getInvoiceNumber()).append("\n");
                requestBody.append("Description: ").append(txRequest.getOrder().getDescription()).append("\n");
            }
            
            if (txRequest.getPayment() != null && txRequest.getPayment().getOpaqueData() != null) {
                OpaqueDataType opaqueData = txRequest.getPayment().getOpaqueData();
                requestBody.append("Payment Data Descriptor: ").append(opaqueData.getDataDescriptor()).append("\n");
                String token = opaqueData.getDataValue();
                if (token != null && token.length() > 8) {
                    requestBody.append("Payment Token: ").append(token.substring(0, 4))
                            .append("****").append(token.substring(token.length() - 4)).append("\n");
                } else {
                    requestBody.append("Payment Token: [MASKED]\n");
                }
            }
            
            requestBody.append("Merchant Order ID: ").append(request.getMerchantOrderId()).append("\n");
            requestBody.append("Order ID: ").append(request.getOrderId()).append("\n");
            requestBody.append("===========================================");
            
            log.debug("Authorize.Net Request Body:\n{}", requestBody.toString());
        }
    }

    public void logCaptureRequestToAuthorizeNet(CreateTransactionRequest apiRequest, String transactionId, long amountCents, String currency) {
        if (log.isDebugEnabled()) {
            TransactionRequestType txRequest = apiRequest.getTransactionRequest();
            StringBuilder requestBody = new StringBuilder();
            requestBody.append("\n=== Authorize.Net Request (CAPTURE) ===\n");
            requestBody.append("Transaction Type: ").append(txRequest.getTransactionType()).append("\n");
            requestBody.append("Reference Transaction ID: ").append(transactionId).append("\n");
            requestBody.append("Amount: ").append(txRequest.getAmount()).append("\n");
            requestBody.append("Amount (cents): ").append(amountCents).append("\n");
            requestBody.append("Currency: ").append(currency).append("\n");
            requestBody.append("===========================================");
            
            log.debug("Authorize.Net Request Body:\n{}", requestBody.toString());
        }
    }

    public void logCustomerProfileRequest(CreateTransactionRequest apiRequest) {
        if (log.isDebugEnabled()) {
            StringBuilder responseBody = new StringBuilder();
            CustomerProfilePaymentType customerProfilePaymentType = apiRequest.getTransactionRequest().getProfile();
            responseBody.append("customerProfilePaymentType: ").append(customerProfilePaymentType);
            log.debug("API Request to Authorize.net: {}", responseBody);
        }
    }

    public void logTransactionResponse(String transactionType, CreateTransactionResponse response) {
        if (log.isDebugEnabled()) {
            StringBuilder responseBody = new StringBuilder();
            responseBody.append("\n=== Authorize.Net Response (").append(transactionType).append(") ===\n");
            
            if (response == null) {
                responseBody.append("Response: null\n");
            } else {
                MessagesType messages = response.getMessages();
                if (messages != null) {
                    responseBody.append("Result Code: ").append(messages.getResultCode()).append("\n");
                    if (messages.getMessage() != null && !messages.getMessage().isEmpty()) {
                        responseBody.append("Messages:\n");
                        for (MessagesType.Message msg : messages.getMessage()) {
                            responseBody.append("  - Code: ").append(msg.getCode())
                                    .append(", Text: ").append(msg.getText()).append("\n");
                        }
                    }
                }
                
                TransactionResponse txResponse = response.getTransactionResponse();
                if (txResponse != null) {
                    responseBody.append("Transaction Response Code: ").append(txResponse.getResponseCode()).append("\n");
                    responseBody.append("Transaction ID: ").append(txResponse.getTransId()).append("\n");
                    responseBody.append("Auth Code: ").append(txResponse.getAuthCode()).append("\n");
                    responseBody.append("AVS Response: ").append(txResponse.getAvsResultCode()).append("\n");
                    responseBody.append("CVV Response: ").append(txResponse.getCvvResultCode()).append("\n");
                    
                    if (txResponse.getMessages() != null && !txResponse.getMessages().getMessage().isEmpty()) {
                        responseBody.append("Transaction Messages:\n");
                        for (var msg : txResponse.getMessages().getMessage()) {
                            responseBody.append("  - Code: ").append(msg.getCode())
                                    .append(", Description: ").append(msg.getDescription()).append("\n");
                        }
                    }
                    
                    if (txResponse.getErrors() != null && !txResponse.getErrors().getError().isEmpty()) {
                        responseBody.append("Errors:\n");
                        for (var error : txResponse.getErrors().getError()) {
                            responseBody.append("  - Error Code: ").append(error.getErrorCode())
                                    .append(", Error Text: ").append(error.getErrorText()).append("\n");
                        }
                    }
                } else {
                    responseBody.append("Transaction Response: null\n");
                }
            }
            
            responseBody.append("===========================================");
            log.debug("Authorize.Net Response Body:\n{}", responseBody.toString());
        }
    }

    public void logCustomerProfileResponse(String transactionType, CreateCustomerProfileResponse response) {
        if (log.isDebugEnabled()) {
            StringBuilder responseBody = new StringBuilder();
            responseBody.append("\n=== Authorize.Net Response (").append(transactionType).append(") ===\n");

            if (response == null) {
                responseBody.append("Response: null\n");
            } else {
                MessagesType messages = response.getMessages();
                if (messages != null) {
                    responseBody.append("Result Code: ").append(messages.getResultCode()).append("\n");
                    if (messages.getMessage() != null && !messages.getMessage().isEmpty()) {
                        responseBody.append("Messages:\n");
                        for (MessagesType.Message msg : messages.getMessage()) {
                            responseBody.append("  - Code: ").append(msg.getCode())
                                    .append(", Text: ").append(msg.getText()).append("\n");
                        }
                    }
                }

                try {
                    if (response.getCustomerProfileId() != null) {
                        responseBody.append("Customer Profile ID: ").append(response.getCustomerProfileId()).append("\n");
                    }
                } catch (Exception e) {
                    log.debug("Unable to read customerProfileId from response", e);
                }

                try {
                    if (response.getCustomerPaymentProfileIdList() != null
                            && response.getCustomerPaymentProfileIdList().getNumericString() != null
                            && !response.getCustomerPaymentProfileIdList().getNumericString().isEmpty()) {
                        responseBody.append("Customer Payment Profile IDs:\n");
                        for (String id : response.getCustomerPaymentProfileIdList().getNumericString()) {
                            responseBody.append("  - ").append(id).append("\n");
                        }
                    }
                } catch (Exception e) {
                    log.debug("Unable to read customerPaymentProfileIdList from response", e);
                }
            }

            responseBody.append("===========================================");
            log.debug("Authorize.Net CustomerProfile Response Body:\n{}", responseBody.toString());
        }
    }

    public void logUpdateCustomerProfileResponse(String transactionType, UpdateCustomerProfileResponse response) {
        if (log.isDebugEnabled()) {
            StringBuilder responseBody = new StringBuilder();
            responseBody.append("\n=== Authorize.Net Response (").append(transactionType).append(") ===\n");
            if (response == null) {
                responseBody.append("Response: null\n");
            } else {
                MessagesType messages = response.getMessages();
                if (messages != null) {
                    responseBody.append("Result Code: ").append(messages.getResultCode()).append("\n");
                    if (messages.getMessage() != null && !messages.getMessage().isEmpty()) {
                        responseBody.append("Messages:\n");
                        for (MessagesType.Message msg : messages.getMessage()) {
                            responseBody.append("  - Code: ").append(msg.getCode())
                                    .append(", Text: ").append(msg.getText()).append("\n");
                        }
                    }
                }
            }
            responseBody.append("===========================================");
            log.debug("Authorize.Net UpdateCustomerProfile Response Body:\n{}", responseBody.toString());
        }
    }
}
