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
workflow can publish releases and update `CHANGELOG.md`. Release notes use
[GitHub Models](https://docs.github.com/github-models) through the workflow's own token (the
`models: read` permission); no extra key is needed. Set the repository variable `CHANGELOG_MODEL`
to use a different model.

## Making a release

GitHub → Actions → **Build** → Run workflow:

| Field | Meaning |
|---|---|
| Branch | The branch to build (default `main`); a tag or commit SHA works too. Build stable releases from `main`; the workflow warns otherwise. |
| Version | `X.Y.Z`, without suffix |
| Channel | **stable**: `vX.Y.Z` (or `vX.Y.Z.W`), the latest release. **beta**: `…-beta.N` (N counts up by itself), a pre-release. **dev**: `…-dev.<run>`, a debug build with full logs. |
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
3. writes the release notes with GitHub Models from the commits since the previous release
   (falling back to the commit list), and puts them in `CHANGELOG.md` **before** building, so the
   app shows them in *What's new*;
4. builds the phone and watch APKs (release build, or debug for dev) and `SHA256SUMS`, and for
   stable and beta also the Google Play bundles (`.aab`, run artifacts only);
5. publishes the GitHub release with the notes, APKs and checksums;
6. commits the new `CHANGELOG.md` section to the default branch.

Want to edit the notes? Edit the release on GitHub, and the section in `CHANGELOG.md` (the app
shows the text that was bundled into the APK).

### Recommended flow
1. Release a **beta** (`1.3.0` → `v1.3.0-beta.1`). Testers who opted in get it in the app.
2. Fix what they find and release more betas (`v1.3.0-beta.2`, …).
3. When a beta is good, run **Promote beta to stable** with its tag (`v1.3.0-beta.2`). It rebuilds
   that exact commit as `v1.3.0` with notes covering everything since the previous stable
   release.

`versionCode` is the workflow run number, so every build installs over the previous one.

## Google Play
See [PLAY_STORE.md](PLAY_STORE.md). The Play bundles come from the `play` build type, which leaves
out the GitHub updater and its permissions.
