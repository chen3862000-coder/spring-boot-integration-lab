package com.example.integrationgateway.application;

import com.example.integrationgateway.api.FulfillmentController.FulfillmentRequest;
import com.example.integrationgateway.client.PartnerClient;
import com.example.integrationgateway.client.PartnerClient.AmbiguousPartnerException;
import com.example.integrationgateway.client.PartnerClient.PartnerResponse;
import com.example.integrationgateway.observability.IntegrationMetrics;
import com.example.integrationgateway.persistence.FulfillmentRecord;
import com.example.integrationgateway.persistence.FulfillmentRepository;
import com.example.integrationgateway.persistence.FulfillmentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class FulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentService.class);

    private final FulfillmentRepository repository;
    private final PartnerClient partnerClient;
    private final IntegrationMetrics metrics;
    private final ObjectMapper objectMapper;

    public FulfillmentService(FulfillmentRepository repository, PartnerClient partnerClient,
                              IntegrationMetrics metrics, ObjectMapper objectMapper) {
        this.repository = repository;
        this.partnerClient = partnerClient;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    public SubmitResult submit(String idempotencyKey, String partnerFault, FulfillmentRequest request) {
        metrics.totalRequests().increment();
        String requestHash = hash(request);
        FulfillmentRepository.Registration registration = repository.register(
                idempotencyKey, requestHash, request.orderId(), request.sku(), request.quantity());

        if (!registration.samePayload()) {
            metrics.idempotencyConflicts().increment();
            throw new IdempotencyConflictException("Idempotency-Key was already used with a different payload");
        }
        if (!registration.created()) {
            metrics.idempotencyHits().increment();
            return new SubmitResult(registration.record(), false);
        }

        FulfillmentRecord record = registration.record();
        try {
            PartnerResponse partnerResponse = partnerClient.createFulfillment(record, idempotencyKey, partnerFault);
            FulfillmentRecord updated = repository.updateResult(
                    record.requestId(),
                    FulfillmentStatus.COMPLETED,
                    partnerResponse.partnerRequestId(),
                    partnerResponse.partnerReference(),
                    toJson(partnerResponse),
                    "Partner confirmed fulfillment");
            metrics.successfulRequests().increment();
            return new SubmitResult(updated, true);
        } catch (AmbiguousPartnerException ex) {
            FulfillmentRecord reconciled = reconcile(record, idempotencyKey, ex);
            return new SubmitResult(reconciled, true);
        } catch (PartnerUnavailableException ex) {
            FulfillmentRecord updated = repository.updateResult(
                    record.requestId(), FulfillmentStatus.UNKNOWN, record.requestId(), null, null,
                    "Partner unavailable; execution outcome is unknown");
            metrics.unknownOutcomes().increment();
            log.warn("Partner unavailable for requestId={}", record.requestId(), ex);
            return new SubmitResult(updated, true);
        } catch (RuntimeException ex) {
            FulfillmentRecord updated = repository.updateResult(
                    record.requestId(), FulfillmentStatus.FAILED, null, null, null,
                    "Partner rejected fulfillment: " + ex.getMessage());
            metrics.failedRequests().increment();
            return new SubmitResult(updated, true);
        }
    }

    public Optional<FulfillmentRecord> find(String requestId) {
        return repository.findByRequestId(requestId);
    }

    private FulfillmentRecord reconcile(FulfillmentRecord record, String idempotencyKey, AmbiguousPartnerException ex) {
        try {
            PartnerResponse status = partnerClient.getFulfillment(record.requestId(), idempotencyKey);
            metrics.successfulRequests().increment();
            return repository.updateResult(record.requestId(), FulfillmentStatus.COMPLETED,
                    status.partnerRequestId(), status.partnerReference(), toJson(status),
                    "Reconciled after ambiguous partner timeout");
        } catch (RuntimeException reconciliationFailure) {
            metrics.unknownOutcomes().increment();
            log.warn("Could not reconcile ambiguous requestId={}", record.requestId(), reconciliationFailure);
            return repository.updateResult(record.requestId(), FulfillmentStatus.UNKNOWN,
                    record.requestId(), null, null, ex.getMessage());
        }
    }

    private String hash(FulfillmentRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = objectMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException | JacksonException ex) {
            throw new IllegalStateException("Could not hash fulfillment request", ex);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            return new String(value.toString().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
    }
}
