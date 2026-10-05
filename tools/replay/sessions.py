#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""
Turns the raw background windows of an exported log zip (sessions/*-irn_window-*/) into replay
fixtures for shared/src/test/.../ReplayTest.kt, so real windows become tests of the background
window logic (BackgroundWindow): which stretch is read, and whether stress gets a reading.

  python3 tools/replay/sessions.py LOGS.zip 20261005-015811 OUT.csv
  python3 tools/replay/sessions.py LOGS.zip --summary

The session is picked by the start of its folder name. Only numbers are kept: time from the
first reading (ms), the tracker's status and heart rate, and the beat intervals with their
status (';'-separated). Columns: offsetMs,status,bpm,ibis,ibiStatus.
With --summary it prints, for every window, the readings and how many were reliable.
"""
import collections
import csv
import io
import sys
import zipfile


def rows(z, names, path):
    return list(csv.DictReader(io.StringIO(z.read(path).decode()))) if path in names else []


def session(z, names, folder):
    base = f"sessions/{folder}"
    hr = rows(z, names, f"{base}/HEART_RATE_CONTINUOUS.csv")
    ibis = collections.defaultdict(list)
    status = collections.defaultdict(list)
    for r in rows(z, names, f"{base}/HEART_RATE_CONTINUOUS.IBI_LIST.csv"):
        ibis[r["timestampNs"]].append(r["value"])
    for r in rows(z, names, f"{base}/HEART_RATE_CONTINUOUS.IBI_STATUS_LIST.csv"):
        status[r["timestampNs"]].append(r["value"])
    hr.sort(key=lambda r: int(r["timestampNs"]))
    seen = set()
    out = []
    first = None
    for r in hr:
        t = r["timestampNs"]
        if t in seen:
            continue
        seen.add(t)
        ms = int(t) // 1_000_000
        first = first if first is not None else ms
        out.append(f"{ms - first},{r['HEART_RATE_STATUS']},{r['HEART_RATE']},{';'.join(ibis[t])},{';'.join(status[t])}")
    return out


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    z = zipfile.ZipFile(sys.argv[1])
    names = set(z.namelist())
    folders = sorted({n.split("/")[1] for n in names if n.startswith("sessions/") and "-irn_window-" in n})
    if sys.argv[2] == "--summary":
        for f in folders:
            lines = session(z, names, f)
            good = sum(1 for line in lines if line.split(",")[1] == "1")
            print(f"{f}: {len(lines)} readings, {good} reliable")
        return
    match = [f for f in folders if f.startswith(sys.argv[2])]
    if len(match) != 1 or len(sys.argv) != 4:
        print(f"{len(match)} windows match {sys.argv[2]!r}; give one and an output file")
        sys.exit(2)
    lines = session(z, names, match[0])
    with open(sys.argv[3], "w", encoding="utf-8") as out:
        out.write("offsetMs,status,bpm,ibis,ibiStatus\n")
        out.write("\n".join(lines) + "\n")
    print(f"{len(lines)} readings -> {sys.argv[3]}")


if __name__ == "__main__":
    main()
