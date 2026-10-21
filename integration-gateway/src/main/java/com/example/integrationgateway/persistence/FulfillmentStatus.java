package com.example.integrationgateway.persistence;

public enum FulfillmentStatus {
    PROCESSING,
    COMPLETED,
    FAILED,
    RETRYABLE_FAILURE,
    UNKNOWN;

    public boolean isTerminalSuccess() {
        return this == COMPLETED;
    }
}
