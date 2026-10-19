# Architecture

The system has two Spring Boot services with a hard boundary between the gateway and the partner simulator.

- `integration-gateway` owns public API semantics, persisted idempotency, resilience policies, and observability.
- `mock-partner` simulates an external fulfillment API with deterministic failures and in-memory partner-side deduplication.
- MySQL stores gateway idempotency and fulfillment state.

The gateway does not share database tables or application code with the partner. HTTP is the only integration boundary.

The key consistency rule is that the database decides gateway execution ownership before any partner call is attempted. Partner calls happen outside the registration transaction to avoid holding locks across network I/O.
