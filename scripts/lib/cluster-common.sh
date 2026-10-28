#!/usr/bin/env bash
# Shared helpers for the native local cluster scripts.
# This file is sourced by the start/stop/status scripts.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RUN_DIR="${ROOT_DIR}/.run"
LOG_DIR="${RUN_DIR}/logs"
PID_DIR="${RUN_DIR}/pids"

if [[ -f "${ROOT_DIR}/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    source "${ROOT_DIR}/.env"
    set +a
fi

GATEWAY_PORT="${GATEWAY_PORT:-8080}"
PARTNER_PORT="${PARTNER_PORT:-8081}"
PROMETHEUS_PORT="${PROMETHEUS_PORT:-9090}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_DATABASE="${MYSQL_DATABASE:-integration_lab}"
MYSQL_USER="${MYSQL_USER:-integration}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-integration_local_only}"
MYSQL_ROOT_USER="${MYSQL_ROOT_USER:-root}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-}"
SPRING_PROFILE="${SPRING_PROFILE:-demo}"

PID_MOCK_PARTNER="${PID_DIR}/mock-partner.pid"
PID_GATEWAY="${PID_DIR}/integration-gateway.pid"
PID_PROMETHEUS="${PID_DIR}/prometheus.pid"

LOG_MOCK_PARTNER="${LOG_DIR}/mock-partner.log"
LOG_GATEWAY="${LOG_DIR}/integration-gateway.log"
LOG_PROMETHEUS="${LOG_DIR}/prometheus.log"

log() {
    printf '==> %s\n' "$*"
}

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "Required command '$1' not found."
}

setup_java() {
    if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == "Darwin" && -x /usr/libexec/java_home ]]; then
        JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
        [[ -n "$JAVA_HOME" ]] && export JAVA_HOME
    fi

    if [[ -n "${JAVA_HOME:-}" ]]; then
        JAVA_BIN="${JAVA_HOME}/bin/java"
    else
        JAVA_BIN="${JAVA_BIN:-java}"
    fi

    [[ -x "$JAVA_BIN" ]] || die "Java not found. Set JAVA_HOME to a JDK 21 installation."

    local version
    version="$("$JAVA_BIN" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
    case "$version" in
        21.*) ;;
        *) die "JDK 21 required; found '${version:-unknown}'. Set JAVA_HOME to a JDK 21 installation." ;;
    esac

    export JAVA_HOME
}

process_running() {
    local pidfile="$1"
    [[ -f "$pidfile" ]] || return 1
    local pid
    pid="$(cat "$pidfile" 2>/dev/null || true)"
    [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null
}

start_process() {
    local name="$1"
    local pidfile="$2"
    local logfile="$3"
    shift 3

    if process_running "$pidfile"; then
        log "${name} is already running (PID $(cat "$pidfile"))"
        return 0
    fi

    rm -f "$pidfile"
    log "Starting ${name}"
    (
        cd "$ROOT_DIR"
        nohup "$@" >"$logfile" 2>&1 &
        echo $! >"$pidfile"
    )
}

stop_process() {
    local name="$1"
    local pidfile="$2"

    if ! process_running "$pidfile"; then
        log "${name} is not running"
        rm -f "$pidfile"
        return 0
    fi

    local pid
    pid="$(cat "$pidfile")"
    log "Stopping ${name} (PID ${pid})"
    kill -TERM "$pid" 2>/dev/null || true

    local i
    for ((i = 1; i <= 30; i++)); do
        if ! kill -0 "$pid" 2>/dev/null; then
            break
        fi
        sleep 1
    done

    if kill -0 "$pid" 2>/dev/null; then
        log "${name} did not stop gracefully; killing it"
        kill -KILL "$pid" 2>/dev/null || true
    fi
    rm -f "$pidfile"
}

assert_port_available() {
    local port="$1"
    local name="$2"

    if command -v lsof >/dev/null 2>&1; then
        local owner
        owner="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | head -n 1 || true)"
        [[ -z "$owner" ]] || die "Port ${port} is already in use by PID ${owner}; cannot start ${name}."
    fi
}

wait_for_health() {
    local name="$1"
    local url="$2"
    local pidfile="$3"
    local timeout="${4:-90}"

    local i
    for ((i = 1; i <= timeout; i++)); do
        if ! process_running "$pidfile"; then
            return 1
        fi
        if curl -fsS "$url" >/dev/null 2>&1; then
            log "${name} is healthy"
            return 0
        fi
        sleep 1
    done
    return 1
}

show_log_tail() {
    local logfile="$1"
    [[ -f "$logfile" ]] || return 0
    printf '\n--- last lines of %s ---\n' "$logfile" >&2
    tail -n 40 "$logfile" >&2 || true
}

mysql_ping() {
    command -v mysqladmin >/dev/null 2>&1 || return 1
    mysqladmin --protocol=tcp -h "$MYSQL_HOST" -P "$MYSQL_PORT" ping --silent >/dev/null 2>&1
}

ensure_mysql() {
    if mysql_ping; then
        log "MySQL is reachable at ${MYSQL_HOST}:${MYSQL_PORT}"
        return 0
    fi

    log "MySQL is not responding at ${MYSQL_HOST}:${MYSQL_PORT}"
    local started=0
    if command -v brew >/dev/null 2>&1; then
        local formula
        for formula in mysql mysql@8.4; do
            if brew list --versions "$formula" >/dev/null 2>&1; then
                log "Starting Homebrew service ${formula}"
                brew services start "$formula" >/dev/null
                started=1
                break
            fi
        done
    fi

    if [[ "$started" -eq 0 ]]; then
        die "MySQL is not running. Install and start MySQL 8.4, then retry. See docs/local-cluster.md."
    fi

    local i
    for ((i = 1; i <= 30; i++)); do
        if mysql_ping; then
            log "MySQL is ready"
            return 0
        fi
        sleep 1
    done
    die "MySQL did not become ready within 30 seconds. Check its service logs."
}

run_mysql_root() {
    if [[ -n "$MYSQL_ROOT_PASSWORD" ]]; then
        MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$@"
    else
        "$@"
    fi
}

validate_identifier() {
    [[ "$1" =~ ^[A-Za-z0-9_]+$ ]] || die "Invalid MySQL identifier: $1"
}

mysql_app_ready() {
    MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=tcp -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" \
        -e "USE \`${MYSQL_DATABASE}\`" >/dev/null 2>&1
}

provision_database() {
    require_command mysql
    validate_identifier "$MYSQL_DATABASE"
    validate_identifier "$MYSQL_USER"

    local -a mysql_cmd=(mysql --protocol=tcp -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_ROOT_USER")
    if ! run_mysql_root "${mysql_cmd[@]}" -e "SELECT 1" >/dev/null 2>&1; then
        if mysql_app_ready; then
            log "Using existing database ${MYSQL_DATABASE} with user ${MYSQL_USER}"
            return 0
        fi
        die "Cannot connect to MySQL as '${MYSQL_ROOT_USER}'. Set MYSQL_ROOT_PASSWORD, or create database '${MYSQL_DATABASE}' and user '${MYSQL_USER}' manually."
    fi

    local escaped_password="$MYSQL_PASSWORD"
    escaped_password="${escaped_password//\\/\\\\}"
    escaped_password="${escaped_password//\'/\'\'}"

    run_mysql_root "${mysql_cmd[@]}" <<SQL
CREATE DATABASE IF NOT EXISTS \`${MYSQL_DATABASE}\` CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS '${MYSQL_USER}'@'localhost' IDENTIFIED BY '${escaped_password}';
CREATE USER IF NOT EXISTS '${MYSQL_USER}'@'127.0.0.1' IDENTIFIED BY '${escaped_password}';
ALTER USER '${MYSQL_USER}'@'localhost' IDENTIFIED BY '${escaped_password}';
ALTER USER '${MYSQL_USER}'@'127.0.0.1' IDENTIFIED BY '${escaped_password}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'localhost';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

    log "Database ${MYSQL_DATABASE} and user ${MYSQL_USER} are ready"
}

find_module_jar() {
    local module="$1"
    local target_dir="${ROOT_DIR}/${module}/target"
    [[ -d "$target_dir" ]] || die "Build output for ${module} not found. Run without --skip-build."

    local jar
    jar="$(find "$target_dir" -maxdepth 1 -type f -name "${module}-*.jar" 2>/dev/null | head -n 1 || true)"
    [[ -n "$jar" ]] || die "No jar found for ${module}. Run without --skip-build."
    printf '%s\n' "$jar"
}

build_apps() {
    log "Building application jars"
    (
        cd "$ROOT_DIR"
        ./mvnw -B -DskipTests package
    )
}
