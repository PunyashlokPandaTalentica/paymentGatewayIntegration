package com.paymentgateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Service
@Slf4j
public class WebhookSignatureService {

    private final String signatureKey;

    public WebhookSignatureService(
            @Value("${authorize.net.webhook-signature-key:}") String signatureKey) {
        this.signatureKey = signatureKey;
    }

    /**
     * Verifies HMAC SHA-256 signature for webhook payload.
     */
    public boolean verifySignature(String payload, String signature) {
        if (signatureKey == null || signatureKey.isBlank()) {
            log.warn("Webhook signature key not configured, skipping verification");
            return true; // Allow in development
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    signatureKey.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computedSignature = Base64.getEncoder().encodeToString(hash);

            return computedSignature.equals(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Error verifying webhook signature", e);
            return false;
        }
    }
}

