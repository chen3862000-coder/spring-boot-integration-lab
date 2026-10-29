# Case Study: Spring Boot Integration Lab

## 1. The Problem

External APIs fail in ways that simple CRUD examples rarely cover. They can respond slowly, return transient errors, enforce rate limits, or process a write while the caller times out waiting for the response.

This project demonstrates how a backend service can protect a merchant-facing API from those failures without claiming impossible distributed exactly-once guarantees.

## 2. Architecture Overview

The system contains an Integration Gateway, a Mock Partner API, and MySQL. The gateway exposes the merchant API and owns idempotency, resilience, and observability. The partner is isolated behind HTTP and simulates real external-provider failure modes.

## 3. Reliability Challenges

The key challenges are duplicate client retries, ambiguous write outcomes, transient partner errors, partner rate limits, and preserving useful diagnostic visibility under failure.

## 4. Engineering Decisions

MySQL stores idempotency records so duplicate requests and concurrent submissions are handled with database constraints. Resilience4j centralizes retry, circuit breaker, and rate limiting policy. The mock partner implements idempotency to demonstrate the external contract required for safe write retries.

## 5. Implementation

The gateway validates fulfillment requests, atomically registers an idempotency key, calls the partner outside the database transaction, and stores the confirmed or unknown outcome. Duplicate requests with the same payload return persisted state. Duplicate keys with different payloads return HTTP 409.

## 6. Failure Scenario Demonstrations

Deterministic fault injection supports HTTP 500, HTTP 429, slow responses, service unavailable responses, and timeout-after-commit. The timeout-after-commit scenario demonstrates why a caller must distinguish confirmed failure from unknown execution state.

## 7. Testing and Performance Results

Automated tests cover core reliability behavior using Spring Boot Test, Testcontainers MySQL, and WireMock. k6 smoke, baseline, overload, and fault-profile measurements from a local run are recorded in [Performance Results](performance-results.md), including environment, throughput, p95/p99 latency, HTTP failure rate, and limiter responses. The same document records a four-replica cluster run that handles 20 VUs with zero HTTP failures and zero limiter rejections. These short local runs demonstrate behavior and are not production capacity claims.

## 8. Production Trade-offs

Production extensions would include authentication, durable partner-side idempotency, distributed rate limiting, reconciliation workers, alerting, and possibly distributed tracing. These are intentionally excluded from the reference implementation to keep the reliability patterns clear.

## 9. Business Value

The project shows how to reduce duplicate business effects, isolate external dependency failures, improve operational diagnosis, and design honest consistency semantics for API integrations.
