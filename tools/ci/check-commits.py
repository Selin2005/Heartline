#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Fails when commits in a range carry AI attribution: an AI author or committer, or
Co-Authored-By / Claude-Session / "Generated with Claude Code" lines (see CLAUDE.md).

  python3 tools/ci/check-commits.py [RANGE]    default: origin/main..HEAD
"""
import re
import subprocess
import sys

rng = sys.argv[1] if len(sys.argv) > 1 else "origin/main..HEAD"
AUTHOR = re.compile(r"claude|anthropic", re.I)
TRAILER = re.compile(r"^\s*(co-authored-by:.*(claude|anthropic)|claude-session:|.*generated with \[?claude code|.*claude\.ai/code)", re.I | re.M)

log = subprocess.run(["git", "log", "--format=%H%x1f%an <%ae>%x1f%cn <%ce>%x1f%B%x1e", rng], capture_output=True, text=True, check=True).stdout
bad = []
for entry in filter(str.strip, log.split("\x1e")):
    sha, author, committer, body = entry.strip().split("\x1f", 3)
    reasons = [f"author {author}" for _ in [0] if AUTHOR.search(author)]
    reasons += [f"committer {committer}" for _ in [0] if AUTHOR.search(committer)]
    reasons += ["AI attribution line in the message" for _ in [0] if TRAILER.search(body)]
    if reasons:
        bad.append(f"{sha[:8]} {body.splitlines()[0] if body else ''}: {', '.join(reasons)}")
if bad:
    print("Commits must be authored by the owner without AI attribution (see CLAUDE.md):")
    print("\n".join(bad))
    sys.exit(1)
print(f"{rng}: commit authorship OK")
