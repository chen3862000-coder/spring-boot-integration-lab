# Performance Results

Status: not measured in the implementation environment. No benchmark values are claimed.

Record the date, CPU/RAM, OS, Java version, Docker resource limits, profile, virtual users, duration, throughput, p95/p99 latency, error rate, and gateway HTTP 429 count after running the test.

Run:

```bash
BASE_URL=http://localhost:8080 VUS=20 DURATION=60s k6 run performance/load.js
```

Available profiles are `baseline`, `overload`, and `fault`. Use the command below to override virtual users/duration without changing the selected profile:

```bash
BASE_URL=http://localhost:8080 PROFILE=baseline VUS=5 DURATION=60s k6 run performance/load.js
```
