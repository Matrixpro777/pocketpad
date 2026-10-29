#!/usr/bin/env bash
# Compila el APK de PocketPad con el toolchain instalado en ~/Android.
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$HOME/Android/jdk21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

GRADLE="$HOME/Android/gradle/gradle-8.9/bin/gradle"
[ -x "$GRADLE" ] || { echo "Falta Gradle 8.9 en $GRADLE"; exit 1; }

printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
"$GRADLE" --console=plain "${@:-assembleDebug}"

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
  cp "$APK" ../pocketpad/web/pocketpad.apk
  echo
  echo "APK: $(pwd)/$APK"
  echo "También servido en http://<ip-pc>:8080/pocketpad.apk"
fi
