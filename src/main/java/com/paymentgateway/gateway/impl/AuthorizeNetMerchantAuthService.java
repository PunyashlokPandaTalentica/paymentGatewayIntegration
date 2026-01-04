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
