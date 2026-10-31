#!/usr/bin/env bash
# Read hr-sync.employee-changes, drop duplicate event ids, and check per-employee order.
# Prints counts only. Bootstrap defaults to localhost:9092 (the compose host listener).
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

export HR_SYNC_KAFKA_BOOTSTRAP_SERVERS="${HR_SYNC_KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
export HR_SYNC_KAFKA_TOPIC="${HR_SYNC_KAFKA_TOPIC:-hr-sync.employee-changes}"
export HR_SYNC_KAFKA_IDLE_MS="${HR_SYNC_KAFKA_IDLE_MS:-3000}"

mvn -B -o -DskipTests exec:java -Dexec.mainClass=com.moveinsync.hrsync.kafka.DemoConsumer
