# Toolchain de la app Android (estilo linker: sin Gradle, solo herramientas del SDK).
#
# Todo se resuelve del entorno para que el mismo script sirva en local y en CI.
# Para forzar rutas a mano, crea env.local.sh (ignorado por git).

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) EXE=".exe" ;;
  *)                    EXE="" ;;
esac

to_posix() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"; else printf '%s' "$1"; fi
}

# ------------------------------------------------------------------ Android SDK

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
  echo "Falta ANDROID_SDK_ROOT (o ANDROID_HOME) apuntando al Android SDK." >&2
  exit 1
fi
SDK="$(to_posix "$SDK")"

BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"
PLATFORM_VERSION="${PLATFORM_VERSION:-android-36}"
MIN_SDK="${MIN_SDK:-24}"        # minimo de ARCore
TARGET_SDK="${TARGET_SDK:-36}"

PKG="org.frost.vision"

# ARCore: unica dependencia externa. Se descarga y verifica con tools/vendor-arcore.sh.
ARCORE_VERSION="${ARCORE_VERSION:-1.57.0}"
ARCORE_SHA256="${ARCORE_SHA256:-df53bb8ea8e3a9f32e256b91909959d60b2e4c60d8fcc56b905b4c3e926f5c72}"
ANDROID_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
ARCORE_DIR="$ANDROID_ROOT/third_party/arcore/$ARCORE_VERSION"

# Version de la app desde git: versionCode = numero de commits; versionName = etiqueta
# android-vX.Y.Z si HEAD la tiene, si no "<ultima>-dev+<sha>[.dirty]".
if [ -z "${VERSION_CODE:-}" ]; then
  VERSION_CODE="$(git -C "$ANDROID_ROOT" rev-list --count HEAD 2>/dev/null || echo 1)"
fi
if [ -z "${VERSION_NAME:-}" ]; then
  _tag="$(git -C "$ANDROID_ROOT" describe --tags --exact-match --match 'android-v*' 2>/dev/null || true)"
  if [ -n "$_tag" ]; then
    VERSION_NAME="${_tag#android-v}"
  else
    _last="$(git -C "$ANDROID_ROOT" describe --tags --abbrev=0 --match 'android-v*' 2>/dev/null || true)"
    _sha="$(git -C "$ANDROID_ROOT" rev-parse --short HEAD 2>/dev/null || echo local)"
    _dirty="$(git -C "$ANDROID_ROOT" diff --quiet HEAD 2>/dev/null || echo .dirty)"
    _last="${_last#android-v}"
    VERSION_NAME="${_last:-0.0}-dev+${_sha}${_dirty}"
  fi
fi

BT="$SDK/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK/platforms/$PLATFORM_VERSION/android.jar"
CORE_LAMBDA_STUBS="$BT/core-lambda-stubs.jar"

AAPT="$BT/aapt$EXE"
AAPT2="$BT/aapt2$EXE"
ZIPALIGN="$BT/zipalign$EXE"
D8_JAR="$BT/lib/d8.jar"
APKSIGNER_JAR="$BT/lib/apksigner.jar"
ADB="$SDK/platform-tools/adb$EXE"

# -------------------------------------------------------------------------- JDK

_usable_jdk() {
  for dir in "$@"; do
    [ -x "$dir/bin/javac$EXE" ] || continue
    major="$("$dir/bin/javac$EXE" -version 2>&1 | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p')"
    if [ -n "$major" ] && [ "$major" -ge 11 ] 2>/dev/null; then
      printf '%s' "$dir"
      return 0
    fi
  done
  return 1
}

if [ -n "${JAVA_HOME:-}" ]; then
  JDK="$(to_posix "$JAVA_HOME")"
else
  JDK="$(_usable_jdk \
      "/c/Program Files/Microsoft/jdk-"* \
      "/c/Program Files/Eclipse Adoptium/jdk-"* \
      "/c/Program Files/Java/jdk-"* \
      /usr/lib/jvm/* \
      /Library/Java/JavaVirtualMachines/*/Contents/Home 2>/dev/null)" || JDK=""
fi

if [ -z "${JDK:-}" ] || [ ! -x "$JDK/bin/javac$EXE" ]; then
  echo "No se encontro un JDK utilizable. Define JAVA_HOME (hace falta 11 o superior)." >&2
  exit 1
fi

JAVA="$JDK/bin/java$EXE"
JAVAC="$JDK/bin/javac$EXE"
KEYTOOL="$JDK/bin/keytool$EXE"

# ---------------------------------------------------------------------- firma

# Keystore de depuracion versionada (claves publicas, solo depuracion): misma firma en
# cualquier maquina. Para otra, define KEYSTORE / KS_PASS / KS_ALIAS.
KEYSTORE="${KEYSTORE:-$ANDROID_ROOT/keystore/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KS_ALIAS="${KS_ALIAS:-androiddebugkey}"

if [ -f "$ANDROID_ROOT/env.local.sh" ]; then
  . "$ANDROID_ROOT/env.local.sh"
fi
