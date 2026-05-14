#!/usr/bin/env bash
#
# bootstrap.sh — one-command dev environment setup for Hoshi Reader Android.
#
# Installs everything the build needs that does NOT travel through git:
#   - JDK 21 (Homebrew openjdk@21 formula — no sudo)
#   - Android command-line tools + SDK platform 36, platform-tools, build-tools
#   - Android NDK 28.2.13676358 (the version the Android Gradle Plugin pins) + CMake 3.22.1
#   - Rust toolchain + cargo-ndk + the Android Rust targets (for the hoshiepub UniFFI lib)
#   - git submodules (reference/Hoshi-Reader-iOS, third_party/hoshidicts-kotlin-bridge)
#
# It then writes `local.properties` and a sourceable `.bootstrap-env` (both gitignored).
#
# Usage:
#   ./bootstrap.sh
#   source ./.bootstrap-env      # then `./gradlew ...` works in this shell
#
# Re-runnable: every step is idempotent. macOS only (uses Homebrew); on Linux install the
# equivalent packages by hand.

set -u

# --- The NDK version the Android Gradle Plugin expects. If a build later fails with a
#     CXX1104 "NDK ... disagrees with android.ndkVersion" error, change this to the
#     version the error names and re-run. ---
NDK_VERSION="28.2.13676358"

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
log() { printf '\n\033[1;36m[bootstrap]\033[0m %s\n' "$*"; }
fail() { printf '\n\033[1;31m[bootstrap] FATAL:\033[0m %s\n' "$*"; exit 1; }

[ "$(uname)" = "Darwin" ] || fail "This script targets macOS (Homebrew). On Linux, install JDK 21, the Android cmdline-tools, NDK $NDK_VERSION, CMake 3.22.1 and Rust + cargo-ndk manually."
command -v brew >/dev/null 2>&1 || fail "Homebrew is required. Install it from https://brew.sh and re-run."

BREW_PREFIX="$(brew --prefix)"

log "1/7  git submodules"
git -C "$REPO" submodule update --init --recursive

log "2/7  JDK 21 (openjdk@21 formula)"
brew install openjdk@21
JAVA_HOME="$BREW_PREFIX/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
[ -x "$JAVA_HOME/bin/java" ] || fail "JDK 21 not found at $JAVA_HOME after install."

log "3/7  Android command-line tools"
brew install --cask android-commandlinetools
ANDROID_HOME="$BREW_PREFIX/share/android-commandlinetools"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
[ -x "$SDKMANAGER" ] || SDKMANAGER="$(command -v sdkmanager || true)"
[ -x "$SDKMANAGER" ] || fail "sdkmanager not found after installing android-commandlinetools."
export JAVA_HOME  # sdkmanager is a Java tool

log "4/7  accept SDK licenses"
yes 2>/dev/null | "$SDKMANAGER" --licenses --sdk_root="$ANDROID_HOME" >/dev/null 2>&1 || true

log "5/7  SDK packages (platform 36, platform-tools, build-tools, NDK $NDK_VERSION, CMake 3.22.1)"
BUILD_TOOLS="$("$SDKMANAGER" --list --sdk_root="$ANDROID_HOME" 2>/dev/null \
  | grep -oE 'build-tools;[0-9.]+' | sort -V | tail -1)"
[ -n "$BUILD_TOOLS" ] || BUILD_TOOLS="build-tools;36.0.0"
yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-36" "$BUILD_TOOLS" \
  "ndk;$NDK_VERSION" "cmake;3.22.1" 2>&1 | tail -3
ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"
[ -d "$ANDROID_NDK_HOME" ] || fail "NDK $NDK_VERSION did not install at $ANDROID_NDK_HOME."

log "6/7  Rust + cargo-ndk + Android targets"
if ! command -v rustup >/dev/null 2>&1 && [ ! -x "$HOME/.cargo/bin/rustup" ]; then
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
fi
export PATH="$HOME/.cargo/bin:$PATH"
command -v cargo-ndk >/dev/null 2>&1 || cargo install cargo-ndk
rustup target add aarch64-linux-android x86_64-linux-android

log "7/7  write local.properties + .bootstrap-env"
# local.properties carries only sdk.dir — the NDK is resolved by android.ndkVersion, and
# pinning ndk.dir to a different version would break the build (CXX1104).
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$REPO/local.properties"
# The Rust/UniFFI build reads ANDROID_NDK_HOME from the environment, so it must be exported
# when running gradle — source this file (or add the exports to your shell profile).
cat > "$REPO/.bootstrap-env" <<ENV
export JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME="$ANDROID_HOME"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_NDK_HOME"
export PATH="\$JAVA_HOME/bin:\$HOME/.cargo/bin:$ANDROID_HOME/platform-tools:\$PATH"
ENV

log "DONE. Next:"
echo "    source ./.bootstrap-env"
echo "    ./gradlew :app:assembleDebug          # build the debug APK"
echo "    ./gradlew :app:testDebugUnitTest      # run unit tests"
echo
echo "  Add the exports from .bootstrap-env to your shell profile to skip the source step."
