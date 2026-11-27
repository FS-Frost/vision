#!/usr/bin/env bash
# Descarga el AAR de ARCore (version fijada en env.sh), verifica su SHA-256 y lo
# desempaqueta en third_party/arcore/<version>/. El AAR NO se versiona: el repo es
# publico y ARCore esta bajo los "ARCore Additional Terms of Service".
set -euo pipefail
cd "$(dirname "$0")/.."
source ./env.sh

if [ -f "$ARCORE_DIR/classes.jar" ] && [ -f "$ARCORE_DIR/.ok" ]; then
  exit 0
fi

URL="https://dl.google.com/dl/android/maven2/com/google/ar/core/$ARCORE_VERSION/core-$ARCORE_VERSION.aar"
TMP="$ARCORE_DIR.tmp"
rm -rf "$TMP"
mkdir -p "$TMP"
echo "== descargando ARCore $ARCORE_VERSION =="
curl -fsSL -o "$TMP/core.aar" "$URL"

GOT="$(sha256sum "$TMP/core.aar" | cut -d' ' -f1)"
if [ "$GOT" != "$ARCORE_SHA256" ]; then
  echo "SHA-256 inesperado para core-$ARCORE_VERSION.aar" >&2
  echo "  esperado: $ARCORE_SHA256" >&2
  echo "  obtenido: $GOT" >&2
  rm -rf "$TMP"
  exit 1
fi

( cd "$TMP" && unzip -q core.aar -d aar )
mkdir -p "$TMP/out/lib/arm64-v8a"
cp "$TMP/aar/classes.jar" "$TMP/out/"
cp "$TMP/aar/R.txt" "$TMP/aar/AndroidManifest.xml" "$TMP/out/"
cp -r "$TMP/aar/res" "$TMP/out/res"
cp "$TMP/aar/jni/arm64-v8a/"*.so "$TMP/out/lib/arm64-v8a/"

rm -rf "$ARCORE_DIR"
mkdir -p "$(dirname "$ARCORE_DIR")"
mv "$TMP/out" "$ARCORE_DIR"
rm -rf "$TMP"
touch "$ARCORE_DIR/.ok"
echo "ARCore $ARCORE_VERSION -> $ARCORE_DIR"
