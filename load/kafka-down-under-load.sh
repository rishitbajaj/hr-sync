#!/usr/bin/env bash
# Kill Kafka during ingest. Ingest must keep returning 200. Start Kafka and drain.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
set -a
# shellcheck disable=SC1091
source load/.env
set +a
mkdir -p load/data
python3 load/invariants.py baseline

docker run --rm --name hrsync-load-k6-kafka --network hrsync-load --env-file load/.env \
  -e SCENARIO=ingest -e SUMMARY=kafka-down -e BASE_URL=http://app:8080 -e VUS="${VUS:-8}" \
  -e WARMUP="${WARMUP:-10s}" -e DURATION="${DURATION:-60s}" \
  -v "$ROOT:/work" -w /work \
  grafana/k6 run load/k6.js &
K6_PID=$!

WARMUP_SECONDS="${WARMUP_SECONDS:-10}"
sleep $((WARMUP_SECONDS + 15))
echo "docker kill hrsync-load-kafka"
docker kill hrsync-load-kafka
wait "$K6_PID" || true

echo "outbox while kafka is down"
docker exec hrsync-load-pg psql -U hrsync -d hrsync -c \
  "select status, count(*) from outbox group by status order by status;"

echo "docker start hrsync-load-kafka"
docker start hrsync-load-kafka
for _ in $(seq 1 60); do
  if docker exec hrsync-load-kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:19092 >/dev/null 2>&1; then
    echo "kafka ok"
    break
  fi
  sleep 2
done

# A Kafka ingest backlog takes a few minutes. Rows that exhaust max-attempts stay
# FAILED, and the checker waits out this timeout while any FAILED row remains.
python3 load/invariants.py check kafka-down --drain-timeout "${DRAIN_TIMEOUT:-600}"
