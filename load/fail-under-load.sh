#!/usr/bin/env bash
# Kill the app during the ingest scenario, restart it, and check invariants.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
set -a
# shellcheck disable=SC1091
source load/.env
set +a
mkdir -p load/data
python3 load/invariants.py baseline

docker run --rm --name hrsync-load-k6 --network hrsync-load --env-file load/.env \
  -e SCENARIO=ingest -e SUMMARY=failure -e BASE_URL=http://app:8080 -e VUS="${VUS:-8}" \
  -e WARMUP="${WARMUP:-10s}" -e DURATION="${DURATION:-60s}" \
  -v "$ROOT:/work" -w /work \
  grafana/k6 run load/k6.js &
K6_PID=$!

WARMUP_SECONDS="${WARMUP_SECONDS:-10}"
sleep $((WARMUP_SECONDS + 15))
echo "docker kill hrsync-load-app"
docker kill hrsync-load-app
wait "$K6_PID" || true

echo "docker start hrsync-load-app"
docker start hrsync-load-app
for _ in $(seq 1 60); do
  if curl -sf -o /dev/null http://127.0.0.1:8092/health; then
    echo "health ok"
    break
  fi
  sleep 2
done
curl -sf -o /dev/null http://127.0.0.1:8092/health

python3 load/invariants.py check failure --allow-unacked --drain-timeout "${DRAIN_TIMEOUT:-120}"
