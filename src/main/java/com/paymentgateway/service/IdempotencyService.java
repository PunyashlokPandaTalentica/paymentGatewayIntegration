package com.paymentgateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class IdempotencyService {
    private final Map<String, Object> idempotencyStore = new ConcurrentHashMap<>();

    /**
     * Generates a hash of the request body for idempotency checking.
     */
    public String hashRequestBody(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(body.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to hash request body", e);
        }
    }

    /**
     * Creates a composite idempotency key from key, endpoint, and body hash.
     */
    public String createCompositeKey(String idempotencyKey, String endpoint, String bodyHash) {
        return idempotencyKey + ":" + endpoint + ":" + bodyHash;
    }

    /**
     * Stores a response for idempotency.
     */
    public void storeResponse(String compositeKey, Object response) {
        idempotencyStore.put(compositeKey, response);
    }

    /**
     * Retrieves a stored response if it exists.
     */
    @SuppressWarnings("unchecked")
    public <T> T getStoredResponse(String compositeKey, Class<T> responseType) {
        Object stored = idempotencyStore.get(compositeKey);
        if (stored != null && responseType.isInstance(stored)) {
            return (T) stored;
        }
        return null;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}

