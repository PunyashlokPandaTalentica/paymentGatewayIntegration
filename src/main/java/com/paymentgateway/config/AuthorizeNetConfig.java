package com.paymentgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "authorize.net")
public class AuthorizeNetConfig {
    private String apiLoginId;
    private String transactionKey;
    private String environment = "SANDBOX";
    private String webhookSignatureKey;

    public String getApiLoginId() {
        return apiLoginId;
    }

    public void setApiLoginId(String apiLoginId) {
        this.apiLoginId = apiLoginId;
    }

    public String getTransactionKey() {
        return transactionKey;
    }

    public void setTransactionKey(String transactionKey) {
        this.transactionKey = transactionKey;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getWebhookSignatureKey() {
        return webhookSignatureKey;
    }

    public void setWebhookSignatureKey(String webhookSignatureKey) {
        this.webhookSignatureKey = webhookSignatureKey;
    }
}

