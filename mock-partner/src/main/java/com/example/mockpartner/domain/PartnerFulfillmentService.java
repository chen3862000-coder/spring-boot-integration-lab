package com.example.mockpartner.domain;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory partner simulator with idempotent writes; all state is lost when the process restarts. */
@Service
public class PartnerFulfillmentService {
    private final ConcurrentMap<String, PartnerFulfillment> byIdempotencyKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PartnerFulfillment> byPartnerRequestId = new ConcurrentHashMap<>();
    private final AtomicInteger logicalOperations = new AtomicInteger();

    /**
     * Processes one logical partner operation, returning the first result stored for this idempotency key.
     *
     * <p>Repeated keys do not create another logical operation; the original result is returned.
     */
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

    /** Finds a simulated partner operation by partner request ID. */
    public Optional<PartnerFulfillment> find(String partnerRequestId) {
        return Optional.ofNullable(byPartnerRequestId.get(partnerRequestId));
    }

    /** Returns the number of unique idempotency keys processed since startup. */
    public int logicalOperations() {
        return logicalOperations.get();
    }

    public record PartnerRequest(String partnerRequestId, String orderId, String sku, int quantity) {
    }
}
