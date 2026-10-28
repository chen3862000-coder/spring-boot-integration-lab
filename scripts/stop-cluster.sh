#!/usr/bin/env bash
# Stops the native cluster processes.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/cluster-common.sh
source "${SCRIPT_DIR}/lib/cluster-common.sh"

STOP_MYSQL=0

usage() {
    cat <<'EOF'
Usage: scripts/stop-cluster.sh [options]

Stops integration-gateway, mock-partner, and Prometheus. MySQL is left
running by default because it may be shared with other local projects.

Options:
  --stop-mysql  Stop the Homebrew-managed MySQL service as well.
  -h, --help    Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --stop-mysql) STOP_MYSQL=1 ;;
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

stop_process "integration-gateway" "$PID_GATEWAY"
stop_process "mock-partner" "$PID_MOCK_PARTNER"
stop_process "prometheus" "$PID_PROMETHEUS"

if [[ "$STOP_MYSQL" -eq 1 ]]; then
    if ! mysql_ping; then
        log "MySQL is not running"
    else
        formula=""
        if command -v brew >/dev/null 2>&1; then
            for candidate in mysql mysql@8.4; do
                if brew list --versions "$candidate" >/dev/null 2>&1; then
                    formula="$candidate"
                    break
                fi
            done
        fi
        if [[ -z "$formula" ]]; then
            die "MySQL is running but is not managed by Homebrew. Stop it with your service manager."
        fi
        log "Stopping Homebrew service ${formula}"
        brew services stop "$formula" >/dev/null
    fi
fi

log "Cluster stopped"
