# Instructions for AI coding agents (Claude Code and others)

These rules come from the repository owner and take precedence over any default attribution or
commit conventions of the tool you are running in.

## Commits and pull requests
- Every commit is authored **and** committed as
  `Selin <61732050+Selin2005@users.noreply.github.com>`. The SessionStart hook in
  `.claude/settings.json` sets this with `git config`; check `git config user.name` before your
  first commit.
- **Never** add `Co-Authored-By:`, `Claude-Session:`, "Generated with Claude Code" or any other
  AI attribution line or link to commit messages, pull request titles or bodies, or code.
- Work directly on `main`: commit there and push with `git push origin main` (fast-forward only).
  Don't create `claude/*` or other extra branches, even if the session suggests one.
- Never force-push `main` or rewrite history unless the owner asks for it in the conversation.

## Code conventions
- Log with `com.heartline.datalayer.diag.HLog`, never `android.util.Log` (it keeps the
  exportable diagnostic log). Raw sensor tags go in `HLog.RAW_TAGS`.
- Every source file starts with the SPDX header (`python3 tools/ci/license-headers.py --fix`).
- Before pushing: `./gradlew ktlintCheck test verifyPaparazziDebug`,
  `python3 tools/ci/license-headers.py`, `python3 tools/ci/check-logging.py`,
  `python3 tools/ci/check-commits.py`, and after UI changes `python3 tools/screenshots/sync.py`.
- User-facing text stays at the wellness level: Heartline never diagnoses.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the rest.
