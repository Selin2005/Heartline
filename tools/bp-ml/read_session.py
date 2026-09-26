#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Read Heartline blood-pressure raw session logs (.hlbp) and exports (.zip).

Layout (shared/src/main/kotlin/com/heartline/shared/bp/BpSessionLog.kt), gzip of big-endian:
  b"HLBP", int32 format, int32 n + n bytes header JSON,
  int32 stream count, then per stream: UTF name, int32 column count, UTF column names,
  int32 n, n x int64 timestamp (wall-clock ns), n x columns x float32 (row-major; NaN = absent).
UTF is Java's modified UTF-8: uint16 length + bytes.

Usage:
  read_session.py export.zip               # summary of every session and the cuff labels
  read_session.py session.hlbp --plot      # plot every stream (needs matplotlib)
  read_session.py session.hlbp --csv out/  # one CSV per stream

In Python: `from read_session import read_session; s = read_session(open(p, "rb").read())`,
then s["header"] (dict) and s["streams"][name] = {"t_ns": ndarray, "columns": [...], "values": ndarray}.
"""
import argparse
import gzip
import io
import json
import os
import struct
import sys
import zipfile

import numpy as np


def _utf(f):
    (n,) = struct.unpack(">H", f.read(2))
    return f.read(n).decode("utf-8")


def read_session(data: bytes) -> dict:
    f = io.BytesIO(gzip.decompress(data))
    if f.read(4) != b"HLBP":
        raise ValueError("not a Heartline BP session")
    (fmt,) = struct.unpack(">i", f.read(4))
    (n,) = struct.unpack(">i", f.read(4))
    header = json.loads(f.read(n).decode("utf-8"))
    (count,) = struct.unpack(">i", f.read(4))
    streams = {}
    for _ in range(count):
        name = _utf(f)
        (ncol,) = struct.unpack(">i", f.read(4))
        columns = [_utf(f) for _ in range(ncol)]
        (m,) = struct.unpack(">i", f.read(4))
        t = np.frombuffer(f.read(8 * m), dtype=">i8").astype(np.int64)
        v = np.frombuffer(f.read(4 * m * ncol), dtype=">f4").astype(np.float32).reshape(m, ncol)
        streams[name] = {"t_ns": t, "columns": columns, "values": v}
    return {"format": fmt, "header": header, "streams": streams}


def rate_hz(stream) -> float:
    t = stream["t_ns"]
    return (len(t) - 1) / ((t[-1] - t[0]) / 1e9) if len(t) > 1 and t[-1] > t[0] else 0.0


def summary(name: str, s: dict) -> str:
    h = s["header"]
    v = h.get("values", {})
    parts = [f"{name}: {h.get('kind')} {h.get('mode')} alg={h.get('algorithm')} device={h.get('device')}"]
    parts += [f"  {k}: n={len(x['t_ns'])} @ {rate_hz(x):.1f} Hz cols={x['columns']}" for k, x in s["streams"].items()]
    if "fusion.systolic" in v:
        parts.append(f"  fused {v['fusion.systolic']:.0f}/{v['fusion.diastolic']:.0f} ±{v.get('fusion.sdSys', 0):.0f}  state={h.get('notes', {}).get('state')}")
    if h.get("cuffSystolic"):
        parts.append(f"  cuff {h['cuffSystolic']}/{h['cuffDiastolic']}")
    return "\n".join(parts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--plot", action="store_true")
    ap.add_argument("--csv")
    a = ap.parse_args()
    sessions = {}
    if a.path.endswith(".zip"):
        with zipfile.ZipFile(a.path) as z:
            for n in z.namelist():
                if n.endswith(".hlbp"):
                    sessions[os.path.basename(n)[:-5]] = read_session(z.read(n))
            if "dataset.json" in z.namelist():
                data = json.loads(z.read("dataset.json"))
                for e in data.get("entries", []):
                    sid = e.get("sessionId")
                    if sid in sessions:
                        sessions[sid]["header"]["cuffSystolic"] = e["cuffSystolic"]
                        sessions[sid]["header"]["cuffDiastolic"] = e["cuffDiastolic"]
    else:
        sessions[os.path.basename(a.path)] = read_session(open(a.path, "rb").read())
    for name, s in sessions.items():
        print(summary(name, s))
        if a.csv:
            os.makedirs(a.csv, exist_ok=True)
            for k, x in s["streams"].items():
                arr = np.column_stack([x["t_ns"], x["values"]])
                np.savetxt(os.path.join(a.csv, f"{name}_{k}.csv"), arr, delimiter=",", header="t_ns," + ",".join(x["columns"]), comments="")
        if a.plot:
            import matplotlib.pyplot as plt

            fig, axes = plt.subplots(len(s["streams"]), 1, sharex=True, figsize=(12, 2.2 * len(s["streams"])))
            axes = np.atleast_1d(axes)
            t0 = min(x["t_ns"][0] for x in s["streams"].values() if len(x["t_ns"]))
            for ax, (k, x) in zip(axes, s["streams"].items()):
                for c, col in enumerate(x["columns"]):
                    ax.plot((x["t_ns"] - t0) / 1e9, x["values"][:, c], lw=0.6, label=col)
                ax.set_ylabel(k)
                ax.legend(loc="upper right", fontsize=6)
            axes[-1].set_xlabel("s")
            plt.show()
    return 0


if __name__ == "__main__":
    sys.exit(main())
