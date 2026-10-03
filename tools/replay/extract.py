#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""
Turns the raw heart-rate tracker readings of an exported watch log into a replay fixture, so a
real-world false (or missed) notice becomes a test (shared/src/test/.../ReplayTest.kt).

The watch logs every HEART_RATE_CONTINUOUS reading that carries beat intervals as
  MM-DD HH:MM:SS.mmm  V Heartline/Sensor: HR status=S ibis=[...] ibiStatus=[...]
This keeps only those numbers between two times: no names, no identifiers.

  python3 tools/replay/extract.py LOG_OR_ZIP "10-03 13:19:30" "10-03 13:22:05" OUT.csv

LOG_OR_ZIP is watch.log, or the exported zip (watch.log is read from it). Output columns:
offsetMs (from the first reading), status, ibis and ibiStatus (';'-separated).
"""
import re
import sys
import zipfile
from datetime import datetime

LINE = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+(?:\d+\s+\d+\s+)?V Heartline/Sensor: HR status=(-?\d+) ibis=\[([^\]]*)\] ibiStatus=\[([^\]]*)\]")


def read_log(path):
    if path.endswith(".zip"):
        with zipfile.ZipFile(path) as z:
            return z.read("watch.log").decode("utf-8", "replace").splitlines()
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read().splitlines()


def stamp(text):
    return datetime.strptime("2000-" + text, "%Y-%m-%d %H:%M:%S.%f")


def extract(lines, start, end):
    first = None
    seen = set()
    rows = []
    for line in lines:
        m = LINE.match(line)
        if not m or not (start <= m.group(1)[:len(start)] and m.group(1)[:len(end)] <= end):
            continue
        # The kept log and the logcat tail can both hold a reading: keep it once.
        if m.group(0) in seen:
            continue
        seen.add(m.group(0))
        at = stamp(m.group(1))
        first = first or at
        offset = int((at - first).total_seconds() * 1000)
        ibis = ";".join(x.strip() for x in m.group(3).split(",") if x.strip())
        status = ";".join(x.strip() for x in m.group(4).split(",") if x.strip())
        rows.append(f"{offset},{m.group(2)},{ibis},{status}")
    return rows


def main():
    if len(sys.argv) != 5:
        print(__doc__)
        sys.exit(2)
    rows = extract(read_log(sys.argv[1]), sys.argv[2], sys.argv[3])
    with open(sys.argv[4], "w", encoding="utf-8") as out:
        out.write("offsetMs,status,ibis,ibiStatus\n")
        out.write("\n".join(rows) + "\n")
    print(f"{len(rows)} readings -> {sys.argv[4]}")


if __name__ == "__main__":
    main()
