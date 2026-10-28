#!/usr/bin/env bash
# Each strategy gets a disposable database; existing compose data is never touched.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
OUT=${OUT:-"$ROOT/artifacts/benchmarks/$(date +%Y%m%d-%H%M%S)"}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
RATE=${RATE:-200}
DURATION=${DURATION:-30s}
PAYERS=${PAYERS:-200}
WARMUP_DURATION=${WARMUP_DURATION:-20s}
VIRTUAL_THREADS=${VIRTUAL_THREADS:-false}
APP_PORT=${APP_PORT:-18080}
DB_PORT=${DB_PORT:-15432}
STRATEGIES=${STRATEGIES:-'row-lock optimistic sharded'}
JAR=${JAR:-target/ledgerline-0.1.0-SNAPSHOT.jar}
[[ -f "$JAR" ]] || { echo 'Run ./mvnw package first' >&2; exit 1; }
app_pid=''
container=''
cleanup() {
  if [[ -n "$app_pid" ]]; then kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; app_pid=''; fi
  if [[ -n "$container" ]]; then podman rm -f "$container" >/dev/null; container=''; fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM
{
  date -Is
  uname -a
  java -version 2>&1
  lscpu
  echo "RATE=$RATE DURATION=$DURATION PAYERS=$PAYERS WARMUP_DURATION=$WARMUP_DURATION VIRTUAL_THREADS=$VIRTUAL_THREADS"
  echo 'Fees, relay, consumers, resolver and reconciliation disabled; outbox writes remain enabled.'
  podman image inspect docker.io/library/postgres:16 docker.io/grafana/k6:latest --format '{{.RepoTags}} {{.Id}}'
  sha256sum "$JAR"
} > "$OUT/environment.txt"
for strategy in $STRATEGIES; do
  case "$strategy" in row-lock|optimistic|sharded) ;; *) echo "Unknown strategy: $strategy" >&2; exit 1;; esac
  dir="$OUT/$strategy"
  mkdir -p "$dir"
  container=$(podman run -d --rm -p "127.0.0.1:$DB_PORT:5432" \
    -e POSTGRES_DB=ledgerline -e POSTGRES_USER=ledgerline -e POSTGRES_PASSWORD=ledgerline \
    docker.io/library/postgres:16)
  ready=false
  for ((i=0; i<60; i++)); do
    if podman exec "$container" pg_isready -U ledgerline >/dev/null 2>&1; then ready=true; break; fi
    sleep 1
  done
  $ready || { echo 'Postgres did not become ready' >&2; exit 1; }
  java -Xms256m -Xmx512m -jar "$JAR" \
    --server.port="$APP_PORT" --spring.threads.virtual.enabled="$VIRTUAL_THREADS" \
    --spring.datasource.url="jdbc:postgresql://localhost:$DB_PORT/ledgerline" \
    --ledgerline.balance.strategy="$strategy" --ledgerline.fees.enabled=false \
    --ledgerline.outbox.relay.enabled=false --ledgerline.outbox.consumers.enabled=false \
    --ledgerline.payments.resolver.enabled=false --ledgerline.recon.schedule.enabled=false \
    > "$dir/app.log" 2>&1 &
  app_pid=$!
  ready=false
  for ((i=0; i<90; i++)); do
    kill -0 "$app_pid" 2>/dev/null || { cat "$dir/app.log" >&2; exit 1; }
    if curl -fsS "http://localhost:$APP_PORT/actuator/health" >/dev/null 2>&1; then ready=true; break; fi
    sleep 1
  done
  $ready || { echo 'Application did not become ready' >&2; exit 1; }
  run_load() {
    podman run --rm --network host -v "$ROOT/k6:/k6:ro,Z" \
      -e BASE_URL="http://localhost:$APP_PORT" -e RATE="$1" -e DURATION="$2" -e PAYERS="$PAYERS" \
      docker.io/grafana/k6:latest run /k6/hot-account.js
  }
  # Warm the JVM using separate accounts before collecting the measured run.
  run_load 50 "$WARMUP_DURATION" > "$dir/warmup.log" 2>&1
  jcmd "$app_pid" JFR.start name=benchmark settings=profile > "$dir/jfr.log"
  # k6 exit 99 is a threshold failure: keep its evidence and compare all strategies.
  rc=0
  run_load "$RATE" "$DURATION" > "$dir/k6.log" 2>&1 || rc=$?
  echo "$rc" > "$dir/exit-code.txt"
  jcmd "$app_pid" JFR.stop name=benchmark filename="$dir/profile.jfr" >> "$dir/jfr.log"
  python3 scripts/benchmark-report.py "$dir/k6.log" "$dir/result.json" "$strategy"
  cleanup
  if [[ "$rc" != 0 && "$rc" != 99 ]]; then cat "$dir/k6.log" >&2; exit "$rc"; fi
  echo "Finished $strategy (k6 exit $rc)"
done
python3 scripts/benchmark-report.py --table "$OUT" > "$OUT/results.md"
cat "$OUT/results.md"
echo "Evidence: $OUT"
