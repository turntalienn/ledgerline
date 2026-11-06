# Ledgerline

A double-entry ledger and payment service built with Java 21, Spring Boot, PostgreSQL,
and Kafka. It provides transactional transfers, payment recovery, settlement
reconciliation, and a local environment for exploring account contention and failures.

## Features

- An append-only journal with balanced postings and database-enforced constraints.
- Idempotent transfers with stored responses for duplicate requests.
- Row-lock, optimistic-lock, and sharded balance strategies.
- A transactional outbox and Kafka consumers with event deduplication.
- An eventually consistent account balance projection.
- Payment retries, circuit breaking, webhooks, and recovery of pending attempts.
- A configurable mock payment provider and settlement reconciliation with CSV reports.
- Journal-based volume, money velocity, Gini, HHI, and simulated fee experiments.
- Prometheus metrics, OpenTelemetry traces, Grafana dashboards, and k6 workloads.

## Requirements

- JDK 21 or later.
- Docker with Compose, or Podman with a Compose provider.
- Python 3 for the report checks and examples below.

The Maven wrapper is included. The application runs on port `8080`, PostgreSQL on
`5432`, and the Kafka-compatible Redpanda broker on `19092`.

## Getting started

```bash
git clone https://github.com/turntalienn/ledgerline.git
cd ledgerline
docker compose up -d
./mvnw spring-boot:run
```

Flyway applies the database migrations during startup. Check application health:

```bash
curl --fail http://localhost:8080/actuator/health
```

Podman users can substitute `podman compose` for `docker compose`. To run
Testcontainers against rootless Podman on Linux:

```bash
systemctl --user start podman.socket
export DOCKER_HOST="unix://${XDG_RUNTIME_DIR}/podman/podman.sock"
export TESTCONTAINERS_RYUK_DISABLED=true
```

Stop the local database and broker with `docker compose down`. The PostgreSQL
volume is retained.

## Example transfer

Amounts are integer minor units; `1000` represents INR 10. USER accounts cannot
have a negative balance. A SYSTEM account supplies funds for this local example.

Run the following in Bash while the application is running. Account IDs are read
from the responses, so the example does not depend on a fresh database.

```bash
create_account() {
  curl --fail --silent --show-error http://localhost:8080/accounts \
    -H 'Content-Type: application/json' -d "$1" \
    | python3 -c 'import json, sys; print(int(json.load(sys.stdin)["id"]))'
}

RUN_ID="$(date +%s)-$$"
FAUCET_ID=$(create_account "{\"name\":\"faucet-${RUN_ID}\",\"currency\":\"INR\",\"type\":\"SYSTEM\"}")
ALICE_ID=$(create_account '{"name":"alice","currency":"INR"}')
BOB_ID=$(create_account '{"name":"bob","currency":"INR"}')

curl --fail --silent --show-error http://localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: fund-${RUN_ID}" \
  -d "{\"fromAccountId\":${FAUCET_ID},\"toAccountId\":${ALICE_ID},\"amount\":100000}"

curl --include http://localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: transfer-${RUN_ID}" \
  -d "{\"fromAccountId\":${ALICE_ID},\"toAccountId\":${BOB_ID},\"amount\":1000}"
```

Repeat the last command with the same key and payload to replay the stored response.
The response includes `Idempotent-Replayed: true`. Reusing that key with a different
payload returns `422`.

## API

| Method | Endpoint | Description |
|---|---|---|
| POST | `/accounts` | Create a USER or SYSTEM account |
| GET | `/accounts/{id}` | Read cached and journal balances |
| GET | `/accounts/{id}/projection` | Read the Kafka balance projection |
| POST | `/transfers` | Transfer funds using an `Idempotency-Key` header |
| GET | `/transfers/quote` | Quote a transfer fee for a payer and amount |
| POST | `/payments` | Start a pay-in using an `Idempotency-Key` header |
| GET | `/payments/{id}` | Read payment status and attempts |
| GET / PUT | `/mock-psp/faults` | Inspect or configure mock provider failures |
| POST | `/reconciliation/runs` | Reconcile a settlement date; defaults to today in UTC |
| GET | `/reconciliation/runs/{id}` | Read a reconciliation report |
| GET | `/reconciliation/runs/{id}/mismatches.csv` | Download mismatches |
| GET | `/economics/summary` | Read journal-based economic measures |
| GET | `/economics/fee-experiment` | Read fee-arm volume and revenue |
| GET | `/actuator/prometheus` | Read application metrics |

Economics endpoints accept optional ISO-8601 `from` and `to` timestamps. The default
window is the previous 24 hours. Scheduled reconciliation runs at 00:30 UTC for the
previous day.

## Configuration

Configuration is in `src/main/resources/application.yml`. Spring Boot command-line
arguments override the defaults:

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--ledgerline.balance.strategy=sharded --ledgerline.fees.enabled=true"
```

| Property | Default | Purpose |
|---|---|---|
| `ledgerline.balance.strategy` | `row-lock` | Select `row-lock`, `optimistic`, or `sharded` |
| `ledgerline.balance.shards` | `8` | Number of credit shards per account |
| `ledgerline.fees.enabled` | `false` | Enable configured fee arms |
| `ledgerline.outbox.relay.enabled` | `true` | Publish pending outbox events |
| `ledgerline.outbox.consumers.enabled` | `true` | Enable Kafka consumers |
| `ledgerline.payments.resolver.enabled` | `true` | Retry unresolved payment attempts |
| `ledgerline.recon.schedule.enabled` | `true` | Enable scheduled reconciliation |
| `spring.threads.virtual.enabled` | `false` | Use virtual request threads when enabled |

Use separate databases when comparing balance strategies. Switching strategies on
existing sharded data requires a migration procedure.

## Testing

```bash
./mvnw verify
python3 scripts/check-test-results.py
python3 -m unittest discover -s scripts/tests
```

Integration tests use PostgreSQL, Redpanda, and Toxiproxy through Testcontainers.
They cover concurrent duplicates, conflicting transfers, journal constraints,
Kafka redelivery, payment recovery, network failures, and reconciliation.

A working container runtime is required for the integration tests. Maven can skip
these tests when the runtime is unavailable; `check-test-results.py` rejects skipped
tests. The GitHub Actions workflow runs the suite on Java 21.

## Load testing and profiling

The workloads in `k6/` can run against a local application:

```bash
podman run --rm --network host -v "$PWD/k6:/k6:Z" \
  docker.io/grafana/k6 run /k6/hot-account.js
```

| Workload | Scenario |
|---|---|
| `hot-account.js` | Many payers transferring to one merchant |
| `retry-storm.js` | Concurrent copies of the same transfer |
| `payments.js` | Payments against the fault-injecting provider |
| `fee-experiment.js` | Fee quotes and simulated price-sensitive demand |

The automated benchmark runner requires Podman and a packaged application:

```bash
./mvnw package
podman pull docker.io/library/postgres:16
podman pull docker.io/grafana/k6:latest
./scripts/benchmark.sh
```

Use `RATE`, `DURATION`, `PAYERS`, and `STRATEGIES` to configure a run.
`scripts/benchmark-matrix.sh` compares rates and thread modes. Results, balance
checks, environment details, and JFR recordings are written to
`artifacts/benchmarks/`. `scripts/profile-report.py` extracts JFR tables and an
execution-sample flame graph.

The hot-account workload measures a hot receiver with many funded payers. Report
latency, errors, and dropped iterations alongside throughput; these local runs do
not establish production capacity. The fee experiment uses simulated demand.

## Observability

With the database and broker running, start the telemetry stack and enable the
application's observability profile:

```bash
podman compose -p ledgerline-observability -f compose.observability.yaml up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=observability
```

| Service | Local address |
|---|---|
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| Tempo | http://localhost:3200 |

Dashboards and data sources are provisioned from `observability/`. The profile
enables tracing, Kafka observations, and outbox/projection queue metrics. Leave it
disabled for baseline benchmarks.

With a packaged application and port `8080` free,
`./scripts/smoke-observability.sh` starts a disposable database and application,
then checks Kafka delivery, Prometheus metrics, and a trace stored in Tempo.

The optional `perflens` Maven and Spring profiles enable SQL capture through a
separately installed `io.perflens:perflens-spring-boot-starter:0.1.0-SNAPSHOT`.
The default build does not require that starter. Optional build output goes to
`target-perflens/`.

## Project structure

```text
src/main/java/ledgerline/
  account/        Account creation and balance reads
  ledger/         Journal posting and concurrency control
  ledger/balance/ Balance strategy implementations
  idempotency/    Request claims and response replay
  transfer/       Transfer API and transaction orchestration
  payment/        Pay-in processing and recovery
  mockpsp/        Mock provider, webhooks, and settlement data
  outbox/         Event persistence, relay, and notification consumer
  projection/     Kafka balance read model
  recon/          Settlement matching and mismatch reports
  economics/      Journal-based measures and fee reports
  fees/           Fee arms and quotes
  observability/  Delivery queue metrics
src/main/resources/db/migration/  Flyway migrations
src/test/                        Unit and integration tests
k6/                              Load workloads
scripts/                         Benchmark and verification tools
observability/                   Telemetry service configuration
```

## Limitations

Ledgerline is a local development lab. API authentication and signed provider
webhooks are not implemented. Idempotency keys are global and have no expiration.
The mock provider shares the application's JVM and database, while Toxiproxy tests
introduce real TCP delays and connection cuts.

The Kafka projection is eventually consistent and must not authorize spending.
Failed projection records block their partition until repaired; rebuilding needs
retained event history or a snapshot. Reconciliation uses a simplified settlement
window and does not automatically carry unmatched items forward.
