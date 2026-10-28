# Design Decisions

## MySQL-backed idempotency

A unique constraint on `idempotency_key` is the concurrency control mechanism. This is safer than in-memory locks because it works across threads and future gateway replicas.

The MySQL schema declares its status index inside `CREATE TABLE`, and repository updates use an update followed by a select in a short transaction.

## Conservative write retry

The gateway retries selected partner failures, but ambiguous write timeouts are not blindly retried. The mock partner supports idempotency keys so duplicate writes can be safely deduplicated when retries occur.

## Unknown outcomes

If the gateway cannot determine whether the partner processed a request, it stores `UNKNOWN`. It does not claim failure unless the failure is confirmed.

## Local rate limiter

The reference implementation uses a single-process Resilience4j rate limiter. A production multi-replica deployment would need shared quota coordination.
