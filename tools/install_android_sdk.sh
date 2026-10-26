#!/usr/bin/env bash
# Instalación local (sin root) de JDK 21 + Android SDK cmdline-tools en ~/Android
set -euo pipefail

ROOT="$HOME/Android"
DL="$ROOT/dl"
SDK="$ROOT/Sdk"
mkdir -p "$DL" "$SDK"

echo "== JDK 21 =="
if [ ! -x "$ROOT/jdk21/bin/java" ]; then
  curl -fL --retry 3 -o "$DL/jdk21.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk"
  mkdir -p "$ROOT/jdk21"
  tar xzf "$DL/jdk21.tar.gz" -C "$ROOT/jdk21" --strip-components=1
fi
export JAVA_HOME="$ROOT/jdk21"
export PATH="$JAVA_HOME/bin:$PATH"
java -version

echo "== cmdline-tools =="
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fL --retry 3 -o "$DL/cmdline-tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip"
  rm -rf "$SDK/cmdline-tools/tmp"
  mkdir -p "$SDK/cmdline-tools/tmp"
  unzip -q "$DL/cmdline-tools.zip" -d "$SDK/cmdline-tools/tmp"
  mv "$SDK/cmdline-tools/tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rmdir "$SDK/cmdline-tools/tmp"
fi

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"

echo "== licencias =="
yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses > /dev/null || true

echo "== paquetes =="
"$SDKMANAGER" --sdk_root="$SDK" --install \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0"

echo "== listo =="
"$SDKMANAGER" --sdk_root="$SDK" --list_installed
