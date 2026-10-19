package com.example.integrationgateway.persistence;

public enum FulfillmentStatus {
    PROCESSING,
    COMPLETED,
    FAILED,
    UNKNOWN;

    public boolean isTerminalSuccess() {
        return this == COMPLETED;
    }
}
