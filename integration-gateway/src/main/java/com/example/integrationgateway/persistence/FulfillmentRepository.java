package com.example.integrationgateway.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class FulfillmentRepository {

    private final JdbcClient jdbcClient;

    public FulfillmentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public Registration register(String idempotencyKey, String requestHash, String orderId, String sku, int quantity) {
        String requestId = "REQ-" + UUID.randomUUID();
        int inserted = jdbcClient.sql("""
                insert ignore into fulfillment_requests
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
        FulfillmentRecord existing = findByIdempotencyKey(idempotencyKey).orElseThrow();
        if (inserted == 1) {
            return new Registration(existing, true, true);
        }
        boolean samePayload = requestHash.equals(existing.requestHash());
        if (samePayload && "OUTBOUND_RATE_LIMITED".equals(existing.failureCode())) {
            int reclaimed = jdbcClient.sql("""
                    update fulfillment_requests
                    set status = 'PROCESSING', failure_code = null, message = 'Retrying after outbound rate limit'
                    where idempotency_key = :idempotencyKey and status = 'FAILED'
                      and failure_code = 'OUTBOUND_RATE_LIMITED'
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

    public Optional<FulfillmentRecord> findByIdempotencyKey(String idempotencyKey) {
        return jdbcClient.sql("select * from fulfillment_requests where idempotency_key = :idempotencyKey")
                .param("idempotencyKey", idempotencyKey)
                .query(this::map)
                .optional();
    }

    public Optional<FulfillmentRecord> findByRequestId(String requestId) {
        return jdbcClient.sql("select * from fulfillment_requests where request_id = :requestId")
                .param("requestId", requestId)
                .query(this::map)
                .optional();
    }

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

    public record Registration(FulfillmentRecord record, boolean created, boolean samePayload) {
    }
}
