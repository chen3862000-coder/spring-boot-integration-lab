# Performance Results

> These are short local measurements used to demonstrate limiter and failure behavior. Some profiles were repeated for reproducibility. They are not SLOs, production capacity benchmarks, or statistically repeatable estimates.

## Local Run (2026-10-21)

Environment: Apple Silicon developer laptop (10 vCPU, 24 GiB RAM), macOS, Docker Desktop 29.9.1, Docker Compose 5.6.0, k6 2.3.0. The Compose stack ran with default Docker Desktop resource allocation and no explicit per-container CPU or memory limits. Each single-instance profile ran once for 30 seconds against an already-running local stack. The horizontal-scaling runs below used 60-second profiles.

## Reproducing the Runs

Run the 20-VU overload profile explicitly. Without `PROFILE=overload`, the script selects the baseline threshold.

```bash
BASE_URL=http://localhost:8080 PROFILE=overload VUS=20 DURATION=30s k6 run performance/load.js
```

Profiles are `baseline` (5 VUs), `overload` (20 VUs), `fault` (5 VUs with injected partner 500s), and `cluster` (20 VUs, strict failure and rate-limit thresholds).

```bash
BASE_URL=http://localhost:8080 PROFILE=baseline VUS=5 DURATION=30s k6 run performance/load.js
BASE_URL=http://localhost:8080 PROFILE=fault VUS=5 DURATION=30s k6 run performance/load.js
```

Record the date, CPU/RAM, OS, Java version, Docker resource limits, profile, virtual users, duration, throughput, p95/p99 latency, error rate, and gateway HTTP 429 count when publishing results.

### Reproducing the Horizontal-Scaling Run

The cluster test starts four gateway replicas behind an nginx load balancer, with a shared MySQL instance and a single mock partner:

```bash
make cluster-load-test
# or
./scripts/run-cluster-load-test.sh
```

The script builds and starts `compose.cluster.yaml`, waits for health through the load balancer, runs the `cluster` profile for 20 VUs and 60 seconds, prints each gateway's `integration_requests_total`, and tears the cluster down. Stop the default single-instance Compose stack first so port 8080 is free; the script tears down only the cluster project. A higher-load probe and a manual inspection run are also available:

```bash
VUS=40 DURATION=60s ./scripts/run-cluster-load-test.sh
KEEP_RUNNING=1 ./scripts/run-cluster-load-test.sh
```

For manual runs, use `docker compose -f compose.cluster.yaml up --build -d` and `docker compose -f compose.cluster.yaml down -v`.

## Single-Gateway Results

| Profile | VUs | Duration | Requests | Throughput | p95 | p99 | HTTP failure rate | Gateway 429 rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Baseline | 5 | 30s | 150 | 4.91 req/s | 29.31 ms | 36.41 ms | 0.00% | 0.00% |
| Overload | 20 | 30s | 600 | 19.56 req/s | 34.29 ms | 37.19 ms | 30.00% | 30.00% |
| Fault | 5 | 30s | 144 | 4.69 req/s | 633.66 ms | 648.85 ms | 7.63% | 0.00% |

The overload profile intentionally exceeds the single gateway's configured 10 outbound attempts/second quota; its 30% HTTP failure rate consisted of gateway `429` rate-limit responses. The fault profile injects HTTP 500 in approximately 10% of iterations; observed HTTP failures also include breaker behavior. Each run passed its configured profile thresholds.

## Horizontal Scaling: Four Gateway Replicas (2026-10-29)

Topology: `gateway-a` through `gateway-d`, each with the default 10 outbound attempts/second limiter, behind an nginx round-robin load balancer. All replicas share one MySQL 8.4 instance and one mock partner. The load profile uses unique keys plus a duplicate-key pattern (two duplicates per five iterations) to exercise cross-replica idempotency.

| Run | VUs | Duration | Requests | Throughput | p95 | p99 | HTTP failure rate | Gateway 429 rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Cluster run 1 | 20 | 60s | 1140 | 18.89 req/s | 78.77 ms | 1.06 s | 0.00% | 0.00% |
| Cluster run 2 | 20 | 60s | 1140 | 18.98 req/s | 113.13 ms | 502.21 ms | 0.00% | 0.00% |
| Four-replica capacity probe | 40 | 60s | 2320 | 38.11 req/s | 117.65 ms | 630.88 ms | 6.93% | 6.93% |

Per-gateway request counters for the 20-VU runs were evenly distributed:

| Run | gateway-a | gateway-b | gateway-c | gateway-d |
|---|---:|---:|---:|---:|
| Cluster run 1 | 289 | 287 | 282 | 282 |
| Cluster run 2 | 289 | 285 | 283 | 283 |

At 20 VUs, the cluster sustained approximately 18.9 requests/second with zero HTTP failures and zero gateway `429` responses in two consecutive runs. Both runs passed the strict `cluster` thresholds: failure rate below 1%, rate-limit rate below 1%, p95 below 1 second, and throughput above 15 requests/second.

The same 20-VU profile against a single gateway produced a 30% HTTP failure rate because it exceeded that process's 10 attempts/second outbound limiter. Four replicas removed the request-tier bottleneck for this load: MySQL uniqueness coordinated idempotency across replicas, and nginx distributed requests evenly.

A two-replica and a three-replica exploratory run still produced occasional limiter rejections (3.87% and 1.12% respectively) under the synchronized one-second think-time pattern. Four replicas provided enough headroom for the repeated 20-VU target. The 40-VU probe doubled throughput to approximately 38.1 requests/second but produced 6.93% limiter rejections, showing that outbound quota and burst behavior become the next bottleneck before gateway CPU.

### Latency Breakdown (20 VUs, Four Replicas)

A separate 20-VU, 60-second diagnostic run produced 1,140 requests at 18.93 req/s with average 55.3 ms, p95 116.3 ms, p99 862.9 ms, and zero failures. No component was CPU-saturated: gateway replicas ranged from 4-23%, MySQL from 1-6%, and the mock partner from 0.4-12%.

| Measurement | Value | Interpretation |
|---|---:|---|
| Gateway inbound average | 50.7 ms | Time inside the four gateway processes |
| Hikari connection usage | ~22 ms/request | ~1.9 acquisitions/request; acquisition wait only ~0.4 ms |
| MySQL server-side statements (steady state) | ~15.1 ms/request | COMMIT ~11.0 ms, INSERT ~2.1 ms, UPDATE ~1.0 ms, SELECTs ~0.5 ms |
| MySQL COMMIT latency | 6.19 ms avg, 65.5 ms max | 2,023 commits; largest single server-side cost |
| Mock partner POST | 6.74 ms/call avg, 197.5 ms max | 900 calls; ~5.3 ms/request; low CPU |
| JVM GC pauses | 56 ms max | Not the dominant p95 contributor |
| k6 outside gateway | ~4.7 ms/request | k6 average minus gateway inbound average |

The larger measured backend cost is **MySQL**, specifically transaction commit latency, not the partner. The partner is not saturated: it served roughly 15 calls/second at about 6.7 ms average server-side latency with low CPU. The gateway connection pool was not contended: acquisition wait averaged under 0.5 ms and no pending connections were observed.

The remaining ~23 ms/request is gateway-side processing such as JSON handling, request hashing, logging, metrics, HTTP client work, Docker networking, and scheduling. Gateway CPU stayed below 25%, so this is per-request overhead rather than CPU saturation.

The single-gateway overload p95 is not directly comparable: 30% of its responses were fast `429` rejections, while the cluster run executed the full database and partner path for every request.

If the goal is lower p95, the first lever is MySQL commit and insert latency (faster storage, InnoDB durability settings, fewer commits, or avoiding duplicate insert attempts), followed by gateway per-request overhead. Adding partner replicas would not address the measured p95 bottleneck at this load.

### Interpretation and Limits

- **This is request-tier scaling.** The gateway processes are stateless apart from MySQL, and the database uniqueness constraint coordinates idempotency across replicas.
- **The limiter is process-local.** Each replica enforces its own 10 attempts/second quota, so aggregate outbound capacity scales with replica count. This is not a shared partner-wide quota. A production deployment that needs a global partner limit still requires distributed quota coordination.
- **The limiter is burst-sensitive.** The fixed-window per-process limiter can reject synchronized bursts even when the average outbound rate is below the aggregate limit. The 40-VU probe demonstrates that boundary.
- **The partner tier was not scaled.** The mock partner is a single shared instance with in-memory deduplication; partner-side dedup is not restart-safe. Scaling the partner would require durable partner idempotency.
- **Local measurement only.** These results come from one developer machine running Docker Compose. They demonstrate behavior and scaling direction, not production SLOs or capacity guarantees.
