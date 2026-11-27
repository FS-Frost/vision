#!/usr/bin/env bash
# Construye el APK sin Gradle ni IDE: solo herramientas del Android SDK + ARCore vendorizado.
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

OUT="build"
rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/apkroot/lib/arm64-v8a"

echo "[0/8] ARCore         - vendorizando $ARCORE_VERSION (si falta)"
./tools/vendor-arcore.sh

if [ ! -f "$KEYSTORE" ]; then
  echo "      keytool        - creando la keystore de depuracion"
  mkdir -p "$(dirname "$KEYSTORE")"
  "$KEYTOOL" -genkeypair -v -keystore "$KEYSTORE" \
    -storepass "$KS_PASS" -keypass "$KS_PASS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi

echo "[1/8] aapt2 compile  - recursos de la app y de ARCore"
"$AAPT2" compile --dir src/main/res -o "$OUT/res/app.zip"
"$AAPT2" compile --dir "$ARCORE_DIR/res" -o "$OUT/res/arcore.zip"

echo "[2/8] aapt2 link     - recursos + manifest (R de la app y de com.google.ar.core)"
"$AAPT2" link \
  -I "$ANDROID_JAR" \
  --manifest src/main/AndroidManifest.xml \
  --java "$OUT/gen" \
  --extra-packages com.google.ar.core \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --replace-version \
  -o "$OUT/app-unaligned.apk" \
  "$OUT/res/arcore.zip" "$OUT/res/app.zip"

echo "[3/8] javac          - fuentes Java"
find src/main/java "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
if command -v cygpath >/dev/null 2>&1; then
  BOOTCP="$(cygpath -w "$ANDROID_JAR");$(cygpath -w "$CORE_LAMBDA_STUBS")"
  CP="$BOOTCP;$(cygpath -w "$ARCORE_DIR/classes.jar")"
  export MSYS2_ARG_CONV_EXCL='*'
else
  BOOTCP="$ANDROID_JAR:$CORE_LAMBDA_STUBS"
  CP="$BOOTCP:$ARCORE_DIR/classes.jar"
fi
# -encoding UTF-8 obligatorio (sin el, javac en Windows lee Cp1252 y los literales salen mal).
"$JAVAC" \
  -source 8 -target 8 -Xlint:-options -nowarn \
  -encoding UTF-8 \
  -bootclasspath "$BOOTCP" \
  -classpath "$CP" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"
unset MSYS2_ARG_CONV_EXCL || true

echo "[4/8] d8             - .class + ARCore -> classes.dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
# Las referencias de ARCore a androidx.annotation son solo anotaciones: d8 las avisa y sigue.
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --min-api "$MIN_SDK" \
  --lib "$ANDROID_JAR" \
  --output "$OUT/dex" \
  @"$OUT/classes.txt" "$ARCORE_DIR/classes.jar" 2>&1 | grep -v -E "androidx/annotation|^Warning in .*classes.jar|Missing class" || true
[ -f "$OUT/dex/classes.dex" ] || { echo "d8 no genero classes.dex" >&2; exit 1; }

echo "[5/8] aapt add       - classes.dex"
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"
( cd "$OUT" && "$AAPT" add -f app-unaligned.apk classes.dex >/dev/null )

echo "[6/8] aapt add       - assets/ y lib/arm64-v8a/*.so"
# "aapt add" usa la ruta tal cual como nombre de entrada: se llama desde el directorio padre.
if [ -d src/main/assets ]; then
  ( cd src/main && find assets -type f | sort | tr '\n' '\0' \
      | xargs -0 "$AAPT" add -f "../../$OUT/app-unaligned.apk" >/dev/null )
fi
cp "$ARCORE_DIR/lib/arm64-v8a/"*.so "$OUT/apkroot/lib/arm64-v8a/"
( cd "$OUT/apkroot" && find lib -type f | sort | tr '\n' '\0' \
    | xargs -0 "$AAPT" add -f ../app-unaligned.apk >/dev/null )

echo "[7/8] zipalign       - alineando"
"$ZIPALIGN" -f -p 4 "$OUT/app-unaligned.apk" "$OUT/app-aligned.apk"

echo "[8/8] apksigner      - firmando"
"$JAVA" -cp "$APKSIGNER_JAR" com.android.apksigner.ApkSignerTool sign \
  --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" --min-sdk-version "$MIN_SDK" \
  --out "$OUT/vision-debug.apk" "$OUT/app-aligned.apk"

echo
echo "OK -> $OUT/vision-debug.apk  (version $VERSION_NAME, code $VERSION_CODE)"
ls -la "$OUT/vision-debug.apk"
