#!/usr/bin/env bash
#
# install.sh — build Sui Manga Reader and install it straight onto your Android
# phone, no GitHub release needed. The Android answer to the iOS install.sh.
#
# Usage:
#   ./install.sh                     # build + install to every connected device
#                                    #   (USB, or a phone already on Wi-Fi adb)
#   ./install.sh 192.168.1.42        # connect to that phone over Wi-Fi, then install
#   ./install.sh 192.168.1.42:5555   # ...with an explicit adb port
#   ./install.sh pair 192.168.1.42:37000 483920
#                                    # one-time Wi-Fi pairing (Android 11+), then it
#                                    # remembers the phone for a bare ./install.sh
#
# Options:
#   --release      build the minified release APK instead of the debug APK
#   --no-launch    install but do not auto-launch the app
#
# The build shares the package name (moe.antimony.hoshi.debug) and signing key
# with every release of this fork, so it installs as an in-place update: your
# library, reading progress and dictionaries are kept.
#
# Wi-Fi setup (once per phone, cable-free thereafter):
#   Phone: Settings → Developer options → Wireless debugging → ON.
#   Tap "Pair device with pairing code" to see host:port and a 6-digit code, then:
#     ./install.sh pair <host:port> <code>
#   After that, "Wireless debugging" shows the phone's IP and adb port — run
#     ./install.sh <ip:port>
#   once; the address is remembered, so later updates are just ./install.sh.
#
set -euo pipefail
cd "$(dirname "$0")"

PACKAGE="moe.antimony.hoshi.debug"
ACTIVITY="moe.antimony.hoshi.MainActivity"
DEVICE_FILE="$HOME/.hoshi-android-device"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
err()  { printf '\033[31m%s\033[0m\n' "$*" >&2; }
die()  { err "$*"; exit 1; }

# --- Locate the Android SDK, adb and the NDK -------------------------------
find_sdk() {
  for dir in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
             "/opt/homebrew/share/android-commandlinetools" \
             "$HOME/Library/Android/sdk"; do
    [ -n "$dir" ] && [ -d "$dir/platform-tools" ] && { echo "$dir"; return; }
  done
  return 1
}
SDK="$(find_sdk || true)"
[ -n "$SDK" ] || die "Could not find the Android SDK (set ANDROID_HOME)."
ADB="$SDK/platform-tools/adb"
[ -x "$ADB" ] || die "adb not found at $ADB"

# gradle's default NDK path is stale on this machine; point it at the SDK's ndk.
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -d "$SDK/ndk" ]; then
    ANDROID_NDK_HOME="$(/bin/ls -d "$SDK"/ndk/*/ 2>/dev/null | sort | tail -1)"
    ANDROID_NDK_HOME="${ANDROID_NDK_HOME%/}"
  fi
fi
[ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ] \
  || die "No NDK found. Set ANDROID_NDK_HOME to your SDK's ndk/<version> dir."
export ANDROID_NDK_HOME

# --- One-time Wi-Fi pairing ------------------------------------------------
if [ "${1:-}" = "pair" ]; then
  host="${2:-}"; code="${3:-}"
  [ -n "$host" ] && [ -n "$code" ] || die "Usage: ./install.sh pair <host:port> <code>"
  bold "Pairing with $host"
  printf '%s\n' "$code" | "$ADB" pair "$host" || die "Pairing failed."
  bold "Paired. Now run:  ./install.sh <ip:adbPort>   (from Wireless debugging)"
  exit 0
fi

# --- Parse args ------------------------------------------------------------
CONFIG="debug"
LAUNCH=1
CONNECT=""
for arg in "$@"; do
  case "$arg" in
    --release) CONFIG="release" ;;
    --no-launch) LAUNCH=0 ;;
    --*) die "unknown option: $arg" ;;
    *) CONNECT="$arg" ;;
  esac
done

# A bare run reuses the last phone we connected to over Wi-Fi.
if [ -z "$CONNECT" ] && [ -f "$DEVICE_FILE" ]; then
  saved="$(cat "$DEVICE_FILE" 2>/dev/null || true)"
  if [ -n "$saved" ] && ! "$ADB" devices | grep -q "^$saved[[:space:]]*device"; then
    CONNECT="$saved"
  fi
fi

# --- Connect over Wi-Fi if asked -------------------------------------------
if [ -n "$CONNECT" ]; then
  case "$CONNECT" in *:*) target="$CONNECT" ;; *) target="$CONNECT:5555" ;; esac
  bold "Connecting to $target"
  if "$ADB" connect "$target" | grep -qiE "connected|already"; then
    echo "$target" > "$DEVICE_FILE"
  else
    err "Could not connect to $target."
    err "On the phone: Settings → Developer options → Wireless debugging → ON,"
    err "then pair once with:  ./install.sh pair <host:port> <code>"
    exit 1
  fi
fi

# --- Pick target devices ---------------------------------------------------
DEVICES=()
while IFS= read -r serial; do
  [ -n "$serial" ] && DEVICES+=("$serial")
done < <("$ADB" devices | awk '/\tdevice$/{print $1}')
[ "${#DEVICES[@]}" -gt 0 ] || die "No devices. Plug in over USB (allow debugging) or use Wi-Fi (see the header)."

# --- Build -----------------------------------------------------------------
if [ "$CONFIG" = "release" ]; then
  APK="app/build/outputs/apk/release/app-release.apk"
  TASK=":app:assembleRelease"
else
  APK="app/build/outputs/apk/debug/app-debug.apk"
  TASK=":app:assembleDebug"
fi
bold "Building the $CONFIG APK"
./gradlew "$TASK" --console=plain >/dev/null || die "Build failed. Re-run '$ADB' aside, or ./gradlew $TASK to see the error."
[ -f "$APK" ] || die "Build finished but $APK is missing."

# --- Install + launch on each device ---------------------------------------
fail=0
for serial in "${DEVICES[@]}"; do
  name="$("$ADB" -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  bold "▶ $serial ${name:+($name)}"
  # -r reinstall keeping data, -d allow version downgrade (dev builds go back and forth).
  if "$ADB" -s "$serial" install -r -d "$APK" >/dev/null 2>install.err; then
    echo "  installed"
    rm -f install.err
  else
    err "  install failed:"
    sed 's/^/    /' install.err >&2 || true
    rm -f install.err
    fail=1
    continue
  fi
  if [ "$LAUNCH" -eq 1 ]; then
    "$ADB" -s "$serial" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
      || "$ADB" -s "$serial" shell am start -n "$PACKAGE/$ACTIVITY" >/dev/null 2>&1 || true
    echo "  launched"
  fi
done

[ "$fail" -eq 0 ] && bold "Done." || die "Some installs failed (see above)."
