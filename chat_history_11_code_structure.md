---
description: New prompt created from chat session
mode: edit
model: Claude Sonnet 4.5 (copilot)
---
<user>
can break the file into multiple services for segregation and easier handling ? 
</user>
<assistant>
I'll help you refactor this file into multiple services for better separation of concerns. Here's the step-by-step approach:

1. Extract merchant authentication logic into a separate service
2. Create a mapper service for response mapping
3. Create a logging service for Authorize.Net request/response logging
4. Create a customer profile service for customer profile operations
5. Keep the main gateway focused on transaction operations

Let me create these new service files:

### [AuthorizeNetMerchantAuthService.java](file:///mnt/sda6/shlok-code/payment_gateway_integration/src/main/java/com/paymentgateway/gateway/impl/AuthorizeNetMerchantAuthService.java)

Service for creating merchant authentication.

````java
package com.paymentgateway.gateway.impl;

import net.authorize.Environment;
import net.authorize.api.contract.v1.MerchantAuthenticationType;
import net.authorize.api.controller.base.ApiOperationBase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AuthorizeNetMerchantAuthService {
    
    private final String apiLoginId;
    private final String transactionKey;
    private final Environment environment;

    public AuthorizeNetMerchantAuthService(
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

    public MerchantAuthenticationType createMerchantAuthentication() {
        MerchantAuthenticationType merchantAuthentication = new MerchantAuthenticationType();
        merchantAuthentication.setName(apiLoginId);
        merchantAuthentication.setTransactionKey(transactionKey);
        return merchantAuthentication;
    }
}
````

### [AuthorizeNetResponseMapper.java](file:///mnt/sda6/shlok-code/payment_gateway_integration/src/main/java/com/paymentgateway/gateway/impl/AuthorizeNetResponseMapper.java)

Service for mapping Authorize.Net responses to DTOs.

````java
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
````

### [AuthorizeNetLoggingService.java](file:///mnt/sda6/shlok-code/payment_gateway_integration/src/main/java/com/paymentgateway/gateway/impl/AuthorizeNetLoggingService.java)

Service for logging Authorize.Net requests and responses.

````java
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
````

### [AuthorizeNetCustomerProfileService.java](file:///mnt/sda6/shlok-code/payment_gateway_integration/src/main/java/com/paymentgateway/gateway/impl/AuthorizeNetCustomerProfileService.java)

Service for customer profile operations.

````java
package com.paymentgateway.gateway.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.authorize.api.contract.v1.*;
import net.authorize.api.controller.CreateCustomerProfileController;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthorizeNetCustomerProfileService {

    private final AuthorizeNetMerchantAuthService merchantAuthService;
    private final AuthorizeNetLoggingService loggingService;

    /**
     * Creates a Customer Profile from an Accept.js payment token.
     */
    public CustomerProfilePaymentType createCustomerProfile(String acceptJsToken, String customerId) {
        log.info("Creating Customer Profile for customer: {}", customerId);
        log.debug("createCustomerProfile - incoming acceptJsToken (masked)={}, customerId={}",
                (acceptJsToken != null && acceptJsToken.length() > 8) ? acceptJsToken.substring(0,4) + "****" + acceptJsToken.substring(acceptJsToken.length()-4) : "[MASKED]",
                customerId);

        MerchantAuthenticationType merchantAuthentication = merchantAuthService.createMerchantAuthentication();
        
        CustomerProfileType customerProfile = new CustomerProfileType();
        customerProfile.setMerchantCustomerId(customerId);
        customerProfile.setEmail(customerId + "@example.com");
        
        CustomerPaymentProfileType paymentProfile = new CustomerPaymentProfileType();
        paymentProfile.setCustomerType(CustomerTypeEnum.INDIVIDUAL);
        
        CustomerAddressType billTo = new CustomerAddressType();
        billTo.setFirstName("Customer");
        billTo.setLastName("User");
        billTo.setAddress("123 Main St");
        billTo.setCity("Bellevue");
        billTo.setState("WA");
        billTo.setZip("98004");
        billTo.setCountry("US");
        paymentProfile.setBillTo(billTo);
        
        PaymentType paymentType = new PaymentType();
        OpaqueDataType opaqueData = new OpaqueDataType();
        opaqueData.setDataDescriptor("COMMON.ACCEPT.INAPP.PAYMENT");
        opaqueData.setDataValue(acceptJsToken);
        paymentType.setOpaqueData(opaqueData);
        paymentProfile.setPayment(paymentType);
        
        List<CustomerPaymentProfileType> paymentProfiles = new ArrayList<>();
        paymentProfiles.add(paymentProfile);
        customerProfile.setPaymentProfiles(paymentProfiles);
        
        CreateCustomerProfileRequest apiRequest = new CreateCustomerProfileRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setProfile(customerProfile);
        apiRequest.setValidationMode(ValidationModeEnum.TEST_MODE);
        
        log.debug("Executing CreateCustomerProfileController for customerId={}", customerId);
        CreateCustomerProfileController controller = new CreateCustomerProfileController(apiRequest);
        controller.execute();
        
        CreateCustomerProfileResponse response = controller.getApiResponse();
        loggingService.logCustomerProfileResponse("CREATE_CUSTOMER_PROFILE", response);
        
        if (response == null || response.getMessages() == null) {
            log.error("CreateCustomerProfile failed: no response or no messages, customerId={}", customerId);
            throw new RuntimeException("Failed to create Customer Profile: No response from gateway");
        }

        String resultCode = response.getMessages().getResultCode() != null ? response.getMessages().getResultCode().toString() : "UNKNOWN";
        log.info("CreateCustomerProfile resultCode={}, customerId={}", resultCode, customerId);

        if (MessageTypeEnum.ERROR.equals(response.getMessages().getResultCode())) {
            String messageText = null;
            if (response.getMessages().getMessage() != null && !response.getMessages().getMessage().isEmpty()) {
                messageText = response.getMessages().getMessage().get(0).getText();
                String errorCode = response.getMessages().getMessage().get(0).getCode();
                log.error("CreateCustomerProfile error: code={}, message={}", errorCode, messageText);
            }
            throw new RuntimeException("Failed to create Customer Profile: " + (messageText != null ? messageText : "Unknown error"));
        }

        String customerProfileId = response.getCustomerProfileId();
        String paymentProfileId = response.getCustomerPaymentProfileIdList() != null
                && response.getCustomerPaymentProfileIdList().getNumericString() != null
                && !response.getCustomerPaymentProfileIdList().getNumericString().isEmpty()
                ? response.getCustomerPaymentProfileIdList().getNumericString().get(0)
                : null;

        if (customerProfileId == null || customerProfileId.isBlank()) {
            log.error("CreateCustomerProfile failed: customerProfileId is null or blank");
            throw new RuntimeException("Failed to create Customer Profile: No profile ID returned");
        }
        
        log.info("Customer Profile created successfully: profileId={}, paymentProfileId={}, customerId={}", 
                customerProfileId, paymentProfileId, customerId);
        
        CustomerProfilePaymentType result = new CustomerProfilePaymentType();
        result.setCustomerProfileId(customerProfileId);
        result.setPaymentProfileId(paymentProfileId);
        return result;
    }
}
````

### [AuthorizeNetGateway.java](file:///mnt/sda6/shlok-code/payment_gateway_integration/src/main/java/com/paymentgateway/gateway/impl/AuthorizeNetGateway.java)

Refactored gateway to use the new services.

````java
package com.paymentgateway.gateway.impl;

import com.paymentgateway.gateway.PaymentGateway;
import com.paymentgateway.gateway.dto.AuthResponse;
import com.paymentgateway.gateway.dto.CaptureResponse;
import com.paymentgateway.gateway.dto.PurchaseRequest;
import com.paymentgateway.gateway.dto.PurchaseResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.authorize.api.contract.v1.*;
import net.authorize.api.controller.CreateTransactionController;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Slf4j
@RequiredArgsConstructor
public class AuthorizeNetGateway implements PaymentGateway {

    private final AuthorizeNetMerchantAuthService merchantAuthService;
    private final AuthorizeNetResponseMapper responseMapper;
    private final AuthorizeNetLoggingService loggingService;
    private final AuthorizeNetCustomerProfileService customerProfileService;

    @Override
    public PurchaseResponse purchase(PurchaseRequest request) {
        log.info("Processing purchase request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = merchantAuthService.createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_CAPTURE_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        loggingService.logRequestToAuthorizeNet("PURCHASE", apiRequest, request);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        loggingService.logTransactionResponse("PURCHASE", response);
        
        return responseMapper.mapPurchaseResponse(response);
    }

    @Override
    public AuthResponse authorize(PurchaseRequest request) {
        log.info("Processing authorize request for order: {}", request.getOrderId());
        
        MerchantAuthenticationType merchantAuthentication = merchantAuthService.createMerchantAuthentication();
        TransactionRequestType transactionRequest = createTransactionRequest(request);
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_ONLY_TRANSACTION.value());

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        loggingService.logRequestToAuthorizeNet("AUTHORIZE", apiRequest, request);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        loggingService.logTransactionResponse("AUTHORIZE", response);
        
        return responseMapper.mapAuthResponse(response);
    }

    @Override
    public CaptureResponse capture(String transactionId, long amountCents, String currency) {
        log.info("Processing capture for transaction: {}", transactionId);
        
        MerchantAuthenticationType merchantAuthentication = merchantAuthService.createMerchantAuthentication();
        
        TransactionRequestType transactionRequest = new TransactionRequestType();
        transactionRequest.setTransactionType(TransactionTypeEnum.PRIOR_AUTH_CAPTURE_TRANSACTION.value());
        transactionRequest.setRefTransId(transactionId);
        
        if (amountCents > 0) {
            BigDecimal amount = BigDecimal.valueOf(amountCents).divide(BigDecimal.valueOf(100));
            transactionRequest.setAmount(amount);
        }

        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);

        loggingService.logCaptureRequestToAuthorizeNet(apiRequest, transactionId, amountCents, currency);

        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();

        CreateTransactionResponse response = controller.getApiResponse();
        loggingService.logTransactionResponse("CAPTURE", response);
        
        return responseMapper.mapCaptureResponse(response);
    }

    private TransactionRequestType createTransactionRequest(PurchaseRequest request) {
        TransactionRequestType transactionRequest = new TransactionRequestType();
        
        PaymentType paymentType = new PaymentType();
        OpaqueDataType opaqueData = new OpaqueDataType();
        opaqueData.setDataDescriptor("COMMON.ACCEPT.INAPP.PAYMENT");
        opaqueData.setDataValue(request.getPaymentMethodToken());
        paymentType.setOpaqueData(opaqueData);
        transactionRequest.setPayment(paymentType);

        BigDecimal amount = request.getAmount().getAmount();
        transactionRequest.setAmount(amount);

        OrderType order = new OrderType();
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

    /**
     * Delegates to AuthorizeNetCustomerProfileService
     */
    public CustomerProfilePaymentType createCustomerProfile(String acceptJsToken, String customerId) {
        return customerProfileService.createCustomerProfile(acceptJsToken, customerId);
    }
    
    /**
     * Processes a purchase transaction using a Customer Profile.
     */
    public PurchaseResponse purchaseWithCustomerProfile(
            String customerProfileId,
            String paymentProfileId,
            BigDecimal amount,
            String merchantOrderId,
            String description) {
        log.info("Processing purchase with Customer Profile: profileId={}, paymentProfileId={}", 
                customerProfileId, paymentProfileId);
        
        MerchantAuthenticationType merchantAuthentication = merchantAuthService.createMerchantAuthentication();
        
        TransactionRequestType transactionRequest = new TransactionRequestType();
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_CAPTURE_TRANSACTION.value());
        transactionRequest.setAmount(amount);
        
        CustomerProfilePaymentType customerProfilePayment = new CustomerProfilePaymentType();
        customerProfilePayment.setCustomerProfileId(customerProfileId);
        if (paymentProfileId != null) {
            customerProfilePayment.setPaymentProfileId(paymentProfileId);
        }
        transactionRequest.setProfile(customerProfilePayment);
        
        OrderType order = new OrderType();
        String invoiceNumber = merchantOrderId != null && merchantOrderId.length() > 20 
                ? merchantOrderId.substring(0, 20) 
                : merchantOrderId;
        order.setInvoiceNumber(invoiceNumber);
        order.setDescription(description);
        transactionRequest.setOrder(order);
        
        CreateTransactionRequest apiRequest = new CreateTransactionRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setTransactionRequest(transactionRequest);
        
        log.info("Processing Customer Profile purchase for order: {}", merchantOrderId);
        loggingService.logCustomerProfileRequest(apiRequest);
        
        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();
        
        CreateTransactionResponse response = controller.getApiResponse();
        loggingService.logTransactionResponse("PURCHASE_WITH_CUSTOMER_PROFILE", response);
        
        return responseMapper.mapPurchaseResponse(response);
    }
}
````

The refactoring provides:
1. **AuthorizeNetMerchantAuthService** - Handles authentication setup
2. **AuthorizeNetResponseMapper** - Maps all response types to DTOs
3. **AuthorizeNetLoggingService** - Centralizes all logging logic
4. **AuthorizeNetCustomerProfileService** - Manages customer profile operations
5. **AuthorizeNetGateway** - Simplified, focused on transaction orchestration

Each service has a single responsibility and is easier to test and maintain independently.
</assistant>