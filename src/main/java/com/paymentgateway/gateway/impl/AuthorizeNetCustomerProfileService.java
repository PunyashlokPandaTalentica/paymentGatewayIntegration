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
        
        // Create customer profile
        CustomerProfileType customerProfile = new CustomerProfileType();
        customerProfile.setMerchantCustomerId(customerId);
        customerProfile.setEmail(customerId + "@example.com");
        
        // Create payment profile with Accept.js token
        CustomerPaymentProfileType paymentProfile = new CustomerPaymentProfileType();
        
        // IMPORTANT: Set customerType FIRST (XML element order matters)
        paymentProfile.setCustomerType(CustomerTypeEnum.INDIVIDUAL);
        
        // Add billing address SECOND
        CustomerAddressType billTo = new CustomerAddressType();
        billTo.setFirstName("Customer");
        billTo.setLastName("User");
        billTo.setAddress("123 Main St");
        billTo.setCity("Bellevue");
        billTo.setState("WA");
        billTo.setZip("98004");
        billTo.setCountry("US");
        paymentProfile.setBillTo(billTo);
        
        // Add payment information THIRD
        PaymentType paymentType = new PaymentType();
        OpaqueDataType opaqueData = new OpaqueDataType();
        opaqueData.setDataDescriptor("COMMON.ACCEPT.INAPP.PAYMENT");
        opaqueData.setDataValue(acceptJsToken);
        paymentType.setOpaqueData(opaqueData);
        paymentProfile.setPayment(paymentType);
        
        // CRITICAL: Set as default payment profile FOURTH
        paymentProfile.setDefaultPaymentProfile(true);
        log.debug("Setting payment profile as DEFAULT for customer: {}", customerId);
        
        // Add payment profile to customer profile
        List<CustomerPaymentProfileType> paymentProfiles = new ArrayList<>();
        paymentProfiles.add(paymentProfile);
        customerProfile.setPaymentProfiles(paymentProfiles);
        
        // Create request
        CreateCustomerProfileRequest apiRequest = new CreateCustomerProfileRequest();
        apiRequest.setMerchantAuthentication(merchantAuthentication);
        apiRequest.setProfile(customerProfile);
        apiRequest.setValidationMode(ValidationModeEnum.TEST_MODE);
        
        log.debug("Executing CreateCustomerProfileController for customerId={}", customerId);
        CreateCustomerProfileController controller = new CreateCustomerProfileController(apiRequest);
        controller.execute();
        
        CreateCustomerProfileResponse response = controller.getApiResponse();
        
        // Log detailed response
        loggingService.logCustomerProfileResponse("CREATE_CUSTOMER_PROFILE", response);
        
        // Validate response
        if (response == null || response.getMessages() == null) {
            log.error("CreateCustomerProfile failed: no response or no messages, customerId={}", customerId);
            throw new RuntimeException("Failed to create Customer Profile: No response from gateway");
        }

        String resultCode = response.getMessages().getResultCode() != null 
                ? response.getMessages().getResultCode().toString() 
                : "UNKNOWN";
        log.info("CreateCustomerProfile resultCode={}, customerId={}", resultCode, customerId);

        // Check for errors
        if (MessageTypeEnum.ERROR.equals(response.getMessages().getResultCode())) {
            String messageText = null;
            String errorCode = null;
            if (response.getMessages().getMessage() != null && !response.getMessages().getMessage().isEmpty()) {
                messageText = response.getMessages().getMessage().get(0).getText();
                errorCode = response.getMessages().getMessage().get(0).getCode();
            }
            log.error("CreateCustomerProfile error: code={}, message={}, customerId={}", 
                    errorCode, messageText, customerId);
            throw new RuntimeException("Failed to create Customer Profile: " + 
                    (messageText != null ? messageText : "Unknown error"));
        }

        // Extract IDs
        String customerProfileId = response.getCustomerProfileId();
        String paymentProfileId = null;
        
        if (response.getCustomerPaymentProfileIdList() != null
                && response.getCustomerPaymentProfileIdList().getNumericString() != null
                && !response.getCustomerPaymentProfileIdList().getNumericString().isEmpty()) {
            paymentProfileId = response.getCustomerPaymentProfileIdList().getNumericString().get(0);
        }

        // Validate that we got both IDs
        if (customerProfileId == null || customerProfileId.isBlank()) {
            log.error("CreateCustomerProfile failed: customerProfileId is null or blank, customerId={}", customerId);
            throw new RuntimeException("Failed to create Customer Profile: No profile ID returned");
        }
        
        if (paymentProfileId == null || paymentProfileId.isBlank()) {
            log.error("CreateCustomerProfile WARNING: paymentProfileId is null or blank - this WILL cause transaction failures, customerId={}, customerProfileId={}", 
                    customerId, customerProfileId);
            throw new RuntimeException("Failed to create Customer Profile: No payment profile ID returned");
        }
        
        log.info("Customer Profile created successfully: customerProfileId={}, paymentProfileId={}, customerId={}", 
                customerProfileId, paymentProfileId, customerId);
        
        // Return both IDs
        CustomerProfilePaymentType result = new CustomerProfilePaymentType();
        result.setCustomerProfileId(customerProfileId);
        result.setPaymentProfileId(paymentProfileId);
        return result;
    }
}
