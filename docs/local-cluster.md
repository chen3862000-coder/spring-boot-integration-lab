# Running the Cluster Natively (No Docker)

The repository includes scripts that run the full cluster directly on the host machine:

- `mock-partner` as a local JVM process
- `integration-gateway` as a local JVM process
- MySQL as a locally installed service
- Prometheus optionally, as a local binary

The Docker Compose workflow remains available and is unchanged.

## What Runs

| Component | Default URL | Managed by |
|---|---|---|
| Integration Gateway | http://127.0.0.1:8080 | `scripts/start-cluster.sh` |
| Mock Partner | http://127.0.0.1:8081 | `scripts/start-cluster.sh` |
| MySQL | 127.0.0.1:3306 | Host MySQL service |
| Prometheus (optional) | http://127.0.0.1:9090 | `scripts/start-cluster.sh --with-prometheus` |

The scripts build both services, create the `integration_lab` database and `integration` user when root access is available, start the partner first, wait for health, then start the gateway.

## Prerequisites

- JDK 21. The script uses `JAVA_HOME`; on macOS it falls back to `/usr/libexec/java_home -v 21`.
- MySQL 8.4 server and client tools (`mysql`, `mysqladmin`).
- `curl`.
- Optional: `prometheus` for metrics scraping.

### macOS Setup

```bash
brew install mysql@8.4
brew services start mysql@8.4
```

Homebrew initializes MySQL with a passwordless `root` account for local use. If your installation uses a root password, export it before starting the cluster:

```bash
export MYSQL_ROOT_PASSWORD='your-root-password'
```

Optional Prometheus:

```bash
brew install prometheus
```

### Linux Setup

Install MySQL 8.4 with your distribution's package manager, start the service, and ensure you can connect as an administrative user. For example:

```bash
sudo systemctl start mysql
export MYSQL_ROOT_USER=root
export MYSQL_ROOT_PASSWORD='your-root-password'
```

## Start the Cluster

```bash
./scripts/start-cluster.sh
```

With Prometheus:

```bash
./scripts/start-cluster.sh --with-prometheus
```

Equivalent Make targets:

```bash
make cluster-start
make cluster-start-observability
```

On first run the script builds the jars with `./mvnw -B -DskipTests package`. Use `--skip-build` to reuse existing jars.

If you prefer to create the database and user yourself, create them before running the script:

```sql
CREATE DATABASE integration_lab CHARACTER SET utf8mb4;
CREATE USER 'integration'@'localhost' IDENTIFIED BY 'integration_local_only';
CREATE USER 'integration'@'127.0.0.1' IDENTIFIED BY 'integration_local_only';
GRANT ALL PRIVILEGES ON integration_lab.* TO 'integration'@'localhost';
GRANT ALL PRIVILEGES ON integration_lab.* TO 'integration'@'127.0.0.1';
```

The gateway applies `schema.sql` on startup, so no manual table creation is needed.

## Check Status

```bash
./scripts/status-cluster.sh
make cluster-status
```

The status script reports process state, health endpoints, and MySQL reachability.

## Stop the Cluster

```bash
./scripts/stop-cluster.sh
make cluster-stop
```

This stops the gateway, mock partner, and Prometheus. MySQL is left running because it may be shared with other local projects.

To stop the Homebrew-managed MySQL service too:

```bash
./scripts/stop-cluster.sh --stop-mysql
make cluster-stop-mysql
```

## Configuration

The scripts read a `.env` file in the repository root when present. See `.env.example` for the available variables.

| Variable | Default | Purpose |
|---|---|---|
| `GATEWAY_PORT` | `8080` | Gateway HTTP port |
| `PARTNER_PORT` | `8081` | Mock partner HTTP port |
| `MYSQL_HOST` | `127.0.0.1` | MySQL host |
| `MYSQL_PORT` | `3306` | MySQL port |
| `MYSQL_DATABASE` | `integration_lab` | Database name |
| `MYSQL_USER` | `integration` | Application database user |
| `MYSQL_PASSWORD` | `integration_local_only` | Application password |
| `MYSQL_ROOT_USER` | `root` | Administrative user used for provisioning |
| `MYSQL_ROOT_PASSWORD` | empty | Administrative password |
| `PROMETHEUS_PORT` | `9090` | Prometheus UI and scrape port |
| `SPRING_PROFILE` | `demo` | Spring profile for both services |

Example `.env` overrides:

```bash
GATEWAY_PORT=18080
PARTNER_PORT=18081
MYSQL_PASSWORD=local_demo_password
```

The native scripts run both services with the `demo` profile so deterministic fault injection remains available for demonstrations.

## Logs and Runtime Files

Runtime state is written under `.run/`, which is ignored by Git:

- `.run/logs/integration-gateway.log`
- `.run/logs/mock-partner.log`
- `.run/logs/prometheus.log`
- `.run/pids/*.pid`
- `.run/prometheus.yml` (generated from `config/prometheus/prometheus-local.yml`)
- `.run/prometheus-data/`

## Troubleshooting

**JDK version error**

Set `JAVA_HOME` to a JDK 21 installation:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

**MySQL is not reachable**

Start it explicitly and confirm the port:

```bash
brew services start mysql@8.4
mysqladmin --protocol=tcp -h 127.0.0.1 -P 3306 ping
```

**Root connection rejected**

Set `MYSQL_ROOT_USER` and `MYSQL_ROOT_PASSWORD`, or create the database and application user manually using the SQL above.

**Port already in use**

Override the relevant port in `.env` or stop the conflicting process. Check listeners with:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

**Service did not become healthy**

Inspect the recent log output in `.run/logs/` and confirm that MySQL, the partner, and the gateway are all running. The start script prints the last log lines when a health check fails.

**Stale PID files**

If a process was killed externally, `.run/pids` may contain stale entries. Run `./scripts/stop-cluster.sh` to clear them, or delete the stale file.

## Relationship to Docker Compose

`docker compose up --build` still starts the containerized cluster. Use the native scripts when you want host JVMs, direct IDE attach/debugging, or no Docker networking. Both workflows create the same schema and expose the same API.
