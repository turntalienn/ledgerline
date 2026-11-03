#!/usr/bin/env bash
# The optional observability stack and the normal Redpanda service must already be running.
# This creates a disposable database; it never migrates or fills your regular compose database.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p artifacts/observability
container=''
app_pid=''
cleanup() {
  if [[ -n "$app_pid" ]]; then kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; fi
  if [[ -n "$container" ]]; then podman rm -f "$container" >/dev/null; fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM
# Do not accidentally send synthetic requests into an app that already owns the port.
if curl --max-time 2 -s http://localhost:8080/actuator/health >/dev/null; then
  echo 'Port 8080 already serves HTTP; stop that app or run the Python smoke client explicitly.' >&2
  exit 1
fi
container=$(podman run -d --rm -p 127.0.0.1:15434:5432 -e POSTGRES_DB=ledgerline \
  -e POSTGRES_USER=ledgerline -e POSTGRES_PASSWORD=ledgerline docker.io/library/postgres:16)
ready=false
for ((i=0; i<60; i++)); do
  if podman exec "$container" pg_isready -U ledgerline >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
$ready || exit 1
java -Xmx512m -jar target/ledgerline-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=observability \
  --spring.datasource.url=jdbc:postgresql://localhost:15434/ledgerline \
  --ledgerline.outbox.topic="ledger.smoke.$(date +%s)" \
  --ledgerline.payments.resolver.enabled=false --ledgerline.recon.schedule.enabled=false \
  > artifacts/observability/app.log 2>&1 &
app_pid=$!
ready=false
for ((i=0; i<90; i++)); do
  kill -0 "$app_pid" 2>/dev/null || { cat artifacts/observability/app.log >&2; exit 1; }
  if curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
$ready || exit 1
python3 scripts/smoke-observability.py
# Optional short display window for a screenshot; the default exits and cleans up immediately.
if [[ ${DISPLAY_SECONDS:-0} != 0 ]]; then sleep "$DISPLAY_SECONDS"; fi
