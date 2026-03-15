#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
CMDLINE_VERSION="14742923"
CMDLINE_ZIP="commandlinetools-linux-${CMDLINE_VERSION}_latest.zip"
CMDLINE_URL="https://dl.google.com/android/repository/${CMDLINE_ZIP}"
TMP_DIR="$(mktemp -d)"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

mkdir -p "$SDK_ROOT/cmdline-tools"

# Clean up stale side-by-side directories that cause sdkmanager location warnings
rm -rf "$SDK_ROOT"/cmdline-tools/latest-* "$SDK_ROOT"/build-tools/*-2 "$SDK_ROOT"/build-tools/*-3 2>/dev/null || true

installed_version=""
if [[ -f "$SDK_ROOT/cmdline-tools/latest/source.properties" ]]; then
  installed_version="$(awk -F'= ' '/Pkg.Revision/ {print $2}' "$SDK_ROOT/cmdline-tools/latest/source.properties" || true)"
fi

if [[ "$installed_version" != "$CMDLINE_VERSION" ]]; then
  echo "Downloading Android command-line tools..."
  curl -fsSL "$CMDLINE_URL" -o "$TMP_DIR/$CMDLINE_ZIP"
  unzip -q "$TMP_DIR/$CMDLINE_ZIP" -d "$TMP_DIR"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mkdir -p "$SDK_ROOT/cmdline-tools/latest"
  cp -r "$TMP_DIR/cmdline-tools"/* "$SDK_ROOT/cmdline-tools/latest/"
fi

export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$PATH"

set +o pipefail
yes | sdkmanager --sdk_root="$SDK_ROOT" --licenses >/dev/null
set -o pipefail

sdkmanager --sdk_root="$SDK_ROOT" \
  "platform-tools" \
  "platforms;android-34" \
  "build-tools;34.0.0"

echo "Android SDK installed at: $SDK_ROOT"
echo "Set these env vars before building:"
echo "  export ANDROID_SDK_ROOT=$SDK_ROOT"
echo "  export ANDROID_HOME=$SDK_ROOT"
