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
        
        // Validate inputs
        if (customerProfileId == null || customerProfileId.isBlank()) {
            throw new IllegalArgumentException("customerProfileId is required");
        }
        if (paymentProfileId == null || paymentProfileId.isBlank()) {
            throw new IllegalArgumentException("paymentProfileId is required - cannot use customer profile without payment profile ID");
        }
        
        MerchantAuthenticationType merchantAuthentication = merchantAuthService.createMerchantAuthentication();
        
        TransactionRequestType transactionRequest = new TransactionRequestType();
        transactionRequest.setTransactionType(TransactionTypeEnum.AUTH_CAPTURE_TRANSACTION.value());
        transactionRequest.setAmount(amount);
        
        // Create Customer Profile Payment - CRITICAL: ensure correct structure
        CustomerProfilePaymentType customerProfilePayment = new CustomerProfilePaymentType();
        customerProfilePayment.setCustomerProfileId(customerProfileId);
        customerProfilePayment.setPaymentProfileId(paymentProfileId);
        
        log.debug("Created CustomerProfilePaymentType: {}", customerProfilePayment);
        log.debug("CustomerProfileId value: '{}', PaymentProfileId value: '{}'", customerProfileId, paymentProfileId);
        
        transactionRequest.setProfile(customerProfilePayment);
        
        // Order information
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
        
        log.debug("Customer Profile Purchase request - customerProfileId={}, paymentProfileId={}, amount={}, merchantOrderId={}", 
                customerProfileId, paymentProfileId, amount, merchantOrderId);
        loggingService.logCustomerProfileRequest(apiRequest);
        
        CreateTransactionController controller = new CreateTransactionController(apiRequest);
        controller.execute();
        
        CreateTransactionResponse response = controller.getApiResponse();
        loggingService.logTransactionResponse("PURCHASE_WITH_CUSTOMER_PROFILE", response);
        
        // Log error details if transaction failed
        if (response != null && response.getMessages() != null 
                && MessageTypeEnum.ERROR.equals(response.getMessages().getResultCode())) {
            String errorMessage = response.getMessages().getMessage() != null 
                    && !response.getMessages().getMessage().isEmpty()
                    ? response.getMessages().getMessage().get(0).getText() 
                    : "Unknown error";
            String errorCode = response.getMessages().getMessage() != null 
                    && !response.getMessages().getMessage().isEmpty()
                    ? response.getMessages().getMessage().get(0).getCode()
                    : "UNKNOWN";
            log.error("Customer Profile Purchase FAILED - customerProfileId={}, paymentProfileId={}, errorCode={}, error={}", 
                    customerProfileId, paymentProfileId, errorCode, errorMessage);
        }
        
        return responseMapper.mapPurchaseResponse(response);
    }
}

