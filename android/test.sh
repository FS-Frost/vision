#!/usr/bin/env bash
# Tests JVM de la referencia CPU (paquete core/, Java puro sin android.*).
# Compila core/ + src/test/java con javac y los ejecuta con java: no hace falta el SDK.
set -euo pipefail
cd "$(dirname "$0")"

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXE=".exe" ;; *) EXE="" ;; esac
if [ -n "${JAVA_HOME:-}" ]; then JDK="$JAVA_HOME"; else
  for d in "/c/Program Files/Eclipse Adoptium/jdk-"* "/c/Program Files/Microsoft/jdk-"* /usr/lib/jvm/*; do
    [ -x "$d/bin/javac$EXE" ] && JDK="$d" && break
  done
fi
JAVAC="$JDK/bin/javac$EXE"; JAVA="$JDK/bin/java$EXE"

OUT="build/test"
rm -rf "$OUT" && mkdir -p "$OUT"
find src/main/java/org/frost/vision/core src/test/java -name '*.java' > "$OUT/sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn -d "$OUT" @"$OUT/sources.txt"
"$JAVA" -cp "$OUT" org.frost.vision.core.TestMain "$@"
