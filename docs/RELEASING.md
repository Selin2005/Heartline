# Releasing Heartline

## One-time setup

### 1. Release signing key
Every build the workflow makes (stable, beta and dev) is signed with **one release key**, so users
can update between channels without uninstalling. Updates only install on top of a build signed
with the same key, so create it once and keep it safe. **Losing it means users have to uninstall
to get updates.** Local builds and pull requests, without the key, use the shared test key in
`keystore/`; they don't update a build from Releases.

```bash
keytool -genkeypair -v -keystore heartline-release.jks -alias heartline \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 heartline-release.jks > heartline-release.jks.b64
```

Store the `.jks` file and its passwords in a password manager, never in the repository.

### 2. Repository secrets
In GitHub → Settings → Secrets and variables → Actions, add:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | contents of `heartline-release.jks.b64` |
| `RELEASE_KEYSTORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | `heartline` (or the alias you chose) |
| `RELEASE_KEY_PASSWORD` | key password (defaults to the keystore password) |

Also check Settings → Actions → General → Workflow permissions is **Read and write**, so the
workflow can publish releases and update `CHANGELOG.md`.

Release notes and the Telegram summary are written by **GLM 5.3 Flash** through
[OpenCode Go](https://opencode.ai/docs/go/): add the secret `OPENCODE_API_KEY` (a key from the
OpenCode console). The repository variable `OPENCODE_MODEL` picks another model (default
`glm-5.3-flash`). Without the key, or when the model doesn't answer, the notes are the list of
commit subjects (the run shows a warning with the reason).

### 3. Telegram announcements (optional)
Stable and beta releases are announced in the *Announcements & Builds* topic of the
[community group](https://t.me/HeartlineCamiunity): title, a short summary, the release notes and
buttons to the download and the install guide. Dev builds aren't announced.

1. In Telegram, open [@BotFather](https://t.me/BotFather), send `/newbot` and copy the token.
2. Add the bot to the group as an **admin** that can post messages (and manage topics, if the
   group asks for it).
3. Find the topic's ID: open the *Announcements & Builds* topic, copy a message link
   (`https://t.me/HeartlineCamiunity/<topic id>/<message id>`); the first number is the topic ID.
4. In GitHub → Settings → Secrets and variables → Actions:
   - secret `TELEGRAM_BOT_TOKEN`: the bot token;
   - variable `TELEGRAM_THREAD_ID`: the topic ID, only if it isn't `5` (*Announcements & Builds*,
     the default). Set it under **Variables**, not Secrets;
   - variable `TELEGRAM_CHAT_ID`: only if the group isn't `@HeartlineCamiunity` (a private group
     uses its numeric ID, `-100…`).

The message is sent only after the whole build succeeded and the release is published. Network
errors and Telegram rate limits are retried; if it still fails (wrong token, bot not an admin,
wrong topic ID) the run turns red with Telegram's reason, while the release stays published: fix
the setting and post it by hand or re-run the job. Without the token the step only warns. To preview a message:
`python3 tools/release/release.py telegram --version 1.2.0 --channel stable --notes notes.md --release-url URL --dry-run`.

## Making a release

GitHub → Actions → **Build** → Run workflow:

| Field | Meaning |
|---|---|
| Branch | The branch to build (default `main`); a tag or commit SHA works too. Build stable releases from `main`; the workflow warns otherwise. |
| Version | `X.Y.Z`, without suffix |
| Channel | **stable**: `vX.Y.Z` (or `vX.Y.Z.W`), the latest release. **beta**: `…-beta.N` (N counts up by itself), a pre-release. **dev**: `…-dev.<run>`, the newest code for early testers. |
| Publish a GitHub release | Off: the APKs are only kept as run artifacts |
| Run lint and tests | Leave on for anything users will get |

Who is offered what in the app depends on the user's update channel (Settings → Updates):

| Update channel | Offered |
|---|---|
| Stable | stable releases |
| Beta | beta and stable releases |
| Development | dev, beta and stable releases, by publication time |

The channel starts at the kind of build installed and is sticky: a dev user who updates to a
beta or stable release keeps getting dev builds, and a beta user who updates to a stable
release keeps getting betas.

The workflow:
1. works out the version and the previous release of that channel;
2. runs lint, license header check and all tests;
3. writes the release notes with GLM (OpenCode Go) from the commits since the newest version already
   in `CHANGELOG.md` (from the first commit while it lists none), falling back to the commit
   list, and puts them in `CHANGELOG.md` **before** building, so the app shows them in *What's
   new*. Commits that only change agent setup, workflows, tools or tests are left out, and
   documentation and policy changes become one line;
4. builds the phone and watch APKs and `SHA256SUMS`, and for stable and beta also the Google Play
   bundles (`.aab`, run artifacts only);
5. checks the APKs (`tools/ci/check-apks.py`, see below) and stops if anything is wrong;
6. publishes the GitHub release with the notes, APKs and checksums;
7. commits the new `CHANGELOG.md` section to the default branch;
8. for stable and beta, announces the release on Telegram (job `announce`).

**Re-running a run** whose release is already published (*Re-run all jobs*) rebuilds nothing: the
`check` job finds the release by the run id in its text, and only `announce` runs, reading the
notes back from the release and writing a new summary. *Re-run failed jobs* after a failed
announcement does the same. A run that failed before publishing builds again in full, with the
same version.

Want to edit the notes? Edit the release on GitHub, and the section in `CHANGELOG.md` (the app
shows the text that was bundled into the APK).

### Recommended flow
1. Release a **beta** (`1.3.0` → `v1.3.0-beta.1`). Testers on the Beta or Development update
   channel get it in the app (Settings → Updates); the release text and the Telegram post say so.
2. Fix what they find and release more betas (`v1.3.0-beta.2`, …).
3. When a beta is good, run **Promote beta to stable** with its tag (`v1.3.0-beta.2`). It rebuilds
   that exact commit as `v1.3.0` with notes covering everything since the newest version in
   `CHANGELOG.md`.

Promote accepts three- and four-part beta tags (`v1.3.0-beta.2`, `v0.0.2.106-beta.1`).

## One app, three channels
Dev, beta and stable are **the same build** of the same code: the release build type (R8, not
debuggable), signed with the release key. Only the version name and `versionCode` differ. So a
beta behaves exactly like the dev build it came from, and the app can move between channels just
by changing *Settings → Updates → Update channel*, without uninstalling.

- **Same app ID and key** on phone and watch (`io.github.selin2005.heartline`): the Wear Data Layer
  only connects the two apps when both match.
- **`versionCode` = minutes since 2026-01-01 UTC**, from `release.py version`. It counts up with
  every build of every channel, Promote included, so a later build always installs over an
  earlier one (Android refuses a lower `versionCode`).
- **Versions go up too:** a stable or beta version must be higher than every stable and beta
  release so far (the workflow stops otherwise). A release built later with a lower version would
  have a higher `versionCode`, and the higher-versioned, earlier build offered on another channel
  couldn't install over it.
- **R8 only shrinks the libraries.** `proguard-rules.pro` keeps all of Heartline's code whole and
  renames nothing (`-dontobfuscate`), and resource shrinking is off, so resources only the system
  reads (the Wear OS capabilities) stay in.
- `./gradlew assembleDebug` is for local work and tests only; nothing debuggable is published.

Moving to a channel with an **older** newest release (say from dev to stable) waits for that
channel's next release: the app offers only versions above the installed one.

### APK checks
`tools/ci/check-apks.py` runs on every build before anything is published and fails it when:
- the phone and watch differ in app ID, version name or `versionCode`, or either is debuggable;
- they aren't signed with the same certificate, or not with the release key;
- a Wear OS capability is missing (`heartline_phone`, `heartline_watch`);
- a manifest activity, service, receiver or provider has no class in the APK;
- any of Heartline's own classes, or the Samsung Health Sensor SDK, ONNX Runtime or Wear Data
  Layer classes the apps need, is missing or renamed;
- an asset, Java resource, resource name or ONNX Runtime native library is missing.

Run it on local builds too:
`python3 tools/ci/check-apks.py --phone phone/build/outputs/apk/release/phone-release.apk --watch wear/build/outputs/apk/release/wear-release.apk`.

## Google Play
See [PLAY_STORE.md](PLAY_STORE.md). The Play bundles come from the `play` build type, which leaves
out the GitHub updater and its permissions.
