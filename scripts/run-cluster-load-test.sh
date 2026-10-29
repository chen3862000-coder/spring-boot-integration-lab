#!/usr/bin/env bash
# Starts the two-gateway Compose cluster, runs the k6 cluster profile, reports
# per-gateway request counts, and tears the cluster down.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

COMPOSE_FILE="compose.cluster.yaml"
PROFILE="${PROFILE:-cluster}"
VUS="${VUS:-20}"
DURATION="${DURATION:-60s}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
KEEP_RUNNING="${KEEP_RUNNING:-0}"

compose() {
    docker compose -f "$COMPOSE_FILE" "$@"
}

cleanup() {
    if [[ "$KEEP_RUNNING" != "1" ]]; then
        compose down -v --remove-orphans >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

command -v docker >/dev/null 2>&1 || { echo "error: docker not found" >&2; exit 1; }
command -v k6 >/dev/null 2>&1 || { echo "error: k6 not found" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "error: curl not found" >&2; exit 1; }

echo "==> Resetting cluster"
compose down -v --remove-orphans >/dev/null 2>&1 || true

echo "==> Building and starting two gateway replicas behind nginx"
compose up --build -d

echo "==> Waiting for gateway health through the load balancer"
for _ in $(seq 1 180); do
    if curl -fsS "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done
if ! curl -fsS "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
    compose ps
    compose logs --tail 80
    echo "error: cluster did not become healthy" >&2
    exit 1
fi

echo "==> Running k6 profile=${PROFILE} vus=${VUS} duration=${DURATION}"
set +e
PROFILE="$PROFILE" VUS="$VUS" DURATION="$DURATION" BASE_URL="$BASE_URL" k6 run performance/load.js
K6_EXIT=$?
set -e

echo "==> Per-gateway gateway request counters"
for port in 18080 18081 18082 18083; do
    value="$(curl -fsS "http://127.0.0.1:${port}/actuator/prometheus" \
        | awk '/^integration_requests_total / {print $2; exit}' || true)"
    printf 'gateway on port %s: integration_requests_total=%s\n' "$port" "${value:-unavailable}"
done

if [[ "$KEEP_RUNNING" == "1" ]]; then
    echo "==> Cluster left running. Stop it with: docker compose -f ${COMPOSE_FILE} down -v"
fi

exit "$K6_EXIT"
