#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""
Release helpers for the Build workflow.

  release.py version --base 1.2.0 --channel beta --run 57
      Prints the version name for the channel: 1.2.0 (stable), 1.2.0-beta.N (next free N for
      that base), 1.2.0-dev.57 (dev), plus the previous tag the notes start from, as
      GitHub output lines (version=…, previous=…).

  release.py notes --version 1.2.0-beta.3 --previous v1.2.0-beta.2 --out notes.md [--changelog CHANGELOG.md]
      Writes user-facing release notes for the commits since --previous. With GITHUB_TOKEN it
      asks GitHub Models (CHANGELOG_MODEL, default openai/gpt-4.1-mini); otherwise, or if that
      fails, it lists the commit subjects. With --changelog the notes are also added to it.

  release.py changelog --version 1.2.0 --notes notes.md --changelog CHANGELOG.md
      Adds already written notes to CHANGELOG.md (replacing that version's section, if any).
"""
import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import urllib.request

SEMVER = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?$")
MODELS_URL = "https://models.github.ai/inference/chat/completions"
TRAILER = re.compile(r"^(Co-Authored-By|Signed-off-by|Claude-Session|Change-Id):", re.I)


def git(*args: str) -> str:
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout.strip()


def parse(tag: str):
    m = SEMVER.match(tag)
    if not m:
        return None
    pre = m.group(5).split(".") if m.group(5) else []
    # The optional fourth number (0.0.2.102) is kept apart so it's printed only when given.
    return int(m.group(1)), int(m.group(2)), int(m.group(3)), pre, m.group(4)


def sort_key(v):
    major, minor, patch, pre, build = v
    # SemVer: a release sorts after its pre-releases; numeric identifiers before alphanumeric ones.
    ids = [(0, int(p), "") if p.isdigit() else (1, 0, p) for p in pre]
    return (major, minor, patch, int(build or 0), 1 if not pre else 0, ids)


def base_of(v) -> str:
    return f"{v[0]}.{v[1]}.{v[2]}" + (f".{v[4]}" if v[4] is not None else "")


def same_base(a, b) -> bool:
    return a[:3] == b[:3] and int(a[4] or 0) == int(b[4] or 0)


def tags():
    out = []
    for t in git("tag", "--list", "v*").splitlines():
        v = parse(t)
        if v:
            out.append((t, v))
    return sorted(out, key=lambda tv: sort_key(tv[1]))


def is_ancestor(tag: str) -> bool:
    return subprocess.run(["git", "merge-base", "--is-ancestor", tag, "HEAD"], capture_output=True).returncode == 0


def channel_of(v) -> str:
    pre = v[3]
    if not pre:
        return "stable"
    return "dev" if pre[0].lower() == "dev" else "beta"


def cmd_version(a) -> None:
    base = parse(a.base)
    if not base or base[3]:
        sys.exit(f"::error::The version must look like 1.2.0 or 0.0.2.102 (no suffix; the channel adds it), got '{a.base}'")
    all_tags = tags()
    b = base_of(base)
    if a.channel == "stable":
        name = b
        if any(t == f"v{b}" for t, _ in all_tags):
            sys.exit(f"::error::v{b} is already released; pick a higher version")
    elif a.channel == "beta":
        if any(t == f"v{b}" for t, _ in all_tags):
            sys.exit(f"::error::v{b} is already released as stable; betas must come before it")
        numbers = [int(v[3][1]) for t, v in all_tags if same_base(v, base) and len(v[3]) == 2 and v[3][0] == "beta" and v[3][1].isdigit()]
        name = f"{b}-beta.{max(numbers, default=0) + 1}"
    else:
        name = f"{b}-dev.{a.run}"
    current = parse(name)
    # Notes cover everything since the previous release users of this channel had.
    wanted = {"stable": ("stable",), "beta": ("stable", "beta"), "dev": ("stable", "beta", "dev")}[a.channel]
    previous = ""
    for t, v in reversed(all_tags):
        if sort_key(v) < sort_key(current) and channel_of(v) in wanted and is_ancestor(t):
            previous = t
            break
    print(f"version={name}")
    print(f"previous={previous}")


def commits(previous: str):
    rng = f"{previous}..HEAD" if previous else "HEAD"
    raw = git("log", "--no-merges", "--format=%s%n%b%x1e", rng)
    entries = []
    for block in raw.split("\x1e"):
        lines = [line for line in block.strip().splitlines() if line.strip() and not TRAILER.match(line.strip())]
        if lines:
            entries.append(lines)
    return entries


def ai_notes(version: str, entries, stat: str):
    token = os.environ.get("GITHUB_TOKEN")
    if not token or not entries:
        return None
    log = "\n\n".join("\n".join(e) for e in entries)[:24000]
    system = (
        "You write release notes for Heartline, a wellness app for Galaxy Watch and Android phones "
        "(ECG, blood pressure estimates, heart rate, SpO2, stress, body composition). Write for end "
        "users in plain, friendly English. Use Markdown with only these sections, in this order and "
        "only when they have content: '### New', '### Improved', '### Fixed'. One short bullet per "
        "user-visible change; merge related commits. Leave out refactoring, tests, CI, docs and other "
        "internal work. Never claim medical accuracy or diagnosis. No title, no introduction, no "
        "version number, no closing remarks."
    )
    body = {
        "model": os.environ.get("CHANGELOG_MODEL") or "openai/gpt-4.1-mini",
        "temperature": 0.2,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": f"Version {version}. Changed files: {stat}\n\nCommits:\n{log}"},
        ],
    }
    request = urllib.request.Request(
        MODELS_URL,
        data=json.dumps(body).encode(),
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            text = json.load(response)["choices"][0]["message"]["content"].strip()
    except Exception as e:  # noqa: BLE001 - any failure falls back to the commit list
        print(f"::warning::GitHub Models failed ({e}); using the commit list", file=sys.stderr)
        return None
    text = re.sub(r"^```(?:markdown)?\s*|\s*```$", "", text).strip()
    return text if "### " in text or text.startswith("- ") else None


def plain_notes(entries) -> str:
    if not entries:
        return "- Maintenance release."
    return "### Changes\n" + "\n".join(f"- {e[0]}" for e in entries[:40])


def add_to_changelog(path: str, version: str, notes: str) -> None:
    today = datetime.date.today().isoformat()
    section = f"## {version} — {today}\n\n{notes.strip()}\n"
    text = open(path, encoding="utf-8").read() if os.path.exists(path) else "# Changelog\n"
    text = re.sub(rf"^## {re.escape(version)} .*?(?=^## |\Z)", "", text, flags=re.M | re.S)
    m = re.search(r"^## ", text, flags=re.M)
    text = (text[: m.start()] + section + "\n" + text[m.start():]) if m else text.rstrip() + "\n\n" + section
    open(path, "w", encoding="utf-8").write(text)


def cmd_notes(a) -> None:
    entries = commits(a.previous)
    stat = git("diff", "--shortstat", a.previous, "HEAD") if a.previous else "first release"
    notes = ai_notes(a.version, entries, stat) or plain_notes(entries)
    with open(a.out, "w", encoding="utf-8") as f:
        f.write(notes.strip() + "\n")
    if a.changelog:
        add_to_changelog(a.changelog, a.version, notes)
    print(notes)


def main() -> None:
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    v = sub.add_parser("version")
    v.add_argument("--base", required=True)
    v.add_argument("--channel", choices=["stable", "beta", "dev"], required=True)
    v.add_argument("--run", default="0")
    n = sub.add_parser("notes")
    n.add_argument("--version", required=True)
    n.add_argument("--previous", default="")
    n.add_argument("--out", required=True)
    n.add_argument("--changelog")
    c = sub.add_parser("changelog")
    c.add_argument("--version", required=True)
    c.add_argument("--notes", required=True)
    c.add_argument("--changelog", required=True)
    a = p.parse_args()
    if a.cmd == "version":
        cmd_version(a)
    elif a.cmd == "notes":
        cmd_notes(a)
    else:
        add_to_changelog(a.changelog, a.version, open(a.notes, encoding="utf-8").read())


if __name__ == "__main__":
    main()
