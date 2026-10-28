# Performance Results

> These are short, single-run local measurements used to demonstrate limiter and failure behavior. They are not SLOs, production capacity benchmarks, or statistically repeatable estimates.

## Local Run (2026-10-21)

Environment: Apple Silicon developer laptop (10 vCPU, 24 GiB RAM), macOS, Docker Desktop 29.9.1, k6 2.3.0. The Compose stack ran with default Docker Desktop resource allocation and no explicit per-container CPU or memory limits. Each profile ran once for 30 seconds against an already-running local stack.

## Reproducing the Run

Run the 20-VU overload profile explicitly. Without `PROFILE=overload`, the script selects the baseline threshold.

```bash
BASE_URL=http://localhost:8080 PROFILE=overload VUS=20 DURATION=60s k6 run performance/load.js
```

Profiles are `baseline` (5 VUs), `overload` (20 VUs), and `fault` (5 VUs with injected partner 500s). Specify the profile explicitly when overriding VUs because each profile has different error-rate thresholds.

```bash
BASE_URL=http://localhost:8080 PROFILE=baseline VUS=5 DURATION=60s k6 run performance/load.js
BASE_URL=http://localhost:8080 PROFILE=fault VUS=5 DURATION=60s k6 run performance/load.js
```

Record the date, CPU/RAM, OS, Java version, Docker resource limits, profile, virtual users, duration, throughput, p95/p99 latency, error rate, and gateway HTTP 429 count when publishing results.

## Results

| Profile | VUs | Duration | Requests | Throughput | p95 | p99 | HTTP failure rate | Gateway 429 rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Baseline | 5 | 30s | 150 | 4.91 req/s | 29.31 ms | 36.41 ms | 0.00% | 0.00% |
| Overload | 20 | 30s | 600 | 19.56 req/s | 34.29 ms | 37.19 ms | 30.00% | 30.00% |
| Fault | 5 | 30s | 144 | 4.69 req/s | 633.66 ms | 648.85 ms | 7.63% | 0.00% |

The overload profile intentionally exceeds the configured 10 outbound attempts/second quota; its 30% HTTP failure rate consisted of gateway `429` rate-limit responses. The fault profile injects HTTP 500 in approximately 10% of iterations; observed HTTP failures also include breaker behavior. Each run passed its configured profile thresholds.
