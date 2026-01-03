package com.paymentgateway.domain.enums;

public enum TransactionState {
    REQUESTED,
    INITIATED,
    AUTHORIZED,
    SUCCESS,
    FAILED,
    PENDING,
    SETTLED,
    VOIDED
}

