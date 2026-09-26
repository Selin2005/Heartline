#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
# Collects Heartline logs from the phone and the watch into logs/<timestamp>/.
#
#   tools/device/collect-logs.sh live   [-p PHONE_SERIAL] [-w WATCH_SERIAL]
#       Start BEFORE testing. Streams both devices to files until Ctrl+C, so nothing is lost
#       to logcat buffer rotation (a 30 s ECG at 500 Hz can push older lines out quickly).
#
#   tools/device/collect-logs.sh dump   [-p PHONE_SERIAL] [-w WATCH_SERIAL]
#       After testing: dumps what the buffers still hold.
#
# Each device gets two files: heartline.log (Heartline/* tags plus crashes, small, read this
# first) and full.log (everything, for Data Layer / system issues). Serials come from
# `adb devices`; with a single device of each kind they are detected automatically.
set -euo pipefail

mode="${1:-}"
shift || true
phone=""
watch=""
while getopts "p:w:" opt; do
  case "$opt" in
    p) phone="$OPTARG" ;;
    w) watch="$OPTARG" ;;
    *) exit 2 ;;
  esac
done

if [[ "$mode" != "live" && "$mode" != "dump" ]]; then
  sed -n '2,15p' "$0"
  exit 2
fi

# Auto-detect: a watch reports "watch" in ro.build.characteristics.
detect() {
  for s in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
    if adb -s "$s" shell getprop ro.build.characteristics | grep -q watch; then
      [[ -z "$watch" ]] && watch="$s"
    else
      [[ -z "$phone" ]] && phone="$s"
    fi
  done
}
detect

out="logs/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"
TAGS="Heartline/*:V AndroidRuntime:E WearableService:V WearableListenerService:V *:S"

header() { # serial name
  {
    echo "device : $(adb -s "$1" shell getprop ro.product.model | tr -d '\r') ($1)"
    echo "sdk    : $(adb -s "$1" shell getprop ro.build.version.sdk | tr -d '\r')"
    echo "app    : $(adb -s "$1" shell dumpsys package com.heartline.app | grep -m1 versionName | tr -d ' \r')"
    echo "time   : $(date '+%Y-%m-%d %H:%M:%S %z')"
  } > "$out/$2-info.txt"
}

pids=()
for pair in "phone:$phone" "watch:$watch"; do
  name="${pair%%:*}"
  serial="${pair#*:}"
  if [[ -z "$serial" ]]; then
    echo "no $name found (use -${name:0:1} SERIAL)"
    continue
  fi
  header "$serial" "$name"
  if [[ "$mode" == "live" ]]; then
    # A bigger ring buffer makes the full log survive longer too.
    adb -s "$serial" logcat -G 16M >/dev/null 2>&1 || true
    adb -s "$serial" logcat -v threadtime $TAGS > "$out/$name-heartline.log" &
    pids+=($!)
    adb -s "$serial" logcat -v threadtime -b all > "$out/$name-full.log" &
    pids+=($!)
    echo "$name ($serial): streaming to $out/"
  else
    adb -s "$serial" logcat -d -v threadtime $TAGS > "$out/$name-heartline.log"
    adb -s "$serial" logcat -d -v threadtime -b all > "$out/$name-full.log"
    echo "$name ($serial): saved to $out/"
  fi
done

if [[ "$mode" == "live" && ${#pids[@]} -gt 0 ]]; then
  echo "Test now. Press Ctrl+C when done."
  trap 'kill "${pids[@]}" 2>/dev/null; echo; echo "Logs in $out/"; exit 0' INT
  wait
fi
