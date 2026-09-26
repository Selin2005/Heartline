#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Checks, or with --fix adds, the SPDX license header of every source file."""
import pathlib
import subprocess
import sys

SPDX = "SPDX-License-Identifier: AGPL-3.0-or-later"
COPYRIGHT = "Copyright (C) 2026 Selin and Heartline contributors"
COMMENT = {".kt": "//", ".kts": "//", ".py": "#", ".sh": "#"}


def tracked_sources():
    out = subprocess.run(["git", "ls-files"], capture_output=True, text=True, check=True).stdout
    return [pathlib.Path(p) for p in out.splitlines() if pathlib.Path(p).suffix in COMMENT]


def add_header(path: pathlib.Path) -> None:
    c = COMMENT[path.suffix]
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    head = [] if not lines or not lines[0].startswith("#!") else [lines.pop(0)]
    header = [f"{c} {SPDX}\n", f"{c} {COPYRIGHT}\n"]
    if c == "//":
        header.append("\n")
    path.write_text("".join(head + header + lines), encoding="utf-8")


def main() -> int:
    fix = "--fix" in sys.argv
    missing = [p for p in tracked_sources() if SPDX not in "".join(p.read_text(encoding="utf-8").splitlines(True)[:4])]
    for p in missing:
        if fix:
            add_header(p)
        else:
            print(f"missing SPDX header: {p}")
    if missing and not fix:
        print(f"{len(missing)} file(s) without a license header; run: python3 tools/ci/license-headers.py --fix")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
