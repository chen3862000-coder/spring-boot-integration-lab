# Agent Notes

- Maven multi-module project: root `pom.xml` builds `integration-gateway` and `mock-partner`; baseline is JDK 27 with Spring Boot 4.0.8.
- Use `./mvnw clean verify` for full verification; this downloads Maven Wrapper artifacts if `.mvn/wrapper/maven-wrapper.jar` is absent.
- `make verify` is the project harness: Maven Enforcer, Checkstyle, PMD/CPD, JaCoCo coverage gates, and tests.
- Coverage gates are JaCoCo class and method covered ratios >= 70% per module; do not claim coverage without a successful `verify` run.
- Integration tests use Testcontainers MySQL, so Docker is required for `./mvnw verify` and `make integration-test`.
- This workspace environment may not have `mvn` or `docker`; prefer `./mvnw` and report Docker-dependent checks as unexecuted if Docker is unavailable.
- Run the local demo with `docker compose up --build`; `make run` is just a wrapper for that command.
- `integration-gateway` owns public APIs, MySQL idempotency, Resilience4j policies, metrics, and partner HTTP calls.
- `mock-partner` is a separate HTTP service with in-memory idempotency only; do not claim partner deduplication survives restarts.
- Do not hold DB transactions open across partner HTTP calls; gateway idempotency ownership is established with the `fulfillment_requests.idempotency_key` unique constraint.
- Deterministic partner fault injection is via `X-Partner-Fault` values documented in `README.md` and `docs/failure-scenarios.md`.
- k6 performance scripts are opt-in under `performance/`; do not invent benchmark results in docs.
