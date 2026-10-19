# Performance Results

No benchmark numbers are committed because performance depends on the local machine, Docker resources, JVM warmup, and current resilience settings.

Run:

```bash
BASE_URL=http://localhost:8080 VUS=20 DURATION=60s k6 run performance/load.js
```

Record results here with machine details, Docker CPU/memory limits, request mix, latency percentiles, error rate, and observed HTTP 429 rate.
