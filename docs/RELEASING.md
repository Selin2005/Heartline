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
Releases are announced in the *Announcements & Builds* topic of the
[community group](https://t.me/HeartlineCommunity) when the Build form's **Telegram** box is ticked
(the default), for any channel, dev builds too: title, a short summary, the release notes and
buttons to the download and the install guide.

1. In Telegram, open [@BotFather](https://t.me/BotFather), send `/newbot` and copy the token.
2. Add the bot to the group as an **admin** that can post messages (and manage topics, if the
   group asks for it).
3. Find the topic's ID: open the *Announcements & Builds* topic, copy a message link
   (`https://t.me/HeartlineCommunity/<topic id>/<message id>`); the first number is the topic ID.
4. In GitHub → Settings → Secrets and variables → Actions:
   - secret `TELEGRAM_BOT_TOKEN`: the bot token;
   - variable `TELEGRAM_THREAD_ID`: the topic ID, only if it isn't `5` (*Announcements & Builds*,
     the default). Set it under **Variables**, not Secrets;
   - variable `TELEGRAM_CHAT_ID`: only if the group isn't `@HeartlineCommunity` (a private group
     uses its numeric ID, `-100…`).

The message is sent only after the whole build succeeded and the release is published. Network
errors and Telegram rate limits are retried; if it still fails (wrong token, bot not an admin,
wrong topic ID) the run turns red with Telegram's reason, while the release stays published: fix
the setting and post it by hand or re-run the job. Without the token the step only warns. To preview a message:
`python3 tools/release/release.py telegram --version 1.2.0 --channel stable --notes notes.md --release-url URL --dry-run`.

To check OpenCode Go without building anything, run Actions → **OpenCode Go check**: it asks the
model for a short answer, the release notes since the newest version in `CHANGELOG.md` and the
Telegram summary, shows them in the run summary, and fails if any step would fall back.

## Making a release

GitHub → Actions → **Build** → Run workflow:

| Field | Meaning |
|---|---|
| Branch | The branch to build (default `main`); a tag or commit SHA works too. Build stable releases from `main`; the workflow warns otherwise. |
| Version | `X.Y.Z`, without suffix |
| Channel | **stable**: `vX.Y.Z` (or `vX.Y.Z.W`), the latest release. **beta**: `…-beta.N` (N counts up by itself), a pre-release. **dev**: `…-dev.<run>`, the newest code for early testers. |
| Release | **publish**: released right away. **draft**: saved as a draft release (APKs, notes and checksums attached, not visible to the app or users), published later on GitHub. **off**: the APKs are only kept as run artifacts |
| Telegram | Ticked (the default): the release is announced in the Telegram topic, for any channel, dev builds too; for a draft, when it's published. Unticked: no post. Promote has the same box. |
| Run lint and tests | Leave on for anything users will get |

Who is offered what in the app depends on the user's update channel (Settings → Updates):

| Update channel | Offered |
|---|---|
| Stable | stable releases |
| Beta | beta and stable releases |
| Development | dev, beta and stable releases, by publication time |

The *Update channel* setting is shown only in beta and dev builds; stable builds hide it, and
their channel works on as set underneath. The channel starts at the kind of build
installed and is sticky: a dev user who updates to a beta or stable release keeps getting dev
builds, and a beta user who updates to a stable release keeps getting betas. After switching to
a channel the installed build doesn't belong to (dev → beta, beta → stable), the app offers that
channel's newest release published after the installed build; if there's none yet, it waits for
the next one. The phone tells the watch to update when the watch runs a build published before
the phone's newest release.

The workflow:
1. works out the version and the previous release of that channel;
2. runs lint, license header check and all tests;
3. writes the release notes with GLM (OpenCode Go) from the commits since the newest version already
   in `CHANGELOG.md` (for a stable release, the newest stable one, so its notes sum up all its
   betas; from the first commit while there's none), falling back to the commit
   list, and puts them in `CHANGELOG.md` **before** building, so the app shows them in *What's
   new*. Commits that only change agent setup, workflows, tools or tests are left out, and
   documentation and policy changes become one line;
4. builds the phone and watch APKs and `SHA256SUMS`, and for stable and beta also the Google Play
   bundles (`.aab`, run artifacts only);
5. checks the APKs (`tools/ci/check-apks.py`, see below) and stops if anything is wrong;
6. publishes the GitHub release with the notes, APKs and checksums (or saves it as a draft);
7. commits the new `CHANGELOG.md` section to the default branch;
8. with the Telegram box ticked, announces the release on Telegram (job `announce`), dev builds
   too. Dev builds never go into `CHANGELOG.md`.

Steps 7 and 8 wait for a draft to be published.

### Drafts
With **Release: draft** the run saves the release as a draft and stops there: no tag, nothing
in `CHANGELOG.md`, no Telegram post, and the app doesn't see it. Check it (and edit the notes if
you like) under GitHub → Releases, then **Publish release**. That starts the **Release
published** workflow, which adds the release's notes (as published, edits included) to
`CHANGELOG.md` (stable and beta only) and announces it on Telegram if the Build form's Telegram box
was ticked. The APKs keep the notes from
build time for *What's new*. A draft's version counts as taken: the next beta gets the next
number. Delete a draft you don't want. **Release published** can also be run by hand with a tag
to announce a published release again.

Releases the Build workflow publishes itself don't start **Release published** (GitHub runs no
workflows for events made with a workflow's own token), so nothing is announced twice.

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
3. When a beta is good, run **Promote beta to stable** with its tag (`v1.3.0-beta.2`), published right away or as a draft. It rebuilds
   that exact commit as `v1.3.0` with notes covering everything since the previous stable release
   in `CHANGELOG.md` (all the betas together; the whole history before the first stable release).

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
- **Versions go up too:** every build must sort above every stable and beta release so far (the
  workflow stops otherwise). A build made later with a lower version would have a higher
  `versionCode`, and the higher-versioned, earlier build offered on another channel couldn't
  install over it. Dev builds sort above the betas of their version but below its stable release:
  after `X.Y.Z` is released, the next dev builds need a higher version.
- **R8 only shrinks the libraries.** `proguard-rules.pro` keeps all of Heartline's code whole and
  renames nothing (`-dontobfuscate`), and resource shrinking is off, so resources only the system
  reads (the Wear OS capabilities) stay in.
- `./gradlew assembleDebug` is for local work and tests only; nothing debuggable is published.


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

## In-app updates (phone)
The GitHub build updates itself from the releases (`phone/.../update/`); the Google Play build
leaves that to the store (`BuildConfig.UPDATER`, and `src/play/AndroidManifest.xml` removes the
permissions).

- **Checking.** `UpdateWorker` looks once a day (with a network) and announces each new version
  once. *Settings → Updates → Check now* looks right away.
- **Downloading** (`UpdateDownloadWorker`). *Download and install* starts a background job,
  independent of the screen:
  - it runs as a data-sync foreground job with a progress notification and *Cancel*, so leaving
    the screen, the app or locking the phone doesn't stop it;
  - it waits for a network, and after a dropped connection tries again (backoff from 30 s, up to
    8 attempts);
  - the file is `files/updates/<apk>.part`, and every attempt continues it with an HTTP `Range`
    request (`206` appends, `200` starts again, `416` means it's complete);
  - the same version already downloading is kept; a newer version replaces it;
  - "Install unknown apps" is asked for before downloading, and coming back with it allowed
    carries on.
- **Verifying.** The whole file's SHA-256 must match the release's `SHA256SUMS`, or the file is
  deleted. A verified file is renamed to `files/updates/<apk>` and recorded as ready
  (`UpdateRepository.Ready`: version, file, SHA-256). Tapping install again verifies it again and
  installs without downloading; a damaged file is downloaded again.
- **Installing.** When the download finishes:
  - with Heartline on screen, the system installer starts right away;
  - in the background, a *ready, tap to install* notification opens `updates?install=true`, which
    starts it (Android 10+ doesn't let an app in the background open a screen). Home shows an
    *Update X is ready* card too.

  If the installer asks for confirmation while Heartline is in the background,
  `InstallResultReceiver` posts a notification that opens the confirmation. A failed install is
  notified and the file kept; *Cancel* in the system dialog leaves the update ready on the Updates
  screen.
- **Cleaning up.** On every start, once the ready version (or a newer one) is installed, its file
  is deleted.
- **Logs.** Every step is logged as `Heartline/Update`: the requested version and attempt, bytes
  already on disk, the HTTP code, verification, ready, how it was handed to the installer, and the
  installer's status.

Tests: `ReleaseSourceTest` (a local HTTP server: `206`, `200`, `416`, the hash), `UpdaterTest`
(reuse, damaged file, continuing, other versions, checksum, cleanup) and
`UpdateDownloadWorkerTest` (ready notification, retry, the screen's states, the installer
hand-over).

## Google Play
See [PLAY_STORE.md](PLAY_STORE.md). The Play bundles come from the `play` build type, which leaves
out the GitHub updater and its permissions.
