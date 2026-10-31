#!/usr/bin/env bash
# Sample CPU, heap, and Postgres while a k6 scenario runs, then check invariants.
# From the repo root: ./load/run.sh ingest
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
SCENARIO="${1:?scenario name}"
shift || true
set -a
# shellcheck disable=SC1091
source load/.env
set +a
mkdir -p load/data
OUT="load/data/${SCENARIO}-observe"
rm -rf "$OUT"
mkdir -p "$OUT"

python3 load/invariants.py baseline
cp load/data/baseline.json "$OUT/baseline.json"

TOKEN="$(curl -sS -X POST http://127.0.0.1:8092/auth/token \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${HR_SYNC_BOOTSTRAP_OPERATOR_USERNAME}\",\"password\":\"${HR_SYNC_BOOTSTRAP_OPERATOR_PASSWORD}\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"

docker exec hrsync-load-pg psql -U hrsync -d hrsync -c "select pg_stat_statements_reset();" >/dev/null

rm -f "$OUT/stop"
sample() {
  while [[ ! -f "$OUT/stop" ]]; do
    local ts
    ts="$(date +%s)"
    docker stats --no-stream --format "{{.Name}} {{.CPUPerc}} {{.MemUsage}}" \
      hrsync-load-app hrsync-load-pg >"$OUT/stats-$ts.txt" || true
    docker exec hrsync-load-pg psql -U hrsync -d hrsync -c \
      "select now(), state, wait_event_type, wait_event, count(*) from pg_stat_activity where datname = 'hrsync' group by 1, 2, 3, 4 order by 5 desc;" \
      >"$OUT/activity-$ts.txt" || true
    curl -sS -m 8 -H "Authorization: Bearer $TOKEN" -H 'Accept: application/openmetrics-text;version=1.0.0;charset=utf-8' \
      http://127.0.0.1:8092/actuator/prometheus -o "$OUT/prom-$ts.txt" || true
    for metric in jvm.memory.used process.cpu.usage system.cpu.usage hikaricp.connections.active hikaricp.connections.pending hikaricp.connections.max outbox.pending outbox.oldest.pending.seconds outbox.failed outbox.published outbox.claim.size outbox.publish.rate; do
      curl -sS -m 8 -H "Authorization: Bearer $TOKEN" \
        "http://127.0.0.1:8092/actuator/metrics/${metric}" -o "$OUT/metric-${metric}-$ts.json" || true
    done
    sleep 5
  done
}
sample &
SAMPLE_PID=$!

set +e
docker run --rm --network hrsync-load --env-file load/.env \
  -e "SCENARIO=${SCENARIO}" -e BASE_URL=http://app:8080 \
  -v "$ROOT:/work" -w /work \
  grafana/k6 run "$@" load/k6.js
K6_STATUS=$?
set -e
touch "$OUT/stop"
wait "$SAMPLE_PID" || true

docker exec hrsync-load-pg psql -U hrsync -d hrsync -c \
  "select left(query, 160) as query, calls, round(total_exec_time::numeric, 1) as total_ms, round(mean_exec_time::numeric, 2) as mean_ms from pg_stat_statements order by total_exec_time desc limit 12;" \
  >"$OUT/statements.txt" || true
docker exec hrsync-load-pg psql -U hrsync -d hrsync -c \
  "explain select count(*) from outbox where status = 'PENDING';" \
  >"$OUT/explain-pending.txt" || true
docker exec hrsync-load-pg psql -U hrsync -d hrsync -c \
  "explain select id from employee where tenant_id = 'load-a' and employee_id = 'a-1-0' for update;" \
  >"$OUT/explain-employee.txt" || true

# Prints pending every 2s and returns when pending and failed are both 0.
python3 load/invariants.py check "$SCENARIO" --drain-timeout "${DRAIN_TIMEOUT:-360}"
exit "$K6_STATUS"
