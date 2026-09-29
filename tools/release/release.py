#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""
Release helpers for the Build workflow.

  release.py version --base 1.2.0 --channel beta --run 57
      Prints the version name for the channel: 1.2.0 (stable), 1.2.0-beta.N (next free N for
      that base), 1.2.0-dev.57 (dev), the previous tag the notes start from and the Android
      versionCode (minutes since 2026, so it counts up with every build of every channel), as
      GitHub output lines (version=…, previous=…, code=…). Every build must sort above every
      stable and beta release so far.

  release.py notes --version 1.2.0-beta.3 --out notes.md --changelog CHANGELOG.md [--previous TAG]
      Writes user-facing release notes. With --changelog they cover the commits since the newest
      version already in CHANGELOG.md (its tag), or the whole history when it has none yet, and
      are added to it; --previous is only used without --changelog, or when that version's tag
      is missing. With OPENCODE_API_KEY it asks OpenCode Go (OPENCODE_MODEL, default
      glm-5.3-flash); otherwise, or if that fails, it lists the commit subjects.

  release.py changelog --version 1.2.0 --notes notes.md --changelog CHANGELOG.md
      Adds already written notes to CHANGELOG.md (replacing that version's section, if any).

  release.py release-notes --tag v1.2.0 --out notes.md
      Reads the notes back from a published GitHub release (gh CLI), for re-announcing it.

  release.py telegram --version 1.2.0 --channel stable --notes notes.md --release-url URL [--dry-run]
      Announces the release in the community's Telegram topic: title, a short summary (OpenCode
      Go, when OPENCODE_API_KEY is set), the notes and buttons to the release and the install
      guide. Needs TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID (TELEGRAM_THREAD_ID for a forum topic);
      without them it does nothing. --dry-run prints the message instead of sending it.
"""
import argparse
import datetime
import html
import json
import os
import re
import subprocess
import sys
import time
import uuid
import urllib.error
import urllib.request

SEMVER = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?$")
OPENCODE_URL = "https://opencode.ai/zen/go/v1/chat/completions"
NOTES_START, NOTES_END = "<!-- notes -->", "<!-- /notes -->"
TELEGRAM_LIMIT = 4096
# OpenCode Go: seconds for each streamed piece, and for the whole answer.
READ_TIMEOUT, ANSWER_LIMIT = 120, 600
ATTEMPTS = 3
# Cloudflare (in front of OpenCode) blocks urllib's default "Python-urllib/3.x" (error 1010).
USER_AGENT = "heartline-release/1.0 (+https://github.com/selin2005/heartline)"
# OpenCode Go routes by session and rejects requests without one (MissingSessionID): one stable
# id per workflow run (all requests of a release belong to one "conversation").
SESSION_ID = "heartline-" + (
    f"{os.environ['GITHUB_RUN_ID']}-{os.environ.get('GITHUB_RUN_ATTEMPT', '1')}" if os.environ.get("GITHUB_RUN_ID") else uuid.uuid4().hex
)
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


def tags(extra=()):
    """The version tags, plus [extra] (the tags of draft releases, which have no git tag yet)."""
    out = {}
    for t in [*git("tag", "--list", "v*").splitlines(), *extra]:
        v = parse(t.strip())
        if v:
            out[t.strip()] = v
    return sorted(out.items(), key=lambda tv: sort_key(tv[1]))


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
    # Drafts count too: their tag only exists once they're published.
    known = open(a.known_tags, encoding="utf-8").read().split() if a.known_tags and os.path.exists(a.known_tags) else []
    all_tags = tags(known)
    b = base_of(base)
    if a.channel == "stable":
        name = b
        if any(t == f"v{b}" for t, _ in all_tags):
            sys.exit(f"::error::v{b} is already released (or saved as a draft); pick a higher version")
    elif a.channel == "beta":
        if any(t == f"v{b}" for t, _ in all_tags):
            sys.exit(f"::error::v{b} is already released as stable; betas must come before it")
        numbers = [int(v[3][1]) for t, v in all_tags if same_base(v, base) and len(v[3]) == 2 and v[3][0] == "beta" and v[3][1].isdigit()]
        name = f"{b}-beta.{max(numbers, default=0) + 1}"
    else:
        name = f"{b}-dev.{a.run}"
    current = parse(name)
    # Android installs an update only over a lower versionCode, and the versionCode counts up with
    # time (version_code), so every build must also sort above every stable and beta release so far
    # (the Stable and Beta update channels go by version): otherwise switching channels would offer
    # the older-built, higher version as an update that can't install. Dev builds sort above the
    # betas of their version but below its stable release, so after X.Y.Z is released, dev builds
    # need a higher version.
    higher = [t for t, v in all_tags if channel_of(v) != "dev" and sort_key(v) > sort_key(current)]
    if higher:
        sys.exit(f"::error::{higher[-1]} is already released (or saved as a draft): a {a.channel} build must be a higher version than {name}")
    # Notes cover everything since the previous release users of this channel had.
    wanted = {"stable": ("stable",), "beta": ("stable", "beta"), "dev": ("stable", "beta", "dev")}[a.channel]
    previous = ""
    for t, v in reversed(all_tags):
        if sort_key(v) < sort_key(current) and channel_of(v) in wanted and git("tag", "--list", t) and is_ancestor(t):
            previous = t
            break
    print(f"version={name}")
    print(f"previous={previous}")
    print(f"code={version_code()}")


# 2026-01-01 00:00 UTC. Every build's versionCode is the minutes since then.
CODE_EPOCH = 1767225600


def version_code(now=None) -> int:
    """The Android versionCode: minutes since 2026-01-01 UTC. Counts up with every build of every
    channel (dev, beta, stable and Promote), so any later build installs over any earlier one, and
    stays below Google Play's 2100000000 for thousands of years."""
    return int(((time.time() if now is None else now) - CODE_EPOCH) // 60)


# Commits that only touch these never reach the release notes: agent and CI setup, workflows,
# repository tooling, tests and the changelog itself.
INTERNAL = re.compile(
    r"^(\.claude/|CLAUDE\.md$|\.github/|tools/(?!.*README\.md$)|[^/]+/src/test/|\.gitignore$|"
    r"\.editorconfig$|CHANGELOG\.md$)"
)
# Documentation, legal texts and store listings: summed up as one line.
DOCS = re.compile(r"^(docs/|legal/|fastlane/|[^/]+\.md$|LICENSE|tools/[^/]+/README\.md$)")
DOCS_LINE = "Documentation, terms and policy updates"


def commits(previous: str):
    """Commit messages since [previous] that matter to users, and whether docs changed too."""
    rng = f"{previous}..HEAD" if previous else "HEAD"
    raw = git("log", "--no-merges", "--format=%x1e%s%n%b%x1f", "--name-only", rng)
    entries, docs = [], False
    for block in raw.split("\x1e"):
        if not block.strip():
            continue
        message, _, names = block.partition("\x1f")
        files = [f for f in names.split("\n") if f.strip()]
        outside = [f for f in files if not INTERNAL.match(f)]
        if files and not outside:
            continue
        if files and all(DOCS.match(f) for f in outside):
            docs = True
            continue
        lines = [line for line in message.strip().splitlines() if line.strip() and not TRAILER.match(line.strip())]
        if lines:
            entries.append(lines)
    return entries, docs


def stream_text(response, deadline: float) -> str:
    """The answer text of a streamed (server-sent events) chat completion; a plain JSON answer too."""
    if "text/event-stream" not in (response.headers.get("Content-Type") or ""):
        return json.loads(response.read())["choices"][0]["message"]["content"] or ""
    parts, finish = [], None
    for raw_line in response:
        if time.time() > deadline:
            raise TimeoutError("no complete answer within the time limit")
        line = raw_line.decode("utf-8", "replace").strip()
        if not line.startswith("data:"):
            continue
        data = line[5:].strip()
        if data == "[DONE]":
            break
        event = json.loads(data)
        if event.get("error"):
            raise RuntimeError(f"error in the stream: {json.dumps(event['error'])[:300]}")
        choice = (event.get("choices") or [{}])[0]
        finish = choice.get("finish_reason") or finish
        # Only the answer; reasoning models also stream their thinking in other fields.
        parts.append((choice.get("delta") or {}).get("content") or (choice.get("message") or {}).get("content") or "")
    text = "".join(parts)
    if not text.strip():
        raise RuntimeError(f"empty answer (finish reason: {finish})")
    return text


def ask_model(system: str, user: str):
    """One OpenCode Go chat completion, or None without OPENCODE_API_KEY or when it fails.

    Streamed, so a long answer doesn't hit a read timeout (each piece arrives within READ_TIMEOUT,
    the whole answer within ANSWER_LIMIT). Empty answers, network errors, timeouts and server
    errors (5xx, 429) are tried again, ATTEMPTS times in all."""
    key = os.environ.get("OPENCODE_API_KEY")
    if not key:
        return None
    url = os.environ.get("OPENCODE_URL") or OPENCODE_URL
    model = os.environ.get("OPENCODE_MODEL") or "glm-5.3-flash"
    # The API takes the plain id; some clients prefix it with the provider, so try that too.
    names = [model] + ([f"opencode-go/{model}"] if "/" not in model else [])
    reason = "no attempt"
    for name in names:
        for attempt in range(1, ATTEMPTS + 1):
            body = {
                "model": name,
                "temperature": 0.2,
                "stream": True,
                "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            }
            request = urllib.request.Request(
                url,
                data=json.dumps(body).encode(),
                headers={
                    "Authorization": f"Bearer {key}",
                    "Content-Type": "application/json",
                    "Accept": "text/event-stream, application/json",
                    "User-Agent": USER_AGENT,
                    "x-opencode-session": SESSION_ID,
                },
            )
            retry = False
            try:
                with urllib.request.urlopen(request, timeout=READ_TIMEOUT) as response:
                    text = stream_text(response, time.time() + ANSWER_LIMIT)
                # Reasoning models may put their thinking before the answer.
                text = re.sub(r"<think>.*?</think>", "", text, flags=re.S).strip()
                if text:
                    print(f"Text by {name} (OpenCode Go)", file=sys.stderr)
                    return text
                reason = "empty answer"
                retry = True
            except urllib.error.HTTPError as e:
                reason = f"HTTP {e.code}: {e.read()[:300].decode(errors='replace')}"
                if e.code in (400, 404) and name != names[-1]:
                    break  # try the prefixed model id
                retry = e.code == 429 or e.code >= 500
            except Exception as e:  # noqa: BLE001 - fall back to text without the model
                reason = f"{type(e).__name__}: {e}"
                retry = True
            if retry and attempt < ATTEMPTS:
                print(f"OpenCode Go ({name}): {reason}; trying again", file=sys.stderr)
                time.sleep(5)
                continue
            print(f"::warning::OpenCode Go ({name}) failed: {reason}", file=sys.stderr)
            return None
    print(f"::warning::OpenCode Go failed: {reason}", file=sys.stderr)
    return None


def ai_notes(version: str, entries, docs: bool, stat: str):
    if not entries:
        return None
    # Subject and the first lines of each body; only subjects for many commits (the first release):
    # enough for the model, and a much quicker answer.
    lines = 4 if len(entries) <= 20 else 1
    log = "\n\n".join("\n".join(e[:lines]) for e in entries)[:16000]
    system = (
        "You write release notes for Heartline, a wellness app for Galaxy Watch and Android phones "
        "(ECG, blood pressure estimates, heart rate, SpO2, stress, body composition). Write for end "
        "users in plain, friendly English. Use Markdown with only these sections, in this order and "
        "only when they have content: '### New', '### Improved', '### Fixed'. One short bullet per "
        "user-visible change; merge related commits. Leave out refactoring, tests, CI, docs and other "
        "internal work. Never claim medical accuracy or diagnosis. No title, no introduction, no "
        "version number, no closing remarks."
    )
    if docs:
        log += f"\n\n(Also: {DOCS_LINE}. Mention this as exactly one bullet under '### Improved'.)"
    text = ask_model(system, f"Version {version}. Changed files: {stat}\n\nCommits:\n{log}")
    if not text:
        return None
    text = re.sub(r"^```(?:markdown)?\s*|\s*```$", "", text).strip()
    return text if "### " in text or text.startswith("- ") else None


MENTION = re.compile(r"(?<![\w`/])@[\w.-]+(?::[\w.-]+)?")


def no_mentions(text: str) -> str:
    """Puts "@name" in backticks: GitHub would link it to that user and list them among the
    release's contributors (a commit about "@param:StringRes" once added a stranger)."""
    return MENTION.sub(lambda m: f"`{m.group(0)}`", text)


def plain_notes(entries, docs: bool = False) -> str:
    lines = [f"- {e[0]}" for e in entries[:40]] + ([f"- {DOCS_LINE}"] if docs else [])
    return "### Changes\n" + "\n".join(lines) if lines else "- Maintenance release."


def add_to_changelog(path: str, version: str, notes: str) -> None:
    today = datetime.date.today().isoformat()
    section = f"## {version} — {today}\n\n{notes.strip()}\n"
    text = open(path, encoding="utf-8").read() if os.path.exists(path) else "# Changelog\n"
    text = re.sub(rf"^## {re.escape(version)} .*?(?=^## |\Z)", "", text, flags=re.M | re.S)
    m = re.search(r"^## ", text, flags=re.M)
    text = (text[: m.start()] + section + "\n" + text[m.start():]) if m else text.rstrip() + "\n\n" + section
    open(path, "w", encoding="utf-8").write(text)


def changelog_base(path: str, version: str):
    """Tag of the newest version in CHANGELOG.md (other than [version]) that has one, "" if none."""
    if not os.path.exists(path):
        return ""
    text = open(path, encoding="utf-8").read()
    for listed in re.findall(r"^## (\S+)", text, flags=re.M):
        if listed != version and git("tag", "--list", f"v{listed}"):
            return f"v{listed}"
        if listed != version:
            print(f"::warning::{listed} is in {path} but has no tag v{listed}; looking further back", file=sys.stderr)
    return ""


def cmd_notes(a) -> None:
    if a.changelog:
        # The notes cover everything since the last version users saw in the changelog.
        a.previous = changelog_base(a.changelog, a.version)
    print(f"Release notes for {a.version}: commits since {a.previous or 'the first commit'}", file=sys.stderr)
    entries, docs = commits(a.previous)
    stat = git("diff", "--shortstat", a.previous, "HEAD") if a.previous else "first release"
    notes = no_mentions(ai_notes(a.version, entries, docs, stat) or plain_notes(entries, docs))
    with open(a.out, "w", encoding="utf-8") as f:
        f.write(notes.strip() + "\n")
    if a.changelog:
        add_to_changelog(a.changelog, a.version, notes)
    print(notes)


def inline_html(text: str) -> str:
    """Markdown inline code, bold and links to Telegram HTML, everything else escaped."""
    out = html.escape(text, quote=False)
    out = re.sub(r"`([^`]+)`", r"<code>\1</code>", out)
    out = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", out)
    return re.sub(r"\[([^\]]+)\]\((https?://[^)\s]+)\)", r'<a href="\2">\1</a>', out)


def notes_html(notes: str) -> str:
    """Release notes (### sections and - bullets) as Telegram HTML."""
    lines = []
    for line in notes.strip().splitlines():
        s = line.strip()
        if s.startswith("#"):
            lines.append(("\n" if lines else "") + f"<b>{inline_html(s.lstrip('#').strip())}</b>")
        elif s.startswith(("- ", "* ")):
            lines.append("• " + inline_html(s[2:]))
        elif s:
            lines.append(inline_html(s))
    return "\n".join(lines)


def summary(version: str, notes: str):
    system = (
        "Summarise these release notes of Heartline, a wellness app for Galaxy Watch, in one or two "
        "short, friendly sentences for a community chat. Plain text only, no emoji, no version "
        "number, no medical claims."
    )
    text = ask_model(system, f"Version {version}\n\n{notes}")
    return re.sub(r"\s+", " ", text).strip() if text else None


def telegram_message(version: str, channel: str, notes: str, release_url: str, summary_text) -> str:
    title = {
        "stable": f"🚀 <b>Heartline {html.escape(version)}</b> is out",
        "beta": f"🧪 <b>Heartline {html.escape(version)}</b>: new beta",
    }.get(channel, f"🛠 <b>Heartline {html.escape(version)}</b>: development build")
    how = {
        "stable": "📲 Already using Heartline? Get it in the app: Settings → Updates.",
        "beta": "📲 Get it in the app: Settings → Updates, with the update channel set to Beta or Development.",
    }.get(channel, "📲 Get it in the app: Settings → Updates, on the Development update channel.")
    head = title + ("\n\n" + html.escape(summary_text, quote=False) if summary_text else "")
    tail = f"\n\n{html.escape(how, quote=False)}"
    body = notes_html(notes)
    room = TELEGRAM_LIMIT - len(head) - len(tail) - 80
    if len(body) > room:
        # Cut on a line boundary so no HTML tag is split, and point to the full notes.
        body = body[:room].rsplit("\n", 1)[0] + f'\n… <a href="{html.escape(release_url)}">full notes</a>'
    return f"{head}\n\n{body}{tail}"


def notes_from_body(body: str) -> str:
    """The notes inside a release text: between the notes markers, or (older releases) the text
    before '### Install' without the leading quote lines."""
    if NOTES_START in body and NOTES_END in body:
        return body.split(NOTES_START, 1)[1].split(NOTES_END, 1)[0].strip()
    head = body.split("### Install", 1)[0]
    return "\n".join(line for line in head.splitlines() if not line.startswith(">")).strip()


def cmd_release_notes(a) -> None:
    body = subprocess.run(
        ["gh", "release", "view", a.tag, "--json", "body", "--jq", ".body"],
        capture_output=True, text=True, check=True,
    ).stdout
    notes = notes_from_body(body)
    if not notes:
        sys.exit(f"::error::No release notes found in {a.tag}")
    with open(a.out, "w", encoding="utf-8") as f:
        f.write(notes + "\n")
    print(notes)


def cmd_telegram(a) -> None:
    token = os.environ.get("TELEGRAM_BOT_TOKEN", "")
    chat = os.environ.get("TELEGRAM_CHAT_ID", "")
    thread = os.environ.get("TELEGRAM_THREAD_ID", "")
    notes = open(a.notes, encoding="utf-8").read()
    text = telegram_message(a.version, a.channel, notes, a.release_url, summary(a.version, notes))
    guide = f"https://github.com/{a.repo}/blob/main/docs/DEVICE_TESTING.md#2-install"
    payload = {
        "chat_id": chat,
        "text": text,
        "parse_mode": "HTML",
        "link_preview_options": {"is_disabled": True},
        "reply_markup": {"inline_keyboard": [[
            {"text": "📦 Download", "url": a.release_url},
            {"text": "📖 How to install", "url": guide},
        ]]},
    }
    if thread.strip():
        payload["message_thread_id"] = int(thread.strip())
    if a.dry_run:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return
    if not token or not chat:
        print("::warning::Not announced on Telegram: the TELEGRAM_BOT_TOKEN secret is missing (docs/RELEASING.md)")
        return
    if "message_thread_id" in payload:
        print(f"Posting to {chat}, topic {payload['message_thread_id']}")
    else:
        print(f"::warning::TELEGRAM_THREAD_ID is empty: posting to the General topic of {chat}")
    request = urllib.request.Request(
        f"https://api.telegram.org/bot{token}/sendMessage",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "User-Agent": USER_AGENT},
    )
    # Network hiccups, rate limits (429) and Telegram's 5xx are retried; anything else (chat not
    # found, bot not an admin, bad topic id) won't get better by retrying.
    for attempt in range(1, 5):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                sent = json.load(response).get("result", {})
            # Telegram posts to General without an error when the id isn't one of the group's topics.
            wanted = payload.get("message_thread_id")
            if wanted is not None and sent.get("message_thread_id") != wanted:
                print(f"::warning::Telegram posted outside topic {wanted}; check TELEGRAM_THREAD_ID")
            break
        except urllib.error.HTTPError as e:
            reason = f"{e.code} {e.read().decode(errors='replace')}"
            retry = e.code == 429 or e.code >= 500
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            reason, retry = str(e), True
        if not retry or attempt == 4:
            sys.exit(f"::error::Telegram announcement failed: {reason}")
        print(f"Telegram attempt {attempt} failed ({reason}); retrying", file=sys.stderr)
        time.sleep(5 * 2 ** attempt)
    print(f"Announced {a.version} on Telegram")


def cmd_ai_check(a) -> None:
    """Checks every OpenCode Go step of a release for real (no fallback allowed): a short answer,
    release notes from the commits since [--previous] (the whole history without it) and the
    Telegram summary. Prints them and the Telegram message it would send; exits 1 on any failure."""
    problems = []
    if not os.environ.get("OPENCODE_API_KEY"):
        sys.exit("::error::OPENCODE_API_KEY is not set (Settings → Secrets and variables → Actions → Secrets)")
    model = os.environ.get("OPENCODE_MODEL") or "glm-5.3-flash"
    print(f"Model: {model} at {os.environ.get('OPENCODE_URL') or OPENCODE_URL}")

    started = time.time()
    pong = ask_model("Answer with one word.", "Say the word ready.")
    print(f"\n1. Short answer ({time.time() - started:.1f} s): {pong!r}")
    if not pong:
        problems.append("the model gave no answer")

    entries, docs = commits(a.previous)
    stat = git("diff", "--shortstat", a.previous, "HEAD") if a.previous else "first release"
    started = time.time()
    notes = ai_notes(a.version, entries, docs, stat)
    print(f"\n2. Release notes from {len(entries)} commits since {a.previous or 'the first commit'} ({time.time() - started:.1f} s):\n{notes}")
    if not notes:
        problems.append("no release notes from the model (a build would fall back to the commit list)")

    started = time.time()
    short = summary(a.version, notes or plain_notes(entries, docs))
    print(f"\n3. Telegram summary ({time.time() - started:.1f} s): {short!r}")
    if not short:
        problems.append("no Telegram summary from the model")

    message = telegram_message(a.version, "beta", notes or plain_notes(entries, docs), "https://github.com/selin2005/heartline/releases", short)
    print(f"\n4. Telegram message ({len(message)} of {TELEGRAM_LIMIT} characters):\n{message}")
    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as f:
            f.write(f"### OpenCode Go check: {model}\n\n")
            f.write(f"**Result:** {'❌ ' + '; '.join(problems) if problems else '✅ every step answered by the model'}\n\n")
            f.write(f"**Summary:** {short or '—'}\n\n**Release notes** ({len(entries)} commits):\n\n{notes or '—'}\n")
    if problems:
        sys.exit("::error::OpenCode Go check failed: " + "; ".join(problems))
    print("\nOpenCode Go works: notes and summary were written by the model.")


def main() -> None:
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    v = sub.add_parser("version")
    v.add_argument("--base", required=True)
    v.add_argument("--channel", choices=["stable", "beta", "dev"], required=True)
    v.add_argument("--run", default="0")
    v.add_argument("--known-tags", help="file with the tags of every GitHub release, drafts included")
    n = sub.add_parser("notes")
    n.add_argument("--version", required=True)
    n.add_argument("--previous", default="")
    n.add_argument("--out", required=True)
    n.add_argument("--changelog")
    c = sub.add_parser("changelog")
    c.add_argument("--version", required=True)
    c.add_argument("--notes", required=True)
    c.add_argument("--changelog", required=True)
    rn = sub.add_parser("release-notes")
    rn.add_argument("--tag", required=True)
    rn.add_argument("--out", required=True)
    tg = sub.add_parser("telegram")
    tg.add_argument("--version", required=True)
    tg.add_argument("--channel", choices=["stable", "beta", "dev"], required=True)
    tg.add_argument("--notes", required=True)
    tg.add_argument("--release-url", required=True)
    tg.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", "selin2005/heartline"))
    tg.add_argument("--dry-run", action="store_true")
    ac = sub.add_parser("ai-check")
    ac.add_argument("--version", default="0.0.0-beta.1")
    ac.add_argument("--previous", default="")
    a = p.parse_args()
    if a.cmd == "ai-check":
        cmd_ai_check(a)
    elif a.cmd == "version":
        cmd_version(a)
    elif a.cmd == "notes":
        cmd_notes(a)
    elif a.cmd == "telegram":
        cmd_telegram(a)
    elif a.cmd == "release-notes":
        cmd_release_notes(a)
    else:
        add_to_changelog(a.changelog, a.version, open(a.notes, encoding="utf-8").read())


if __name__ == "__main__":
    main()
