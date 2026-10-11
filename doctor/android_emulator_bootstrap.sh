#!/usr/bin/env bash
set -euo pipefail

# Start the Android emulator with bounded waits. This avoids an unbounded
# adb getprop loop when the emulator process never registers with ADB.
if [[ "${1:-}" != "--" ]]; then
  echo "Usage: bash doctor/android_emulator_bootstrap.sh -- command [args...]" >&2
  exit 2
fi
shift
if [[ $# -eq 0 ]]; then
  echo "No test command was supplied." >&2
  exit 2
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" ]]; then
  echo "ANDROID_SDK_ROOT and ANDROID_HOME are both unset." >&2
  exit 1
fi

ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR="$SDK_ROOT/emulator/emulator"
AVDMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
PACKAGE="system-images;android-35;google_apis;x86_64"
AVD_NAME="cloudstream-test"
PORT=5554
SERIAL="emulator-$PORT"
IMAGE_DIR="$SDK_ROOT/system-images/android-35/google_apis/x86_64"
LOG_DIR="${GITHUB_WORKSPACE:-$PWD}/doctor-results/android"
EMULATOR_LOG="$LOG_DIR/emulator.log"
LOGCAT_FILE="$LOG_DIR/emulator-logcat.txt"
mkdir -p "$LOG_DIR" "$HOME/.android/avd"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"

for executable in "$ADB" "$EMULATOR" "$AVDMANAGER"; do
  if [[ ! -x "$executable" ]]; then
    echo "Required Android SDK executable missing: $executable" >&2
    exit 1
  fi
done
if [[ ! -s "$IMAGE_DIR/system.img" || ! -f "$IMAGE_DIR/source.properties" ]]; then
  echo "Android 35 Google APIs x86_64 system image is missing or incomplete: $IMAGE_DIR" >&2
  exit 1
fi

# Use KVM when the hosted runner exposes it; otherwise use the emulator's
# software path and let the bounded startup timeout report the real failure.
if [[ -e /dev/kvm ]]; then
  sudo chmod 666 /dev/kvm 2>/dev/null || true
fi
if [[ -r /dev/kvm && -w /dev/kvm ]]; then
  ACCEL="on"
else
  ACCEL="off"
fi
echo "Android emulator acceleration: $ACCEL"
echo "Creating fresh AVD: $AVD_NAME"

printf 'no\n' | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$PACKAGE" -d pixel_5 -f

"$ADB" kill-server >/dev/null 2>&1 || true
"$ADB" start-server
rm -f "$EMULATOR_LOG" "$LOGCAT_FILE"

"$EMULATOR" \
  -avd "$AVD_NAME" \
  -port "$PORT" \
  -no-snapshot-load -no-snapshot-save -wipe-data \
  -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim \
  -camera-back none -camera-front none \
  -memory 2048 -cores 2 -accel "$ACCEL" \
  >"$EMULATOR_LOG" 2>&1 &
EMULATOR_PID=$!

cleanup() {
  local rc=$?
  trap - EXIT
  if [[ $rc -ne 0 ]]; then
    echo "Android test failed; collecting emulator diagnostics." >&2
    "$ADB" devices -l >&2 || true
    "$ADB" -s "$SERIAL" logcat -d -t 2000 >"$LOGCAT_FILE" 2>/dev/null || true
    tail -n 160 "$EMULATOR_LOG" >&2 || true
  fi
  "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
  kill -TERM "$EMULATOR_PID" >/dev/null 2>&1 || true
  wait "$EMULATOR_PID" >/dev/null 2>&1 || true
  exit "$rc"
}
trap cleanup EXIT

echo "Waiting up to 5 minutes for ADB to detect $SERIAL..."
DEVICE_FOUND=0
for attempt in $(seq 1 60); do
  if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
    echo "Emulator process exited before registering with ADB (attempt $attempt)." >&2
    exit 1
  fi
  if "$ADB" devices | awk -v serial="$SERIAL" '$1 == serial { found=1 } END { exit !found }'; then
    DEVICE_FOUND=1
    break
  fi
  if (( attempt % 6 == 0 )); then
    echo "ADB has not detected $SERIAL yet ($((attempt * 5)) seconds elapsed)."
    "$ADB" devices -l || true
  fi
  sleep 5
done
if [[ "$DEVICE_FOUND" -ne 1 ]]; then
  echo "Timed out after 5 minutes: ADB never detected $SERIAL. Refusing to loop indefinitely." >&2
  exit 1
fi

echo "Waiting up to 10 minutes for Android boot completion..."
BOOTED=0
for attempt in $(seq 1 120); do
  if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
    echo "Emulator process exited while Android was booting." >&2
    exit 1
  fi
  STATE="$("$ADB" devices | awk -v serial="$SERIAL" '$1 == serial { print $2; exit }')"
  if [[ "$STATE" == "device" ]]; then
    BOOT_STATUS="$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [[ "$BOOT_STATUS" == "1" ]]; then
      BOOTED=1
      break
    fi
  fi
  if (( attempt % 12 == 0 )); then
    echo "Android is not booted yet ($((attempt * 5)) seconds elapsed; ADB state: ${STATE:-missing})."
  fi
  sleep 5
done
if [[ "$BOOTED" -ne 1 ]]; then
  echo "Timed out after 10 minutes: Android did not report sys.boot_completed=1." >&2
  exit 1
fi

"$ADB" -s "$SERIAL" shell input keyevent 82 >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
echo "Android emulator boot completed. Starting test command."
"$@"
