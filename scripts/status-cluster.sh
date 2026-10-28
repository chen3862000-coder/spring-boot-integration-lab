#!/usr/bin/env bash
# Reports the state of the native cluster processes.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/cluster-common.sh
source "${SCRIPT_DIR}/lib/cluster-common.sh"

usage() {
    cat <<'EOF'
Usage: scripts/status-cluster.sh

Prints process, health, port, and MySQL reachability information for the
native cluster. No options.
EOF
}

if [[ $# -gt 0 ]]; then
    case "$1" in
        -h | --help)
            usage
            exit 0
            ;;
        *)
            die "Unknown option: $1 (use --help)"
            ;;
    esac
fi

print_service() {
    local name="$1"
    local pidfile="$2"
    local port="$3"

    if ! process_running "$pidfile"; then
        printf '%-22s stopped\n' "$name"
        return
    fi

    local pid
    pid="$(cat "$pidfile")"
    if curl -fsS "http://127.0.0.1:${port}/actuator/health" >/dev/null 2>&1; then
        printf '%-22s running (PID %s), healthy on port %s\n' "$name" "$pid" "$port"
    else
        printf '%-22s running (PID %s), unhealthy on port %s\n' "$name" "$pid" "$port"
    fi
}

print_service "integration-gateway" "$PID_GATEWAY" "$GATEWAY_PORT"
print_service "mock-partner" "$PID_MOCK_PARTNER" "$PARTNER_PORT"

if process_running "$PID_PROMETHEUS"; then
    if curl -fsS "http://127.0.0.1:${PROMETHEUS_PORT}/-/healthy" >/dev/null 2>&1; then
        printf '%-22s running (PID %s), healthy on port %s\n' "prometheus" "$(cat "$PID_PROMETHEUS")" "$PROMETHEUS_PORT"
    else
        printf '%-22s running (PID %s), unhealthy on port %s\n' "prometheus" "$(cat "$PID_PROMETHEUS")" "$PROMETHEUS_PORT"
    fi
else
    printf '%-22s stopped\n' "prometheus"
fi

if mysql_ping; then
    printf '%-22s reachable at %s:%s\n' "mysql" "$MYSQL_HOST" "$MYSQL_PORT"
else
    printf '%-22s not reachable at %s:%s\n' "mysql" "$MYSQL_HOST" "$MYSQL_PORT"
fi
