#!/usr/bin/env bash
# Instala el APK en el dispositivo conectado y lanza la app (o la Activity indicada).
# Uso: ./run.sh [ActivityCorta]   p. ej. ./run.sh TestsActivity
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

if [ -z "$("$ADB" devices | sed '1d' | grep -w device || true)" ]; then
  echo "No hay dispositivo conectado (adb devices)." >&2
  exit 1
fi

echo "== instalando =="
# -d: el versionCode sale del numero de commits; una rama con menos commits no instalaria encima.
"$ADB" install -r -d build/vision-debug.apk

ACT="${1:-HomeActivity}"
echo "== lanzando .$ACT =="
"$ADB" shell am start -n "$PKG/.ui.$ACT"
