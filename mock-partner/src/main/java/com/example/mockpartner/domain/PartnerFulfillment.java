package com.example.mockpartner.domain;

import java.time.Instant;

public record PartnerFulfillment(
        String partnerRequestId,
        String orderId,
        String sku,
        int quantity,
        String status,
        String partnerReference,
        Instant createdAt) {
}
