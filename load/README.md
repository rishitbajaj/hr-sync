# Load

k6 runs from the Grafana image. Nothing is installed on the host. The stack uses host ports 5442, 8092, and 9094 so it does not touch an audit database already bound to 5432 or 8080. Port 9094 is the Kafka broker. The app publishes to `kafka:19092` on the compose network.

```bash
cp load/.env.example load/.env
docker compose --env-file load/.env -f load/docker-compose.yml up -d --build
python3 load/generate.py
docker run --rm --network hrsync-load --env-file load/.env -e SCENARIO=ingest -e BASE_URL=http://app:8080 -v "$PWD:/work" -w /work grafana/k6 run load/k6.js
```

`SCENARIO` is `ingest`, `contention`, `reads`, `mixed`, or `ramp`. Each of the first four warms up (`WARMUP`, default `10s`) and then holds for `DURATION` (default `60s`) at `VUS` (default 8). `ramp` holds 2, 4, 8, 16, 32, and 48 virtual users on `RAMP_EXEC` (default `ingest`).

`generate.py` loads offices `BLR` and `NYC`, the second-tenant admin, and the employees the scenarios update and read. Passwords stay in `load/.env`.

`./load/run.sh ingest` samples CPU, heap, and `pg_stat_activity` during that one-line command, then runs `load/invariants.py`. `./load/kafka-down-under-load.sh` kills the broker during ingest and checks the backlog after it starts again.
