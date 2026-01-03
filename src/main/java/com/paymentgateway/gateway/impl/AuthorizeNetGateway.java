package com.paymentgateway.gateway.impl;

import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import lombok.extern.slf4j.Slf4j;
import net.authorize.Environment;
import net.authorize.api.contract.v1.*;
import net.authorize.api.controller.CreateTransactionController;
import net.authorize.api.controller.base.ApiOperationBase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Slf4j
public class AuthorizeNetGateway implements PaymentGateway {

    private final String apiLoginId;
    private final String transactionKey;
    private final Environment environment;

    public AuthorizeNetGateway(
            @Value("${authorize.net.api-login-id}") String apiLoginId,
            @Value("${authorize.net.transaction-key}") String transactionKey,
            @Value("${authorize.net.environment:SANDBOX}") String environment) {
        this.apiLoginId = apiLoginId;
        this.transactionKey = transactionKey;
        this.environment = "PRODUCTION".equalsIgnoreCase(environment) 
                ? Environment.PRODUCTION 
                : Environment.SANDBOX;
        
        ApiOperationBase.setEnvironment(this.environment);
    }

    @Override
    public PurchaseResponse purchase(PurchaseRequest request) {
        log.info("Processing purchase request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_CAPTURE_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        // Log request body to Authorize.Net
        logRequestToAuthorizeNet("PURCHASE", apiRequest, request);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        
        // Log response from Authorize.Net
        logResponseFromAuthorizeNet("PURCHASE", response);
        
        return mapPurchaseResponse(response);
    }

    @Override
    public AuthResponse authorize(PurchaseRequest request) {
        log.info("Processing authorize request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_ONLY_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        // Log request body to Authorize.Net
        logRequestToAuthorizeNet("AUTHORIZE", apiRequest, request);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        
        // Log response from Authorize.Net
        logResponseFromAuthorizeNet("AUTHORIZE", response);
        
        return mapAuthResponse(response);
    }

    @Override
    public CaptureResponse capture(String transactionId, long amountCents, String currency) {
        log.info("Processing capture for transaction: {}", transactionId);
        
        MerchantAuthenticationType merchantAuthentication = createMerchantAuthentication();
        
        TransactionRequestType transactionRequest = new TransactionRequestType();
        transactionRequest.setTransactionType(TransactionTypeEnum.PRIOR_AUTH_CAPTURE_TRANSACTION.value());
        transactionRequest.setRefTransId(transactionId);
        
        // Set amount if partial capture is needed
        if (amountCents > 0) {
            BigDecimal amount = BigDecimal.valueOf(amountCents).divide(BigDecimal.valueOf(100));
            transactionRequest.setAmount(amount);
        }

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        // Log request body to Authorize.Net
        logCaptureRequestToAuthorizeNet(apiRequest, transactionId, amountCents, currency);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        
        // Log response from Authorize.Net
        logResponseFromAuthorizeNet("CAPTURE", response);
        
        return mapCaptureResponse(response);
    }

    private MerchantAuthenticationType createMerchantAuthentication() {
        MerchantAuthenticationType merchantAuthentication = new MerchantAuthenticationType();
        merchantAuthentication.setName(apiLoginId);
        merchantAuthentication.setTransactionKey(transactionKey);
        return merchantAuthentication;
    }

    private TransactionRequestType createTransactionRequest(PurchaseRequest request) {
        TransactionRequestType transactionRequest = new TransactionRequestType();
        
        // Payment information
        PaymentType paymentType = new PaymentType();
        OpaqueDataType opaqueData = new OpaqueDataType();
        opaqueData.setDataDescriptor("COMMON.ACCEPT.INAPP.PAYMENT");
        opaqueData.setDataValue(request.getPaymentMethodToken());
        paymentType.setOpaqueData(opaqueData);
        transactionRequest.setPayment(paymentType);

        // Amount
        BigDecimal amount = request.getAmount().getAmount();
        transactionRequest.setAmount(amount);

        // Order information
        OrderType order = new OrderType();
        // Use merchantOrderId for invoiceNumber (required field, max 20 chars)
        // Validate and truncate to 20 characters if needed to fit Authorize.Net's limit
        String merchantOrderId = request.getMerchantOrderId();
        if (merchantOrderId == null || merchantOrderId.isBlank()) {
            throw new IllegalArgumentException("merchantOrderId is required for Authorize.Net transactions");
        }
        String invoiceNumber = merchantOrderId.length() > 20 
                ? merchantOrderId.substring(0, 20) 
                : merchantOrderId;
        order.setInvoiceNumber(invoiceNumber);
        order.setDescription(request.getDescription());
        transactionRequest.setOrder(order);

        return transactionRequest;
    }

    private PurchaseResponse mapPurchaseResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return PurchaseResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
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

    private AuthResponse mapAuthResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return AuthResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
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

    private CaptureResponse mapCaptureResponse(CreateTransactionResponse response) {
        if (response == null || response.getTransactionResponse() == null) {
            return CaptureResponse.builder()
                    .success(false)
                    .responseMessage("No response from gateway")
                    .build();
        }

        TransactionResponse txResponse = response.getTransactionResponse();
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

    /**
     * Logs the request body being sent to Authorize.Net for purchase/authorize transactions
     */
    private void logRequestToAuthorizeNet(String transactionType, CreateTransactionRequest apiRequest, PurchaseRequest request) {
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
                // Mask payment token for security
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

    /**
     * Logs the request body being sent to Authorize.Net for capture transactions
     */
    private void logCaptureRequestToAuthorizeNet(CreateTransactionRequest apiRequest, String transactionId, long amountCents, String currency) {
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

    /**
     * Logs the response received from Authorize.Net
     */
    private void logResponseFromAuthorizeNet(String transactionType, CreateTransactionResponse response) {
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
}

