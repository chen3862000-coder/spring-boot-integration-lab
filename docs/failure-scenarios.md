# Failure Scenarios

Start with `docker compose up --build`. The local Compose `demo` profile enables `X-Partner-Fault` forwarding through the gateway; the default application profile ignores the header.

- `HTTP_500`: verifies bounded retry and circuit breaker accounting.
- `HTTP_429`: verifies explicit retry classification for partner rate limiting.
- `SLOW_RESPONSE`: verifies read timeout behavior when configured below the partner delay.
- `TIMEOUT_AFTER_COMMIT`: verifies that the gateway does not create duplicate partner business effects after an ambiguous timeout.
- `SERVICE_UNAVAILABLE`: verifies fast dependency failure handling.

Timeout-after-commit is the most important scenario: the partner may process the write, but the gateway may not receive the response. The gateway preserves ambiguity and attempts status reconciliation instead of inventing a definitive failure.
