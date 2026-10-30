#!/usr/bin/env python3
"""Seed two tenants, offices, and employees for the k6 scenarios.

The app must already be up (bootstrap creates the operator and the load-a admin).
This script inserts the load-b admin and the rows the scenarios read and update.
"""

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ENV_PATH = ROOT / ".env"
DATA = ROOT / "data"
CONTAINER = os.environ.get("PG_CONTAINER", "hrsync-load-pg")
HOST = os.environ.get("HOST_BASE_URL", "http://127.0.0.1:8092")
COLD_IDS = 100_000
INGEST_VUS = 64


def load_env():
    if not ENV_PATH.exists():
        sys.exit("missing " + str(ENV_PATH))
    for line in ENV_PATH.read_text().splitlines():
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


def required(name):
    value = os.environ.get(name, "")
    if not value:
        sys.exit("missing " + name)
    return value


def wait_health():
    deadline = time.time() + 180
    url = HOST + "/health"
    last = "not started"
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    print("health ok")
                    return
                last = str(response.status)
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            last = str(error)
        time.sleep(2)
    sys.exit("health did not return 200: " + last)


def psql(sql):
    subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", "hrsync", "-d", "hrsync", "-v", "ON_ERROR_STOP=1"],
        input=sql,
        text=True,
        check=True,
    )


def psql_value(sql):
    result = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", "hrsync", "-d", "hrsync", "-t", "-A", "-v", "ON_ERROR_STOP=1"],
        input=sql,
        text=True,
        check=True,
        capture_output=True,
    )
    return result.stdout.strip()


def login(username, password):
    body = json.dumps({"username": username, "password": password}).encode()
    request = urllib.request.Request(
        HOST + "/auth/token",
        data=body,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as error:
        sys.exit("login failed for " + username + " status " + str(error.code))
    if "token" not in payload:
        sys.exit("login for " + username + " returned no token")
    print("login ok " + username)


def main():
    load_env()
    import bcrypt

    wait_health()
    tenant_a = required("HR_SYNC_BOOTSTRAP_ADMIN_TENANT")
    tenant_b = required("HR_SYNC_LOAD_ADMIN_B_TENANT")
    admin_b = required("HR_SYNC_LOAD_ADMIN_B_USERNAME")
    password_b = required("HR_SYNC_LOAD_ADMIN_B_PASSWORD")
    password_hash = bcrypt.hashpw(password_b.encode(), bcrypt.gensalt(rounds=10)).decode()
    if "'" in password_hash or tenant_b.find("'") >= 0 or admin_b.find("'") >= 0:
        sys.exit("seed values must not contain quotes")

    started = time.time()
    psql(
        f"""
        create extension if not exists pg_stat_statements;
        delete from deactivation_effect where tenant_id in ('{tenant_a}', '{tenant_b}');
        delete from outbox where tenant_id in ('{tenant_a}', '{tenant_b}');
        delete from processed_event where tenant_id in ('{tenant_a}', '{tenant_b}');
        delete from employee where tenant_id in ('{tenant_a}', '{tenant_b}');
        delete from office where tenant_id in ('{tenant_a}', '{tenant_b}');
        delete from users where username = '{admin_b}';

        insert into office (id, tenant_id, code) values
            (gen_random_uuid(), '{tenant_a}', 'BLR'),
            (gen_random_uuid(), '{tenant_b}', 'NYC');

        insert into users (id, tenant_id, username, password_hash, role, employee_id)
        values (gen_random_uuid(), '{tenant_b}', '{admin_b}', '{password_hash}', 'ADMIN', null);

        insert into employee (
            id, tenant_id, employee_id, name, email, phone, manager, office_code, shift,
            pickup_point, special_assistance, hr_version, transport_version, status, updated_at
        )
        select gen_random_uuid(), t.tenant_id, t.employee_id,
               t.letter || ' ' || t.employee_id,
               regexp_replace(t.employee_id, '[^A-Za-z0-9]', '', 'g') || '@load.example',
               '+1555' || lpad(t.phone::text, 7, '0'),
               'M1', t.office, 'DAY', null, null, 1, 0, 'ACTIVE', clock_timestamp()
        from (
            select '{tenant_a}' as tenant_id, 'BLR' as office, 'A' as letter,
                   'a-' || vu || '-' || slot as employee_id,
                   (vu * 10 + slot) as phone
            from generate_series(1, {INGEST_VUS}) vu
            cross join generate_series(0, 3) slot
            union all
            select '{tenant_b}', 'NYC', 'B', 'a-' || vu || '-' || slot, 100000 + vu * 10 + slot
            from generate_series(1, {INGEST_VUS}) vu
            cross join generate_series(0, 3) slot
            union all
            select '{tenant_a}', 'BLR', 'A', 'c-' || g, 200000 + g
            from generate_series(0, 3) g
            union all
            select '{tenant_b}', 'NYC', 'B', 'c-' || g, 210000 + g
            from generate_series(0, 3) g
            union all
            select '{tenant_a}', 'BLR', 'A', 'w-' || g, 300000 + g
            from generate_series(0, 19) g
            union all
            select '{tenant_b}', 'NYC', 'B', 'w-' || g, 310000 + g
            from generate_series(0, 19) g
            union all
            select '{tenant_a}', 'BLR', 'A', 'd-' || g, 1000000 + g
            from generate_series(0, {COLD_IDS - 1}) g
            union all
            select '{tenant_b}', 'NYC', 'B', 'd-' || g, 2000000 + g
            from generate_series(0, {COLD_IDS - 1}) g
        ) t;

        insert into processed_event (id, tenant_id, employee_id, channel, source_version, processed_at)
        select gen_random_uuid(), tenant_id, employee_id, 'HR', 1, clock_timestamp()
        from employee
        where tenant_id in ('{tenant_a}', '{tenant_b}');

        insert into outbox (
            tenant_id, aggregate_id, event_type, payload, created_at, published_at, status, attempts
        )
        select tenant_id, employee_id, 'CREATE',
               jsonb_build_object(
                   'employeeId', employee_id,
                   'changedFields', jsonb_build_array('name', 'email', 'phone', 'officeCode', 'shift', 'status')
               ),
               clock_timestamp(), clock_timestamp(), 'PUBLISHED', 1
        from employee
        where tenant_id in ('{tenant_a}', '{tenant_b}');

        analyze employee, processed_event, outbox;
        """
    )
    employees = int(psql_value(
        f"select count(*) from employee where tenant_id in ('{tenant_a}', '{tenant_b}')"
    ))
    login(required("HR_SYNC_BOOTSTRAP_ADMIN_USERNAME"), required("HR_SYNC_BOOTSTRAP_ADMIN_PASSWORD"))
    login(admin_b, password_b)
    login(required("HR_SYNC_BOOTSTRAP_OPERATOR_USERNAME"), required("HR_SYNC_BOOTSTRAP_OPERATOR_PASSWORD"))
    DATA.mkdir(exist_ok=True)
    (DATA / "seed.json").write_text(json.dumps({
        "employees": employees,
        "coldIds": COLD_IDS,
        "ingestVus": INGEST_VUS,
        "seconds": round(time.time() - started, 3),
    }, indent=2) + "\n")
    print(f"seeded {employees} employees in {time.time() - started:.1f}s")


if __name__ == "__main__":
    main()
