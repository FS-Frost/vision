# Vision Android: plan de implementación

**Estado:** plan, sin código todavía. Este documento se revisa antes de escribir código y se actualiza al cerrar cada fase.
**Rama:** `android` del repo `vision`. La app vive en `android/`, y la web de `main` no se toca.
**Dispositivo de referencia:** moto g54 5G: Android 15 (API 35), arm64-v8a, GPU PowerVR BXM-8-256 con OpenGL ES 3.2, ARCore 1.57 instalado.

## 1. Objetivo

Llevar el escáner de `vision` a una app **Android nativa** que haga todo lo que hace la web (escanear, guardar, ver, caminar, continuar con anclas, compartir y exportar) y aproveche lo que el navegador no daba:

| Limitación en la web (medida) | Qué ofrece Android nativo |
|---|---|
| Profundidad **suavizada** de 160×90, sin confianza. Entre sesiones difiere ±10–15 cm a 1,5 m | **Raw Depth API** de ARCore: profundidad sin suavizar más una imagen de **confianza** por píxel, para ponderar la fusión |
| Sin WebGPU en la PowerVR: fusión en CPU (JS), con el mallado en cola a 3 cm | **Compute shaders de OpenGL ES 3.1** (el g54 tiene GLES 3.2): fusión y mallado en GPU |
| Color leído a 120 px de ancho | Imagen de CPU de ARCore en **resolución alta** |
| Cada prueba exigía moverse con el teléfono | **Recording & Playback** de ARCore: grabar una sesión a MP4 y reproducirla como si fuera la cámara, para pruebas repetibles |
| Caché de Pages, versionado de módulos, IndexedDB | APK firmado y versionado, almacenamiento en archivos propios |

## 2. Decisiones (Acuerdo del 2026-11-27)

- **Toolchain estilo `linker`:** sin Gradle, sin Android Studio, sin AndroidX ni Kotlin. Java (fuente 8), `build.sh` con aapt2 → javac → d8 → zipalign → apksigner, y `env.sh` como única fuente de rutas. Se copian y adaptan `build.sh`, `env.sh` y `run.sh` de `linker`.
- **Única dependencia externa: ARCore**, vendorizado por script (sección 5). No se sube al repo.
- **Fusión en GPU desde el inicio** (compute GLES 3.1), con una **implementación de referencia en CPU** (Java puro, sin Android). Esa referencia se prueba en el PC con tests JVM, sirve para comparar contra la GPU en el teléfono y es el respaldo si el dispositivo no tiene GLES 3.1.
- **Todo nativo:** AR, visor y paseo con OpenGL ES propio y Views de Android. Sin WebView.
- **Mejoras de plataforma incluidas:** profundidad raw con confianza, grabar y reproducir sesiones, y cámara en alta resolución. **Fuera:** Cloud Anchors (requieren clave de Google Cloud y envían datos a Google), NDK/C++, Gradle, Filament y SceneView.
- **Por fases, cada una con un APK usable** y probado en el g54.
- **Escaneos de la web:** solo se **importan** `.glb`/`.html` para verlos y caminarlos. No se migra IndexedDB ni se continúan.

### Valores propuestos (corregibles al revisar este plan)

| Dato | Propuesta | Por qué |
|---|---|---|
| Paquete | `org.frost.vision` | Coherente con `org.frost.linker` |
| minSdk / targetSdk | 24 / 36 | 24 es el mínimo de ARCore; 36 = `platforms;android-36` instalado |
| ABI | solo `arm64-v8a` | El g54; reduce el APK |
| ARCore | **requerido** (`com.google.ar.core` = `required`) | La app no tiene sentido sin AR |
| Almacenamiento | `filesDir/scans/<id>/` | Privado de la app; exportar y compartir con un ContentProvider propio |
| `.html` compartido | Mismo visor three.js por CDN que la web | Es un formato de salida para quien lo recibe, no parte de la app |

## 3. Verificado antes de planificar

| Comprobación | Resultado |
|---|---|
| Versión de ARCore en Google Maven | **1.57.0** (`com.google.ar:core`) |
| SHA-256 de `core-1.57.0.aar` | `df53bb8ea8e3a9f32e256b91909959d60b2e4c60d8fcc56b905b4c3e926f5c72` |
| Contenido del AAR | `classes.jar`; `jni/<abi>/libarcore_sdk_{c,jni}.so` (arm64: 68 KB + 101 KB); `res/` (layout `__arcore_education`, `raw/keep_arcore`, strings en decenas de idiomas); `R.txt` (5 ids); `AndroidManifest.xml` (`minSdk` 24, `queries`, `meta-data` `min_apk_version` 262070000, `InstallActivity`) |
| Dependencias del POM | `androidx.annotation:annotation:1.3.0`. 76 clases de `classes.jar` la referencian: son anotaciones, no se necesitan en ejecución (ver riesgo R2) |
| GLES del g54 | `OpenGL ES 3.2 build 1.15@6133110` (`ro.opengles.version` = 196610): compute shaders y SSBO disponibles |
| ARCore en el g54 | `com.google.ar.core` 1.57 instalado |
| SDK de esta máquina | `build-tools 36.0.0`, `platforms android-36`, `platform-tools`. Sin NDK (no hace falta) |

## 4. Arquitectura

```
ARCore Session (Java, hilo GL)
  ├─ pose 6DoF por frame                     Camera.getPose / getDisplayOrientedPose
  ├─ profundidad raw + confianza (~30 Hz)    Frame.acquireRawDepthImage16Bits / acquireRawDepthConfidenceImage
  ├─ imagen de cámara en alta resolución     Frame.acquireCameraImage (YUV) o textura externa de la cámara
  └─ grabación / reproducción (MP4)          Session.startRecording / setPlaybackDatasetUri
        │
        ▼
FusionEngine (interfaz)  ── GpuFusion (GLES 3.1 compute)  |  CpuFusion (Java, referencia y respaldo)
  ├─ asignación de bloques 8³          CPU: qué bloques toca el frame (rayos a baja resolución) → tabla en SSBO
  ├─ integración TSDF proyectiva       por voxel: proyecta en la imagen de profundidad; peso = confianza × peso por distancia
  ├─ color por voxel                   muestreo de la imagen de cámara en alta resolución
  └─ Surface Nets por bloque sucio     vértices/índices a SSBO con contadores atómicos → se dibujan sin leerlos a la CPU
        │
        ▼
Render GLES 3.x (ARRenderer)   fondo de cámara + malla en vivo + mirilla + semáforo (shader con la profundidad en vivo)
Visor (ViewerRenderer)         orbitar / caminar (WalkControls + Collider), color o sombreado
ScanStore                      archivos por escaneo (meta.json, malla, volumen, fotos de anclas)
Export                         GLB (escritor propio), .html autocontenido, importación de .glb/.html
```

### Cambio algorítmico importante: integración **proyectiva**

La web integra **por rayo**: cada píxel recorre su banda de truncamiento. En GPU es más natural integrar **por voxel**, como KinectFusion: cada voxel de un bloque asignado se proyecta en la imagen de profundidad y actualiza su TSDF. Los resultados no son idénticos bit a bit. Por eso:

- La **referencia CPU implementa el mismo algoritmo proyectivo** que la GPU (no el de la web), así la comparación GPU contra CPU tiene sentido.
- Los tests de la web se **portan como propiedades** (superficie en su sitio, normales, costuras sin huecos, color orientado, limpieza de fragmentos, congelación al continuar, anclas, relocalización…), no como valores exactos.

## 5. Toolchain y estructura

### Estructura de `android/`

```
android/
  PLAN.md                 este plan
  CLAUDE.md               reglas operativas de la rama (para agentes)
  env.sh                  rutas y versiones (copia adaptada de linker)
  build.sh                pipeline completo -> build/vision-debug.apk
  run.sh                  adb install -r + am start
  test.sh                 tests JVM de la referencia CPU (javac + java, sin android.jar)
  tools/vendor-arcore.sh  descarga core-<versión>.aar, verifica el SHA-256 y lo desempaqueta en third_party/
  third_party/            (ignorado por git) arcore/<versión>/{classes.jar, res/, jni/arm64-v8a/*.so, R.txt}
  keystore/debug.keystore firma de depuración versionada (como linker)
  src/main/
    AndroidManifest.xml   incluye a mano lo que exige el manifest de ARCore
    java/org/frost/vision/
      core/               Java puro, sin android.*: Fusion (CPU), SurfaceNets, Reloc, Anchors, Collider, Glb, Volume
      gpu/                GpuFusion, shaders, buffers
      ar/                 sesión ARCore, grabación/reproducción, ARRenderer, mirilla
      ui/                 Activities y Views: Inicio, Escanear, Visor, Paseo
      store/              ScanStore, importar/exportar, ContentProvider para compartir
    res/                  layouts, strings (español), temas
    assets/shaders/       *.comp / *.vert / *.frag
    assets/share/         plantilla del visor .html
  src/test/java/          tests JVM de core/
```

### Pipeline (`build.sh`), diferencias con `linker`

1. `tools/vendor-arcore.sh` (si falta `third_party/arcore/1.57.0`): descarga, verifica el SHA-256 y descomprime.
2. `aapt2 compile` de `src/main/res` **y** de `third_party/arcore/.../res`.
3. `aapt2 link` con ambos zips y `--extra-packages com.google.ar.core`, para que exista `com.google.ar.core.R`, que `classes.jar` usa (ver R1).
4. `javac` con `classes.jar` de ARCore en el classpath (más `androidx-annotation.jar` solo si hace falta, R2).
5. `d8` de nuestras clases **más** `classes.jar` de ARCore.
6. `aapt add` de `classes.dex`, `assets/` y `lib/arm64-v8a/libarcore_sdk_{c,jni}.so`, con `android:extractNativeLibs="true"` para no depender de la alineación de página de las `.so`.
7. `zipalign` → `apksigner`, igual que `linker`.

Se heredan las lecciones de `linker`: `-encoding UTF-8`, `cygpath` + `MSYS2_ARG_CONV_EXCL`, assets con `aapt add` (no `-A`), `zipalign` antes de firmar, versión desde git. Con `minSdk 24` ya hay `java.util.function`.

## 6. Equivalencia web → Android

| Web (`main`) | Android (`android/`) |
|---|---|
| `scan/fusion-core.js` (TSDF, Surface Nets, limpieza, ICP, `scorePose`) | `core/CpuFusion`, `core/SurfaceNets`, `core/Icp` (referencia) + `gpu/GpuFusion` y `assets/shaders/{integrate,mesh}.comp` |
| `scan/fusion-worker.js` | Hilo de fusión + cola de frames (`ar/FusionThread`) |
| `scan/reloc.js` (búsqueda 3D, visibilidad, coincidencia en vivo, suelo, `startGuess`, `solveAnchors`) | `core/Reloc`, `core/Anchors` |
| `scan/main.js` (sesión XR, color, HUD, anclas, confirmación, visor) | `ui/ScanActivity` + `ar/ARRenderer` + `ui/ConfirmOverlay`; `ui/ViewerActivity` + `ui/ViewerRenderer` |
| Shader semáforo (`materials.semaforo`) | `assets/shaders/semaforo.vert/.frag` con la profundidad raw en vivo como textura |
| `scan/walk.js`, `scan/collider.js` | `ui/WalkControls`, `core/Collider` |
| `scan/db.js` (IndexedDB) | `store/ScanStore` (archivos) |
| `scan/share.js` (visor `.html`, importar) | `store/HtmlExporter`, `core/GlbWriter`, `core/GlbReader`, `store/ShareProvider` |
| `diag/index.html` | `ui/DiagActivity` (F4, opcional) |
| `tests/fusion.test.mjs` | `src/test/java/...` (propiedades portadas) + pruebas en el dispositivo GPU contra CPU |

## 7. Fases

Cada fase termina con: `build.sh` en 0, `apksigner verify` correcto, APK instalado en el g54, criterio de aceptación verificado (adb + captura + `logcat`) y este plan actualizado.

### F0: Esqueleto y ARCore

**Entregables:**
- `env.sh`, `build.sh`, `run.sh`, `test.sh` y `tools/vendor-arcore.sh`.
- Manifest con permiso de cámara, `uses-feature` AR, `meta-data` de ARCore y `InstallActivity`.
- `ScanActivity` mínima: comprobar ARCore (`ArCoreApk.checkAvailability` / `requestInstall`), pedir permiso de cámara, sesión con Raw Depth activado, fondo de cámara en GLSurfaceView y HUD con fps, tracking, tamaño de la profundidad raw y % de confianza media.
- Botones **Grabar** y **Reproducir** (Recording & Playback a `filesDir/recordings/`).

**Criterio de aceptación (g54):**
- Cámara a **≥ 28 fps**; tracking 6DoF.
- `logcat` muestra la profundidad raw (resolución, ~Hz) y la confianza.
- Una grabación de 30 s se reproduce y da poses y profundidad.

**Riesgos que valida:** R1, R2, R3.

### F1: Escanear, guardar y ver

**Entregables:**
- `core/CpuFusion` + `core/SurfaceNets` (proyectivo, bloques 8³, voxel 3 cm, peso = confianza × peso por distancia, color de la imagen en alta resolución, peso mínimo, dilatación, limpieza de fragmentos).
- `gpu/GpuFusion` con el mismo algoritmo.
- Malla en vivo sobre la cámara; guía de cobertura.
- `store/ScanStore` (guardar y listar).
- `ui/ViewerActivity` (orbitar, color o sombreado).
- Tests JVM: superficie, normales, costuras, color, limpieza y rendimiento de la referencia.
- **Prueba en el dispositivo GPU contra CPU** sobre una grabación (pantalla oculta «Pruebas», resultado en `logcat`).

**Criterio de aceptación (g54):**
- Escaneo de 1 min a **≥ 28 fps** de render.
- Integración en GPU **≤ 5 ms** por frame a ≥ 10 Hz. La web tardaba 10–18 ms en CPU.
- Sin cola de mallado: **0 bloques pendientes** 1 s después de dejar de moverse.
- GPU contra CPU sobre la misma grabación: **≥ 99 %** de los vértices a menos de 1 voxel.
- Guardar → cerrar la app → reabrir: el escaneo se ve igual.

**Medición (no es criterio de aprobación):** consistencia entre sesiones con raw + confianza frente a los ±10–15 cm de la web. Se mide sobre dos grabaciones del mismo sitio.

### F2: Continuar un escaneo

**Entregables:**
- Pista de inicio.
- **Anclas**: mirilla con profundidad raw y solo píxeles de alta confianza; foto; distancia y altura; comprobaciones al marcar.
- `core/Anchors.solveAnchors` + ICP local.
- Volumen congelado (solo zonas nuevas).
- Confirmación con **semáforo**, parpadeo y recomendación.
- Búsqueda global de respaldo (`core/Reloc`, en CPU).
- Las ampliaciones se guardan como escaneo nuevo.
- Tests JVM portados: anclas, congelación, relocalización sintética, suelo y coincidencia en vivo.

**Criterio de aceptación (g54):**
- Con 3 anclas, desde otro punto: residuos **≤ 18 cm** y coincidencia en vivo **≥ 57 %**. Es el resultado de la web: debe igualarse o mejorar.
- Con dos grabaciones del mismo sitio, la continuación es **reproducible offline** (misma pose ±1 cm al repetir).

### F3: Paseo, compartir, exportar e importar

**Entregables:**
- Paseo en primera persona con joystick y colisiones (`core/Collider`).
- Exportar `.glb` (`core/GlbWriter`) y compartir `.html` autocontenido (Intent `ACTION_SEND` + `ShareProvider`).
- **Importar** `.glb`/`.html` de la web (`core/GlbReader`) para verlos y caminarlos.
- Borrar y renombrar escaneos.

**Criterio de aceptación (g54):**
- Un `.html` compartido desde la app se abre en el navegador de un PC.
- Un `.glb` exportado por la web se importa y se ve con su color.
- En el paseo no se atraviesan paredes.

### F4: Mejoras

Del roadmap del README de `main`, priorizadas tras F3:
- Texturas con la imagen en alta resolución.
- Simplificación de mallas grandes.
- Sugerencia automática de buenas anclas.
- Pantalla de diagnóstico.
- CI que construya el APK al empujar una etiqueta `android-v*` (como `linker`).

## 8. Verificación

- **PC:** `./test.sh` compila `core/` y `src/test/java` con `javac` y los ejecuta con `java`, sin Android. Equivale a los 20 tests de Node, portados como propiedades.
- **Teléfono:**
  - `./build.sh && ./run.sh`.
  - Pantalla oculta «Pruebas»: GPU contra CPU y tiempos, sobre grabaciones reproducidas, con salida JSON por `logcat`.
  - Captura con `adb exec-out screencap -p`.
- **Datos reales:** grabaciones (MP4 de ARCore) en el teléfono, que se descargan con `adb pull` para análisis. **No se suben al repo** (tamaño y privacidad).

## 9. Riesgos

| # | Riesgo | Mitigación |
|---|---|---|
| R1 | Los **recursos del AAR** (layout, strings, `R.txt`) exigen generar `com.google.ar.core.R` sin Gradle | `aapt2 compile` de su `res/` + `aapt2 link --extra-packages com.google.ar.core`. Se valida en F0: si `InstallActivity` o el diálogo educativo fallan, se nota ahí |
| R2 | `classes.jar` referencia `androidx.annotation` (76 clases) | Son anotaciones; `javac`/`d8` deberían tolerar su ausencia con avisos. Si no, se vendoriza `androidx.annotation` 1.3.0 (jar diminuto, Java puro) con el mismo script y hash |
| R3 | **Fusión manual del manifest** de ARCore (`queries`, `meta-data`, `InstallActivity`) | Copiarlo literal y documentado en `android/CLAUDE.md`; comprobar con `aapt2 dump xmltree` |
| R4 | **Drivers de compute GLES** de PowerVR: precisión, atómicos, límites de SSBO | Comprobar `GL_MAX_SHADER_STORAGE_BLOCK_SIZE`, `GL_MAX_COMPUTE_WORK_GROUP_*` en F0. Comparar contra la referencia CPU en F1. Respaldo: CPU |
| R5 | **Voxel hashing en GPU** (asignación dinámica de bloques) es complejo | Asignación en CPU (tabla de bloques activos subida por frame) y solo integración y mallado en GPU |
| R6 | Reescribir **visor y paseo** en OpenGL ES propio es mucho trabajo | Va en F1 (visor básico) y F3 (paseo). Se reutilizan la lógica de `Collider` y los parámetros de la web |
| R7 | **Recording & Playback**: la reproducción exige la misma configuración de cámara y puede no incluir Raw Depth en todos los dispositivos | Validar en F0 que la reproducción da profundidad raw; si no, grabar también la profundidad en un archivo propio |
| R8 | **Licencia de ARCore**: «ARCore Additional Terms of Service» | No subir el AAR (el script lo descarga). Si algún día se publica, revisar los requisitos de aviso de privacidad de ARCore |
| R9 | Alcance total grande | Fases con APK usable: se puede frenar en cualquier fase con algo que funciona |

## 10. Pendiente de decidir al revisar

- Confirmar los valores propuestos de la sección 2 (paquete, minSdk, ABI, almacenamiento).
- Nombre visible de la app e icono.
- Si F4 incluye CI por etiqueta o se deja manual.
