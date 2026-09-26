# Working in a Claude Code cloud session

Environment: Ubuntu 24.04, JDK 21, Android SDK in `/opt/android-sdk`, no emulator, KVM or
device. Changes are validated with `assemble` for both modules, JVM + Robolectric + Paparazzi
tests and ktlint. Testing on a device uses APKs from GitHub Actions.

## Gradle proxy
The sandbox routes all outbound traffic through an HTTP proxy defined in `https_proxy` /
`HTTPS_PROXY`. **The JVM, and therefore Gradle and `sdkmanager`, ignore those variables.** Without
extra setup the build fails with `UnknownHostException: dl.google.com` or `Could not resolve …`.

`tools/cloud/gradle-proxy-setup.sh` fixes this:
- it parses the proxy variable (`scheme://[user[:pass]@]host:port`, URL-decoded);
- it writes a managed block between `# >>> heartline-proxy >>>` and `# <<< heartline-proxy <<<`
  in `~/.gradle/gradle.properties`: `systemProp.http(s).proxyHost/Port`, `proxyUser/proxyPassword`
  when present, `systemProp.http.nonProxyHosts` (from `NO_PROXY`), and
  `jdk.http.auth.tunneling.disabledSchemes=` (Basic auth on CONNECT);
- it is idempotent: it replaces the previous block, or removes it when there is no proxy;
- it only runs when `CLAUDE_CODE_REMOTE=true` (outside a session: `HEARTLINE_FORCE_PROXY_SETUP=1`).

It runs automatically at the start of every cloud session as a SessionStart hook in
`.claude/settings.json`. To run it by hand:
`bash tools/cloud/gradle-proxy-setup.sh && grep -A12 heartline-proxy ~/.gradle/gradle.properties`.
If the proxy port changes during a session (container restart), run it again and `./gradlew --stop`.

`sdkmanager` doesn't read the Gradle settings. Pass the proxy explicitly:
```bash
p="${HTTPS_PROXY#*://}"; p="${p%/}"
sdkmanager --proxy=http --proxy_host="${p%:*}" --proxy_port="${p##*:}" "platforms;android-37"
```

## Daily loop
See [CONTRIBUTING.md](../CONTRIBUTING.md#development-setup).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `UnknownHostException` / `Could not GET` | Proxy not configured for the JVM | Run the script above, then `./gradlew --stop` |
| `407 Proxy Authentication Required` | Basic auth disabled in the JDK | The script's block contains `disabledSchemes=`; restart the daemon |
| `PKIX path building failed` | The proxy CA is missing from the truststore | The session's `JAVA_TOOL_OPTIONS` sets the truststore; don't unset it |
| Paparazzi `verify` fails | A visual change | Check the PNGs in `*/build/paparazzi/failures`; if intended, re-record |
