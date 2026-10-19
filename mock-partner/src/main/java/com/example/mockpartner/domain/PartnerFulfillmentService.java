package com.example.mockpartner.domain;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class PartnerFulfillmentService {
    private final ConcurrentMap<String, PartnerFulfillment> byIdempotencyKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PartnerFulfillment> byPartnerRequestId = new ConcurrentHashMap<>();
    private final AtomicInteger logicalOperations = new AtomicInteger();

    public PartnerFulfillment process(String idempotencyKey, PartnerRequest request) {
        return byIdempotencyKey.computeIfAbsent(idempotencyKey, key -> {
            logicalOperations.incrementAndGet();
            PartnerFulfillment fulfillment = new PartnerFulfillment(
                    request.partnerRequestId(),
                    request.orderId(),
                    request.sku(),
                    request.quantity(),
                    "COMPLETED",
                    "PARTNER-" + UUID.randomUUID(),
                    Instant.now());
            byPartnerRequestId.put(fulfillment.partnerRequestId(), fulfillment);
            return fulfillment;
        });
    }

    public Optional<PartnerFulfillment> find(String partnerRequestId) {
        return Optional.ofNullable(byPartnerRequestId.get(partnerRequestId));
    }

    public int logicalOperations() {
        return logicalOperations.get();
    }

    public record PartnerRequest(String partnerRequestId, String orderId, String sku, int quantity) {
    }
}
