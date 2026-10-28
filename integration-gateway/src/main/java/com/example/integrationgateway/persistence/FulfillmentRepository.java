package com.example.integrationgateway.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persists gateway fulfillment state and arbitrates idempotency-key ownership in MySQL. */
@Repository
public class FulfillmentRepository {

    private final JdbcClient jdbcClient;

    public FulfillmentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Registers a new submission or returns its existing idempotency record.
     *
     * <p>{@link Registration#created()} means this caller owns execution, including a successful reclaim of an
     * eligible retryable failure. {@link Registration#samePayload()} is false when the key was used with another
     * request hash. A database unique constraint arbitrates concurrent claims.
     */
    @Transactional
    public Registration register(String idempotencyKey, String requestHash, String orderId, String sku, int quantity) {
        String requestId = "REQ-" + UUID.randomUUID();
        try {
            jdbcClient.sql("""
                    insert into fulfillment_requests
                        (idempotency_key, request_hash, request_id, order_id, sku, quantity, status, message)
                    values (:idempotencyKey, :requestHash, :requestId, :orderId, :sku, :quantity, 'PROCESSING',
                            'Registered for partner execution')
                    """)
                    .param("idempotencyKey", idempotencyKey)
                    .param("requestHash", requestHash)
                    .param("requestId", requestId)
                    .param("orderId", orderId)
                    .param("sku", sku)
                    .param("quantity", quantity)
                    .update();
            return new Registration(findByIdempotencyKey(idempotencyKey).orElseThrow(), true, true);
        } catch (DuplicateKeyException ex) {
            FulfillmentRecord existing = findByIdempotencyKey(idempotencyKey).orElseThrow();
            boolean samePayload = requestHash.equals(existing.requestHash());
            if (samePayload && isRetryableFailure(existing)) {
                int reclaimed = jdbcClient.sql("""
                        update fulfillment_requests
                        set status = 'PROCESSING', failure_code = null, message = 'Retrying after transient dependency failure'
                        where idempotency_key = :idempotencyKey and status = 'RETRYABLE_FAILURE'
                          and failure_code in ('OUTBOUND_RATE_LIMITED', 'CIRCUIT_OPEN', 'PARTNER_RATE_LIMITED')
                        """)
                        .param("idempotencyKey", idempotencyKey)
                        .update();
                if (reclaimed == 1) {
                    return new Registration(findByIdempotencyKey(idempotencyKey).orElseThrow(), true, true);
                }
                existing = findByIdempotencyKey(idempotencyKey).orElseThrow();
            }
            return new Registration(existing, false, samePayload);
        }
    }

    private boolean isRetryableFailure(FulfillmentRecord record) {
        return record.status() == FulfillmentStatus.RETRYABLE_FAILURE
                && FailureCode.isRetryable(record.failureCode());
    }

    /** Finds the persisted record associated with an idempotency key. */
    public Optional<FulfillmentRecord> findByIdempotencyKey(String idempotencyKey) {
        return jdbcClient.sql("select * from fulfillment_requests where idempotency_key = :idempotencyKey")
                .param("idempotencyKey", idempotencyKey)
                .query(this::map)
                .optional();
    }

    /** Finds the persisted record associated with a gateway request ID. */
    public Optional<FulfillmentRecord> findByRequestId(String requestId) {
        return jdbcClient.sql("select * from fulfillment_requests where request_id = :requestId")
                .param("requestId", requestId)
                .query(this::map)
                .optional();
    }

    /** Updates the recorded partner outcome and returns the resulting persisted state. */
    @Transactional
    public FulfillmentRecord updateResult(String requestId, FulfillmentStatus status, String partnerRequestId,
                                          String partnerReference, String failureCode, String responseBody, String message) {
        int updated = jdbcClient.sql("""
                update fulfillment_requests
                set status = :status,
                    partner_request_id = coalesce(:partnerRequestId, partner_request_id),
                    partner_reference = :partnerReference,
                    failure_code = :failureCode,
                    response_body = :responseBody,
                    message = :message,
                    updated_at = current_timestamp(6)
                where request_id = :requestId
                """)
                .param("requestId", requestId)
                .param("status", status.name())
                .param("partnerRequestId", partnerRequestId)
                .param("partnerReference", partnerReference)
                .param("failureCode", failureCode)
                .param("responseBody", responseBody)
                .param("message", message)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Expected one fulfillment row for requestId " + requestId);
        }
        return findByRequestId(requestId).orElseThrow();
    }

    private FulfillmentRecord map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new FulfillmentRecord(
                rs.getLong("id"),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getString("request_id"),
                rs.getString("order_id"),
                rs.getString("sku"),
                rs.getInt("quantity"),
                FulfillmentStatus.valueOf(rs.getString("status")),
                rs.getString("partner_request_id"),
                rs.getString("partner_reference"),
                rs.getString("failure_code"),
                rs.getString("response_body"),
                rs.getString("message"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** Result of idempotency registration; {@code created} denotes execution ownership, not only row insertion. */
    public record Registration(FulfillmentRecord record, boolean created, boolean samePayload) {
    }
}
