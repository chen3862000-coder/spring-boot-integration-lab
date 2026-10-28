#!/usr/bin/env bash
# Starts the mock partner, MySQL-backed gateway, and optional Prometheus as
# native processes on this machine.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/cluster-common.sh
source "${SCRIPT_DIR}/lib/cluster-common.sh"

WITH_PROMETHEUS=0
SKIP_BUILD=0

usage() {
    cat <<'EOF'
Usage: scripts/start-cluster.sh [options]

Starts MySQL, mock-partner, and integration-gateway as native processes.
The MySQL server must be installed locally; the script creates the database
and application user when root access is available.

Options:
  --with-prometheus  Also start a local Prometheus instance.
  --skip-build       Reuse existing jars instead of running Maven.
  -h, --help         Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --with-prometheus) WITH_PROMETHEUS=1 ;;
        --skip-build) SKIP_BUILD=1 ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            die "Unknown option: $1 (use --help)"
            ;;
    esac
    shift
done

mkdir -p "$LOG_DIR" "$PID_DIR"
require_command curl
if [[ "$WITH_PROMETHEUS" -eq 1 ]]; then
    require_command prometheus
fi
setup_java
ensure_mysql
provision_database

if [[ "$SKIP_BUILD" -eq 0 ]]; then
    build_apps
fi

PARTNER_JAR="$(find_module_jar mock-partner)"
GATEWAY_JAR="$(find_module_jar integration-gateway)"

if process_running "$PID_MOCK_PARTNER" || process_running "$PID_GATEWAY"; then
    die "Cluster processes are already running. Run ./scripts/stop-cluster.sh first."
fi

assert_port_available "$PARTNER_PORT" "mock-partner"
assert_port_available "$GATEWAY_PORT" "integration-gateway"
if [[ "$WITH_PROMETHEUS" -eq 1 ]]; then
    assert_port_available "$PROMETHEUS_PORT" "prometheus"
fi

start_process "mock-partner" "$PID_MOCK_PARTNER" "$LOG_MOCK_PARTNER" \
    env "SPRING_PROFILES_ACTIVE=${SPRING_PROFILE}" "SERVER_PORT=${PARTNER_PORT}" \
    "$JAVA_BIN" -jar "$PARTNER_JAR"

if ! wait_for_health "mock-partner" "http://127.0.0.1:${PARTNER_PORT}/actuator/health" "$PID_MOCK_PARTNER"; then
    show_log_tail "$LOG_MOCK_PARTNER"
    stop_process "mock-partner" "$PID_MOCK_PARTNER" || true
    die "mock-partner failed to start"
fi

start_process "integration-gateway" "$PID_GATEWAY" "$LOG_GATEWAY" \
    env "SPRING_PROFILES_ACTIVE=${SPRING_PROFILE}" \
    "SERVER_PORT=${GATEWAY_PORT}" \
    "SPRING_DATASOURCE_URL=jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}?useSSL=false&allowPublicKeyRetrieval=true" \
    "SPRING_DATASOURCE_USERNAME=${MYSQL_USER}" \
    "SPRING_DATASOURCE_PASSWORD=${MYSQL_PASSWORD}" \
    "PARTNER_BASE_URL=http://127.0.0.1:${PARTNER_PORT}" \
    "$JAVA_BIN" -jar "$GATEWAY_JAR"

if ! wait_for_health "integration-gateway" "http://127.0.0.1:${GATEWAY_PORT}/actuator/health" "$PID_GATEWAY"; then
    show_log_tail "$LOG_GATEWAY"
    stop_process "integration-gateway" "$PID_GATEWAY" || true
    stop_process "mock-partner" "$PID_MOCK_PARTNER" || true
    die "integration-gateway failed to start"
fi

if [[ "$WITH_PROMETHEUS" -eq 1 ]]; then
    mkdir -p "${RUN_DIR}/prometheus-data"
    sed -e "s/__GATEWAY_PORT__/${GATEWAY_PORT}/g" \
        -e "s/__PARTNER_PORT__/${PARTNER_PORT}/g" \
        "${ROOT_DIR}/config/prometheus/prometheus-local.yml" >"${RUN_DIR}/prometheus.yml"

    start_process "prometheus" "$PID_PROMETHEUS" "$LOG_PROMETHEUS" \
        prometheus --config.file="${RUN_DIR}/prometheus.yml" \
        --storage.tsdb.path="${RUN_DIR}/prometheus-data" \
        --web.listen-address="127.0.0.1:${PROMETHEUS_PORT}"

    if ! wait_for_health "prometheus" "http://127.0.0.1:${PROMETHEUS_PORT}/-/healthy" "$PID_PROMETHEUS"; then
        show_log_tail "$LOG_PROMETHEUS"
        stop_process "prometheus" "$PID_PROMETHEUS" || true
        die "prometheus failed to start"
    fi
fi

cat <<EOF

Cluster is running:
  Gateway health:    http://127.0.0.1:${GATEWAY_PORT}/actuator/health
  Gateway API:       http://127.0.0.1:${GATEWAY_PORT}/api/fulfillments
  Partner health:    http://127.0.0.1:${PARTNER_PORT}/actuator/health
EOF

if [[ "$WITH_PROMETHEUS" -eq 1 ]]; then
    cat <<EOF
  Prometheus UI:     http://127.0.0.1:${PROMETHEUS_PORT}

EOF
fi

cat <<EOF
Logs:  ${LOG_DIR}
Stop:  ./scripts/stop-cluster.sh
EOF
