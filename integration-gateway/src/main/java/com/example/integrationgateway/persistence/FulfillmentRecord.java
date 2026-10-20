package com.example.integrationgateway.persistence;

import java.time.Instant;

public record FulfillmentRecord(
        long id,
        String idempotencyKey,
        String requestHash,
        String requestId,
        String orderId,
        String sku,
        int quantity,
        FulfillmentStatus status,
        String partnerRequestId,
        String partnerReference,
        String failureCode,
        String responseBody,
        String message,
        Instant createdAt,
        Instant updatedAt) {
}
