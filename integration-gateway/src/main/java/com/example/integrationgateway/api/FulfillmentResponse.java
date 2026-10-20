package com.example.integrationgateway.api;

import com.example.integrationgateway.persistence.FulfillmentRecord;
import com.example.integrationgateway.persistence.FulfillmentStatus;

import java.time.Instant;

public record FulfillmentResponse(
        String requestId,
        String orderId,
        FulfillmentStatus status,
        String partnerReference,
        String failureCode,
        String message,
        Instant updatedAt) {

    public static FulfillmentResponse from(FulfillmentRecord record) {
        return new FulfillmentResponse(
                record.requestId(),
                record.orderId(),
                record.status(),
                record.partnerReference(),
                record.failureCode(),
                record.message(),
                record.updatedAt());
    }
}
