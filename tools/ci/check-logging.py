#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Fails when app code logs with android.util.Log directly instead of HLog (which also keeps the
exportable diagnostic log). Only HLog itself may use android.util.Log."""
import re
import subprocess
import sys

ALLOWED = {"datalayer/src/main/kotlin/com/heartline/datalayer/diag/HLog.kt"}
PATTERN = re.compile(r"import android\.util\.Log\b|android\.util\.Log\.|(?<![\w.])Log\.[dviwe]\(")

files = subprocess.run(["git", "ls-files", "*/src/main/*.kt"], capture_output=True, text=True, check=True).stdout.split()
bad = []
for path in files:
    if path in ALLOWED:
        continue
    for n, line in enumerate(open(path, encoding="utf-8"), 1):
        if PATTERN.search(line):
            bad.append(f"{path}:{n}: {line.strip()}")
if bad:
    print("Use com.heartline.datalayer.diag.HLog instead of android.util.Log:")
    print("\n".join(bad))
    sys.exit(1)
