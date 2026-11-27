# CLAUDE.md (android/)

Reglas operativas de la app Android nativa de `vision` (rama `android`). El plan completo, con fases, criterios y riesgos, está en [PLAN.md](PLAN.md). Las reglas de la web están en el `CLAUDE.md` de la raíz y siguen valiendo para `main`.

## Decisiones (Acuerdo del 2026-11-27, no las cambies sin pedirlo)

- **Toolchain estilo `linker`:** sin Gradle, sin Android Studio, sin AndroidX, Kotlin ni Compose. Java fuente 8. `build.sh`: aapt2 → javac → d8 → zipalign → apksigner. `env.sh` es la **única** fuente de rutas y versiones (resueltas desde `ANDROID_SDK_ROOT`/`ANDROID_HOME` y `JAVA_HOME`; en esta máquina `C:\android-sdk` y el JDK 17 de Adoptium). No propongas Gradle.
- **Única dependencia externa: ARCore** (`com.google.ar:core`, versión fijada en `env.sh`, hoy 1.57.0). Se descarga con `tools/vendor-arcore.sh`, que verifica el SHA-256, a `third_party/` (ignorado por git). **Nunca subas el AAR ni sus `.so` al repo:** el repo es público y el AAR está bajo los «ARCore Additional Terms of Service». Cualquier otra librería de Maven: avisa antes.
- **Fusión en GPU (compute GLES 3.1) con referencia en CPU** (Java puro en `core/`, sin `android.*`). La referencia implementa el **mismo algoritmo proyectivo** que la GPU y es el respaldo sin GLES 3.1. Todo cambio del algoritmo va en ambas, y la comparación GPU contra CPU debe seguir pasando.
- **Todo nativo:** OpenGL ES propio y Views. Sin WebView.
- **Mejoras de plataforma:** profundidad raw con confianza, Recording & Playback y cámara en alta resolución. **Fuera:** Cloud Anchors, NDK/C++.
- **Escaneos de la web:** solo se importan `.glb`/`.html` para verlos. No se continúan.
- **Se heredan de la web**, porque están medidos en el g54:
  - Continuar solo añade zonas nuevas (el volumen guardado queda congelado).
  - Las anclas son el método principal y la búsqueda global el respaldo.
  - Confirmación visual siempre (semáforo con ±15 cm, la app nunca acepta sola).
  - Suelo verificado contra la altura de la cámara (≥ 0,8 m).
  - Peso por distancia (1 hasta 1,5 m, 0 a 3 m).
  - Las ampliaciones se guardan como escaneo nuevo.

## Toolchain: tropiezos heredados de `linker`

- `javac` con **`-encoding UTF-8`**, bootclasspath `android.jar` + `core-lambda-stubs.jar` en formato Windows (`cygpath -w`, `;`) y `MSYS2_ARG_CONV_EXCL='*'`.
- `d8` y `apksigner` se llaman con `"$JAVA" -cp ... ToolClass`. No uses los `.bat`.
- Assets y `.so` entran con **`aapt add` desde el directorio padre** (no con `aapt2 link -A`: en Windows rompe las subcarpetas).
- `zipalign` **antes** de `apksigner`.
- La versión sale de git (`--replace-version`).
- `adb` no está en el PATH: `/c/android-sdk/platform-tools/adb.exe` (o `source env.sh`). Con rutas del teléfono, `MSYS_NO_PATHCONV=1`.
- No hay Python ni `zip` en esta máquina: usa Node, Bash o `aapt`.

## ARCore sin Gradle

- `aapt2 link` con los recursos del AAR y `--extra-packages com.google.ar.core` (genera `com.google.ar.core.R`, que `classes.jar` usa).
- El manifest del AAR se fusiona **a mano** en `src/main/AndroidManifest.xml`: los `queries` (`com.google.ar.core`, `com.android.vending`, intent `BIND_INSTALL_SERVICE`), el `meta-data` `com.google.ar.core.min_apk_version` y la `InstallActivity`. Además: `meta-data` `com.google.ar.core` = `required`, permiso `CAMERA`, `uses-feature` `android.hardware.camera.ar`. Al subir de versión de ARCore, vuelve a comparar su manifest.
- `.so` solo de `arm64-v8a`, con `android:extractNativeLibs="true"`.

## Verificación

1. `./test.sh`: tests JVM de `core/`.
2. `./build.sh && ./run.sh`, y `apksigner verify`.
3. En el g54: captura (`adb exec-out screencap -p`), `logcat` y la pantalla oculta «Pruebas» (GPU contra CPU sobre grabaciones).
4. Las grabaciones de prueba no se suben al repo (`adb pull` para analizar).
