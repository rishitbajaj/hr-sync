#!/usr/bin/env python3
"""Check roster invariants after a k6 scenario.

Usage:
  python3 load/invariants.py baseline
  python3 load/invariants.py check ingest
  python3 load/invariants.py check ingest --allow-unacked
"""

import json
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
CONTAINER = "hrsync-load-pg"


def load_env():
    env_path = ROOT / ".env"
    if not env_path.exists():
        return
    for line in env_path.read_text().splitlines():
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() not in os.environ:
            os.environ[key.strip()] = value.strip()


def tenants():
    load_env()
    first = os.environ.get("HR_SYNC_BOOTSTRAP_ADMIN_TENANT", "load-a")
    second = os.environ.get("HR_SYNC_LOAD_ADMIN_B_TENANT", "load-b")
    return first, second


def psql(sql):
    result = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", "hrsync", "-d", "hrsync", "-t", "-A", "-F", "|", "-v", "ON_ERROR_STOP=1"],
        input=sql,
        text=True,
        check=True,
        capture_output=True,
    )
    return result.stdout.strip()


def counts():
    tenant_list = ", ".join("'" + tenant + "'" for tenant in tenants())
    row = psql(
        f"""
        select
          (select count(*) from processed_event where channel = 'HR' and tenant_id in ({tenant_list})),
          (select count(*) from outbox where tenant_id in ({tenant_list})),
          (select count(*) from outbox where tenant_id in ({tenant_list}) and status = 'PENDING'),
          (select count(*) from outbox where tenant_id in ({tenant_list}) and status = 'FAILED'),
          (select count(*) from outbox where tenant_id in ({tenant_list}) and status <> 'PUBLISHED')
        """
    )
    processed, outbox, pending, failed, not_published = [int(part) for part in row.split("|")]
    return {
        "processed": processed,
        "outbox": outbox,
        "pending": pending,
        "failed": failed,
        "notPublished": not_published,
    }


def oldest_pending():
    value = psql(
        """
        select coalesce((
            select extract(epoch from (clock_timestamp() - created_at))
            from outbox
            where status = 'PENDING'
            order by created_at
            limit 1
        ), 0)
        """
    )
    return float(value)


def version_mismatches():
    tenant_list = ", ".join("'" + tenant + "'" for tenant in tenants())
    return int(psql(
        f"""
        select count(*) from employee e
        join (
            select tenant_id, employee_id, max(source_version) as max_version
            from processed_event
            where channel = 'HR' and tenant_id in ({tenant_list})
            group by tenant_id, employee_id
        ) m on m.tenant_id = e.tenant_id and m.employee_id = e.employee_id
        where e.hr_version <> m.max_version
        """
    ))


def employees_without_processed():
    tenant_list = ", ".join("'" + tenant + "'" for tenant in tenants())
    return int(psql(
        f"""
        select count(*) from employee e
        where e.tenant_id in ({tenant_list})
          and not exists (
              select 1 from processed_event p
              where p.tenant_id = e.tenant_id
                and p.employee_id = e.employee_id
                and p.channel = 'HR'
          )
        """
    ))


def cross_tenant():
    return int(psql(
        """
        select count(*) from employee
        where (tenant_id = 'load-a' and (office_code <> 'BLR' or name not like 'A %' or status <> 'ACTIVE'))
           or (tenant_id = 'load-b' and (office_code <> 'NYC' or name not like 'B %' or status <> 'ACTIVE'))
           or (tenant_id not in ('load-a', 'load-b'))
        """
    ))


def drain(timeout):
    started = time.time()
    last = None
    while True:
        current = counts()
        age = oldest_pending()
        if current["pending"] == 0 and current["failed"] == 0:
            return round(time.time() - started, 3), age, current
        if time.time() - started > timeout:
            sys.exit(
                "drain timed out pending="
                + str(current["pending"])
                + " failed="
                + str(current["failed"])
                + " oldest="
                + str(age)
            )
        if current != last:
            print(
                "draining pending="
                + str(current["pending"])
                + " failed="
                + str(current["failed"])
                + " oldestPendingSeconds="
                + format(age, ".3f"),
                flush=True,
            )
            last = current
        time.sleep(2)


def baseline():
    DATA.mkdir(exist_ok=True)
    payload = counts()
    payload["oldestPendingSeconds"] = oldest_pending()
    (DATA / "baseline.json").write_text(json.dumps(payload, indent=2) + "\n")
    print(json.dumps(payload))


def check(scenario, allow_unacked):
    summary_path = DATA / (scenario + "-summary.json")
    baseline_path = DATA / "baseline.json"
    summary = json.loads(summary_path.read_text())
    before = json.loads(baseline_path.read_text())
    drain_seconds, oldest_after, after = drain(timeout=1800)
    applied = summary["events_applied"]
    stale = summary["events_stale"]
    duplicate = summary["events_duplicate"]
    rejected = summary["events_rejected"]
    classified = summary["events_classified"]
    attempted = summary["events_attempted"]
    other = summary["events_other"]
    http_fail = summary["events_http_fail"]
    leaks = summary["read_leaks"]
    outcome_sum = applied + stale + duplicate + rejected
    processed_delta = after["processed"] - before["processed"]
    outbox_delta = after["outbox"] - before["outbox"]
    mismatches = version_mismatches()
    missing_processed = employees_without_processed()
    leaked = cross_tenant()
    report = {
        "scenario": scenario,
        "outcomeSum": outcome_sum,
        "classified": classified,
        "attempted": attempted,
        "httpFail": http_fail,
        "other": other,
        "applied": applied,
        "stale": stale,
        "duplicate": duplicate,
        "rejected": rejected,
        "processedDelta": processed_delta,
        "outboxDelta": outbox_delta,
        "drainSeconds": drain_seconds,
        "oldestPendingSecondsAfter": oldest_after,
        "pendingAfter": after["pending"],
        "failedAfter": after["failed"],
        "notPublishedAfter": after["notPublished"],
        "versionMismatches": mismatches,
        "employeesWithoutProcessed": missing_processed,
        "crossTenantRows": leaked,
        "readLeaks": leaks,
        "coldExhausted": summary.get("cold_exhausted", 0),
        "allowUnacked": allow_unacked,
    }
    failures = []
    if outcome_sum != classified:
        failures.append("outcome sum != classified")
    if other != 0:
        failures.append("unclassified outcomes")
    if http_fail != attempted - classified:
        failures.append("http failures do not fill the gap between attempted and classified")
    if leaks != 0:
        failures.append("read responses crossed tenants")
    if not allow_unacked:
        if classified != attempted:
            failures.append("not every sent event was classified")
        if processed_delta != applied or outbox_delta != applied:
            failures.append("outbox or processed delta != applied")
    else:
        if processed_delta < applied or outbox_delta != processed_delta:
            failures.append("committed rows are not one outbox per processed change")
    if mismatches != 0:
        failures.append("hr_version is not the highest applied version")
    if missing_processed != 0:
        failures.append("employee without a processed row")
    if leaked != 0:
        failures.append("cross-tenant row")
    if after["pending"] != 0 or after["failed"] != 0 or after["notPublished"] != 0:
        failures.append("outbox did not end PUBLISHED")
    if oldest_after != 0:
        failures.append("oldest pending age is not zero")
    report["ok"] = not failures
    report["failures"] = failures
    (DATA / (scenario + "-invariants.json")).write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    if failures:
        sys.exit("invariants failed: " + "; ".join(failures))


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: invariants.py baseline | check <scenario> [--allow-unacked]")
    if sys.argv[1] == "baseline":
        baseline()
        return
    if sys.argv[1] == "check":
        scenario = sys.argv[2]
        check(scenario, "--allow-unacked" in sys.argv)
        return
    sys.exit("unknown command")


if __name__ == "__main__":
    main()
