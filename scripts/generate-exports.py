#!/usr/bin/env python3
"""Write a 200,000-row reconciliation export and a 120,000-row prefix of it.

The prefix omits the last 80,000 employees. Against a roster that contains all
200,000, that missing ratio is 0.40, which is above the 0.30 safety brake.
"""

import argparse
from pathlib import Path

COLUMNS = "tenantId,employeeId,sourceVersion,name,email,phone,manager,officeCode,shift"
FULL_ROWS = 200_000
TRUNCATED_ROWS = 120_000


def row(tenant: str, index: int) -> str:
    employee = f"exp-{index:06d}"
    phone = f"+1555{index:07d}"
    return (
        f"{tenant},{employee},1,Export {index},e{index}@example.com,"
        f"{phone},M1,BLR,DAY"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tenant", default="demo-co")
    parser.add_argument("--out", type=Path, default=Path("scripts/out"))
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    full = args.out / "export-200000.csv"
    short = args.out / "export-120000.csv"
    with full.open("w", encoding="utf-8", newline="\n") as full_file, short.open(
        "w", encoding="utf-8", newline="\n"
    ) as short_file:
        full_file.write(COLUMNS + "\n")
        short_file.write(COLUMNS + "\n")
        for index in range(FULL_ROWS):
            line = row(args.tenant, index) + "\n"
            full_file.write(line)
            if index < TRUNCATED_ROWS:
                short_file.write(line)
    print(f"wrote {FULL_ROWS} data rows to {full}")
    print(f"wrote {TRUNCATED_ROWS} data rows to {short}")


if __name__ == "__main__":
    main()
