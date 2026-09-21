#!/usr/bin/env bash
# Captures the log of one "send a message, immediately background the app" run.
#
# The interesting evidence is split across three questions:
#   1. did the generation foreground service ever reach ACTIVE, and when;
#   2. did the stream break, and did the provider-level resume run afterwards;
#   3. was the app process still alive when it broke (a dead PID explains a lot).
#
# Usage:
#   tools/capture-bg-stream-log.sh            # wait for the next app start, then record
#   tools/capture-bg-stream-log.sh --now      # record immediately
#
# Then in the app: send a message and switch to the home screen right away.
# Stop with Ctrl-C; the transcript is written to /tmp/bingo-bg-stream.log

set -uo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG="${PKG:-me.rerere.rikkahub.debug}"
OUT="${OUT:-/tmp/bingo-bg-stream.log}"

"$ADB" wait-for-device

if [[ "${1:-}" != "--now" ]]; then
  echo "Waiting for $PKG to start; then send a message and background the app immediately."
  "$ADB" shell "while ! pidof $PKG >/dev/null 2>&1; do sleep 0.3; done"
fi

PID_BEFORE="$("$ADB" shell pidof "$PKG" | tr -d '\r')"
echo "app pid before run: ${PID_BEFORE:-<not running>}"

{
  echo "=== capture started $(date '+%F %T') pid=${PID_BEFORE:-none} ==="
} > "$OUT"
echo "Writing to $OUT — press Ctrl-C when the failure has appeared in the app."

# Clear once so the transcript only covers this run, then stream everything relevant.
"$ADB" logcat -c
"$ADB" logcat -v threadtime \
  StreamResume:V GenerationFg:V ChatService:V \
  ActivityManager:I AndroidRuntime:E \
  libc:F DEBUG:F \
  '*:S' | tee -a "$OUT"

echo
echo "--- process liveness check ---"
PID_AFTER="$("$ADB" shell pidof "$PKG" | tr -d '\r')"
echo "app pid after run: ${PID_AFTER:-<process was killed>}"
echo "pid ${PID_BEFORE:-none} -> ${PID_AFTER:-none}" >> "$OUT"
