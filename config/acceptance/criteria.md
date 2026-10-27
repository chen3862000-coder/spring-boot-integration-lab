# Acceptance Criteria Harness

Run `make verify` before claiming the MVP meets the project plan.

Required evidence:

- `./mvnw clean verify` passes on JDK 21 with Docker available for Testcontainers MySQL.
- GitHub Actions `compose-smoke` starts the actual Compose images and verifies normal fulfillment plus timeout-after-commit reconciliation against the real mock partner.
- Checkstyle passes for size, method length, complexity, coupling, imports, and maintainability rules.
- PMD CPD reports no duplicate blocks above 80 tokens.
- JaCoCo class coverage is at least 70% per module.
- JaCoCo method coverage is at least 70% per module.
- Integration tests demonstrate normal fulfillment, duplicate idempotency, payload conflict, concurrent duplicate safety, retry behavior, non-retry behavior, timeout reconciliation, correlation propagation, and metrics exposure.
- `docker compose up --build` starts `integration-gateway`, `mock-partner`, and `mysql` before demo acceptance is claimed.
- k6 smoke/load scripts are run only when performance evidence is being reported; do not invent performance results.
- Partner `429`, gateway outbound limiter `429`, open-circuit `503`, exhausted partner `5xx` `502`, and ambiguous timeout `202 UNKNOWN` semantics are covered by tests.

Environment note: this repository's integration tests require Docker because gateway tests use Testcontainers MySQL.
