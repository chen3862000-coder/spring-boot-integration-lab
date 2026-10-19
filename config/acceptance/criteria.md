# Acceptance Criteria Harness

Run `make verify` before claiming the MVP meets the project plan.

Required evidence:

- `./mvnw clean verify` passes on JDK 27 with Docker available for Testcontainers MySQL.
- Checkstyle passes for method length, complexity, coupling, imports, and basic maintainability rules.
- Checkstyle passes for source-level maintainability gates including size, complexity, imports, and coupling.
- PMD CPD reports no duplicate blocks above 80 tokens.
- JaCoCo class coverage is at least 70% per module.
- JaCoCo method coverage is at least 70% per module.
- Integration tests demonstrate normal fulfillment, duplicate idempotency, payload conflict, concurrent duplicate safety, retry behavior, non-retry behavior, timeout reconciliation, correlation propagation, and metrics exposure.
- `docker compose up --build` starts `integration-gateway`, `mock-partner`, and `mysql` before demo acceptance is claimed.
- k6 smoke/load scripts are run only when performance evidence is being reported; do not invent performance results.

Environment note: this repository's integration tests require Docker because gateway tests use Testcontainers MySQL.
