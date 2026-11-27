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

## Arquitectura (decidida al implementar)

- **Hilos:** render en el `GLSurfaceView`; fusión en `ar/FusionWorker`, un hilo con **su propio contexto EGL** (`gpu/EglThread`, pbuffer 1×1). Todo acceso a `Fusion` se hace con `worker.run(...)`. Si un frame llega con el hilo ocupado, se descarta (no hay cola).
- **GPU integra, CPU malla:** `GpuFusion` integra en un pool de bloques en SSBO (los huecos no se liberan) y `sync()` copia los bloques cambiados al espejo `Fusion.volume`. Surface Nets, guardado, nube y relocalización trabajan sobre el espejo.
- **Captura (`ar/ArCapture`):** solo se usa la profundidad cuyo timestamp coincide con el del frame de cámara (pose exacta). Pose = `Camera.getPose()` (orientación del sensor, no de la pantalla). Intrínsecos de la **textura** escalados a la profundidad; el color usa los de la **imagen de CPU**. Convención de core/: centro del píxel en i + 0,5, así que `cx = (cx_arcore + 0,5) · escala`.
- **Orden determinista:** `Integrator.touchedBlocks` devuelve claves ordenadas y `Fusion.cloudFrom` ordena por celda. ICP y puntuaciones son sensibles al orden de suma en coma flotante: no reintroduzcas iteración sobre tablas hash en esos caminos.
- **Edge-to-edge:** con targetSdk 36 Android dibuja bajo las barras. Los controles van en una capa con `Ui.insets`; la mirilla, no (debe quedar en el centro exacto de la vista GL, que es el punto que se mide).
- **Almacenamiento:** `filesDir/scans/<id>/{meta.json, mesh.glb, volume.bin, start.jpg, anchorN.jpg}`; grabaciones en `filesDir/recordings/` (`.mp4` de ARCore + `.frames` propio). Compartir sale por `store/ShareProvider` (`cacheDir/share/`).
- **minSdk 24:** nada de `java.util.Base64`, `String.join` ni otras APIs ≥ 26 (base64 propio en `core/ShareHtml`).

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
3. En el g54: captura (`adb exec-out screencap -p`), `logcat` (etiquetas `VISION`, `VISION_TEST`, `VISION_DIAG`) y la pantalla «Pruebas» (`./run.sh TestsActivity`): GPU contra CPU sobre la escena sintética y sobre la última grabación `.frames`. Criterio: `pass: true` (≥ 99 % de vértices GPU a < 1 voxel). Las demás Activities no se exportan: se abren tocando con `adb shell input tap` (coordenadas con `uiautomator dump`).
4. Las grabaciones de prueba no se suben al repo (`adb pull` para analizar).
