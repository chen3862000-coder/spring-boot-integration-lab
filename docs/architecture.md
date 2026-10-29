# Architecture

The system has two Spring Boot services with a hard boundary between the gateway and the partner simulator.

- `integration-gateway` owns public API semantics, persisted idempotency, resilience policies, and observability.
- `mock-partner` simulates an external fulfillment API with deterministic failures and in-memory partner-side deduplication.
- MySQL stores gateway idempotency and fulfillment state.

The gateway does not share database tables or application code with the partner. HTTP is the only integration boundary.

The key consistency rule is that the database decides gateway execution ownership before any partner call is attempted. Partner calls happen outside the registration transaction to avoid holding locks across network I/O.

For load evidence, `compose.cluster.yaml` runs four gateway replicas behind an nginx round-robin load balancer, sharing one MySQL instance and one mock partner. The database uniqueness constraint coordinates idempotency across replicas. Rate limiting and circuit-breaker state remain process-local, so aggregate outbound quota scales with replica count rather than providing a shared partner-wide quota. See [Performance Results](performance-results.md) for the 20-VU cluster measurements and caveats.
