#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
# Translates the sandbox proxy env var (https_proxy / HTTPS_PROXY) into Java
# system properties in ~/.gradle/gradle.properties. The JVM ignores proxy env
# vars, so without this Gradle fails with UnknownHostException in cloud sessions.
#
# Runs as a Claude Code SessionStart hook; no-op unless CLAUDE_CODE_REMOTE=true.
# Idempotent: rewrites only the block between the markers below.
set -euo pipefail

if [[ "${CLAUDE_CODE_REMOTE:-}" != "true" && "${HEARTLINE_FORCE_PROXY_SETUP:-}" != "1" ]]; then
  exit 0
fi

GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
PROPS="$GRADLE_USER_HOME/gradle.properties"
BEGIN="# >>> heartline-proxy >>>"
END="# <<< heartline-proxy <<<"

mkdir -p "$GRADLE_USER_HOME"
touch "$PROPS"

urldecode() { local s="${1//+/ }"; printf '%b' "${s//%/\\x}"; }

# Remove any previous managed block.
tmp="$(mktemp)"
awk -v b="$BEGIN" -v e="$END" '$0==b{skip=1;next} $0==e{skip=0;next} !skip' "$PROPS" > "$tmp"
mv "$tmp" "$PROPS"

proxy="${HTTPS_PROXY:-${https_proxy:-${HTTP_PROXY:-${http_proxy:-}}}}"
if [[ -z "$proxy" ]]; then
  echo "gradle-proxy-setup: no proxy env var; removed managed block" >&2
  exit 0
fi

# Parse scheme://[user[:pass]@]host[:port][/]
rest="${proxy#*://}"
rest="${rest%%/*}"
user="" pass=""
if [[ "$rest" == *@* ]]; then
  creds="${rest%@*}"
  rest="${rest##*@}"
  user="$(urldecode "${creds%%:*}")"
  [[ "$creds" == *:* ]] && pass="$(urldecode "${creds#*:}")"
fi
host="${rest%%:*}"
port="${rest##*:}"
[[ "$port" == "$rest" ]] && port=80

# NO_PROXY (comma list) -> Java nonProxyHosts (pipe list, wildcard domains).
# CIDR ranges have no Java equivalent and are dropped.
nop="${NO_PROXY:-${no_proxy:-}}"
non_proxy=""
IFS=',' read -ra entries <<< "$nop"
for e in "${entries[@]}"; do
  e="${e// /}"
  [[ -z "$e" || "$e" == */* ]] && continue
  [[ "$e" == .* ]] && e="*$e"
  non_proxy+="${non_proxy:+|}$e"
done

{
  echo "$BEGIN"
  echo "# Managed by tools/cloud/gradle-proxy-setup.sh — do not edit by hand."
  for p in https http; do
    echo "systemProp.$p.proxyHost=$host"
    echo "systemProp.$p.proxyPort=$port"
    if [[ -n "$user" ]]; then
      echo "systemProp.$p.proxyUser=$user"
      echo "systemProp.$p.proxyPassword=$pass"
    fi
  done
  [[ -n "$non_proxy" ]] && echo "systemProp.http.nonProxyHosts=$non_proxy"
  # Allow Basic auth for CONNECT tunnels (disabled by default since JDK 8u111).
  echo "systemProp.jdk.http.auth.tunneling.disabledSchemes="
  echo "systemProp.jdk.http.auth.proxying.disabledSchemes="
  echo "$END"
} >> "$PROPS"

echo "gradle-proxy-setup: configured Gradle proxy $host:$port${user:+ (with auth)}" >&2
