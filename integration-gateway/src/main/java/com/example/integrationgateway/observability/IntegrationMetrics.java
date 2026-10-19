package com.example.integrationgateway.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class IntegrationMetrics {
    private final Counter totalRequests;
    private final Counter successfulRequests;
    private final Counter failedRequests;
    private final Counter idempotencyHits;
    private final Counter idempotencyConflicts;
    private final Counter unknownOutcomes;

    public IntegrationMetrics(MeterRegistry registry) {
        this.totalRequests = counter(registry, "integration_requests_total", "Total gateway requests");
        this.successfulRequests = counter(registry, "integration_requests_success_total", "Confirmed successful requests");
        this.failedRequests = counter(registry, "integration_requests_failed_total", "Confirmed failed requests");
        this.idempotencyHits = counter(registry, "integration_idempotency_hits_total", "Duplicate requests served from persisted state");
        this.idempotencyConflicts = counter(registry, "integration_idempotency_conflicts_total", "Idempotency key payload conflicts");
        this.unknownOutcomes = counter(registry, "integration_unknown_outcomes_total", "Ambiguous partner execution outcomes");
    }

    private Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    public Counter totalRequests() {
        return totalRequests;
    }

    public Counter successfulRequests() {
        return successfulRequests;
    }

    public Counter failedRequests() {
        return failedRequests;
    }

    public Counter idempotencyHits() {
        return idempotencyHits;
    }

    public Counter idempotencyConflicts() {
        return idempotencyConflicts;
    }

    public Counter unknownOutcomes() {
        return unknownOutcomes;
    }
}
