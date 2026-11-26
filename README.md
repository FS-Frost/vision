# Vision

Web app estática que escanea una habitación con la cámara del teléfono y construye progresivamente una escena 3D navegable. Todo se ejecuta en el navegador: sin servidor ni build.

**Sitio:** https://fs-frost.github.io/vision/

- [Escáner](https://fs-frost.github.io/vision/scan/): recorre la habitación, mira la malla crecer sobre la cámara y luego orbita o camina por el resultado con color real. Los escaneos se guardan en el teléfono y se exportan a `.glb`.
- [Diagnóstico](https://fs-frost.github.io/vision/diag/): comprueba las capacidades del dispositivo (WebGPU, WebXR/ARCore, profundidad, cámara, benchmarks).

## Estado

Prototipo funcional:

- Escaneo en vivo con color de cámara.
- Visor orbital y paseo en primera persona (joystick virtual) con colisiones.
- Guardado automático en IndexedDB, exportación `.glb` e importación de `.glb`/`.html`.
- Compartir como `.html` autocontenido (visor three.js + modelo en base64), que se abre en cualquier navegador.
- Continuar un escaneo guardado: se guarda el volumen TSDF y la sesión nueva se alinea con la malla guardada. La ampliación se guarda como escaneo nuevo y el original se conserva.
- Calidad: voxel de 3 cm, filtro de ruido, cierre de huecos pequeños y limpieza de fragmentos.

Limitaciones conocidas:

- Continuar exige empezar cerca del punto de inicio del escaneo original (unos 50 cm y 40°), porque la alineación no hace búsqueda global.
- Las superficies lisas sin textura (techo, paredes blancas) dan profundidad poco fiable y pueden generar capas duplicadas.

Ideas siguientes: texturas en lugar de color por vértice, relocalización global para continuar desde cualquier punto y simplificación de mallas grandes.

## Arquitectura

```
WebXR immersive-ar (ARCore)
  ├─ pose 6DoF por frame          (XRViewerPose, espacio 'local-floor' o 'local')
  ├─ profundidad CPU 160x90       (XRFrame.getDepthInformation, luminance-alpha)
  └─ imagen de cámara             (camera-access → render target 120 px → lectura asíncrona)
        │  ~10 Hz, ArrayBuffers transferidos
        ▼
Web Worker (scan/fusion-worker.js → scan/fusion-core.js)
  ├─ fusión TSDF dispersa         bloques 8³, voxel 3 cm, truncamiento 3 voxels, color sRGB por voxel
  ├─ Surface Nets por bloque      peso mínimo 2, dilatación de 1 voxel (ventana 12³), color por vértice
  └─ malla final                  elimina componentes conexas pequeñas
        │  geometrías por supercelda 4³ cada ~250 ms
        ▼
Hilo principal (scan/main.js, three.js 0.180)
  ├─ malla semitransparente sobre la cámara durante el escaneo
  ├─ visor: OrbitControls o paseo (walk.js), color sin iluminación o sombreado
  └─ guardado en IndexedDB (db.js) y GLTFExporter
```

### Decisiones

- **Fusión en CPU, no en WebGPU.** El dispositivo de referencia (moto g54, GPU PowerVR BXM-8-256) no obtiene adapter WebGPU en Chrome Android porque PowerVR está en la blocklist. Con profundidad de 160×90, la fusión en CPU tarda ~5–25 ms por frame en un worker.
- **three.js ≥ 0.175.** Las versiones anteriores llaman a `XRWebGLBinding.getDepthInformation` aunque la sesión use `cpu-optimized`, y eso rompe el bucle de render.
- **`getDepthInformation`**. Chrome 158 renombró `getDepthInfo`; el código acepta ambos nombres.

### Parámetros por URL (escáner)

| Parámetro | Por defecto | Efecto |
|---|---|---|
| `voxel` | selector (3 cm) | Tamaño de voxel en metros; sustituye al selector de calidad |
| `hz` | `10` | Frecuencia máxima de integración de profundidad |
| `colorTopDown` | `0` | `1` invierte la orientación vertical de la imagen de cámara (si el color sale volteado en otro dispositivo) |

Ejemplo: `scan/?voxel=0.025&hz=8`.

## Resultados en el moto g54 (Chrome 158, Android 15)

| Prueba | Resultado |
|---|---|
| Tracking 6DoF | OK, ~30 fps |
| Profundidad | 160×90, `luminance-alpha`, 100 % de píxeles válidos, ~28 Hz |
| Cámara | 864×1920 |
| WebGPU | Sin adapter (blocklist PowerVR) |
| Benchmark TSDF CPU | ~4 ms por 100k actualizaciones de voxel |
| Escaneo real (voxel 4 cm, sin color) | 171 frames, 203 bloques, ~15k triángulos, 0,8 MB |
| Escaneo real (voxel 3 cm, con color) | 126 frames, 563 bloques, ~87k triángulos, fusión ~10–18 ms, lectura de color ~13–35 ms (asíncrona) |

Brave **no** sirve: oculta WebGPU y elimina la API de profundidad CPU.

## Requisitos

- Android con **Chrome** reciente y *Google Play Services for AR*.
- Contexto seguro: HTTPS (GitHub Pages) o `http://localhost`.

## Desarrollo

### Deploy

GitHub Pages sirve `main` desde la raíz (`.nojekyll` incluido). Cada push a `main` se publica en 1–2 minutos.

Pages manda `Cache-Control: max-age=600`. Durante unos 10 minutos tras un deploy, el navegador puede mezclar un HTML nuevo con módulos JS viejos. Para probar enseguida, recarga sin caché: por CDP, `Network.setCacheDisabled` + `Page.reload {ignoreCache: true}`.

### Tests

```bash
node --test tests/*.test.mjs
```

Los tests cubren:

- Núcleo de fusión: posición de la superficie, normales, orientación, costuras entre bloques (con y sin dilatación), cámara rotada, orientación del color, limpieza de fragmentos y rendimiento.
- Sobre una habitación sintética con raycasting: ida y vuelta del volumen, alineación entre sesiones (recuperación, rechazo de datos ajenos y refinamiento) y colisiones con deslizamiento.

### Probar en el teléfono con depuración remota

```bash
ADB=/c/android-sdk/platform-tools/adb.exe   # ruta en esta máquina
"$ADB" devices                                # aceptar el diálogo de depuración USB en el teléfono
"$ADB" shell "cat /proc/net/unix | grep devtools"   # socket de Chrome: chrome_devtools_remote_<pid>
"$ADB" forward tcp:9223 localabstract:chrome_devtools_remote_<pid>
curl http://127.0.0.1:9223/json/list         # pestañas abiertas
"$ADB" exec-out screencap -p > screen.png     # captura (incluye la vista AR)
```

`chrome://inspect` en el PC también funciona. El escáner expone su estado en `window.vision`.

Si hay varios navegadores Chromium instalados (por ejemplo Brave), `chrome_devtools_remote` sin sufijo puede pertenecer a otro navegador. Comprueba el PID con `adb shell ps -A | grep com.android.chrome`.

## Estructura

```
index.html              landing
scan/index.html         escáner (UI + import map de three.js)
scan/main.js            sesión XR, color de cámara, render, visor, guardado, exportación
scan/walk.js            paseo en primera persona (joystick virtual / WASD)
scan/collider.js        colisiones: grilla 2D de ocupación inflada con el radio del jugador
scan/share.js           visor .html autocontenido para compartir; importación de .glb/.html
scan/db.js              IndexedDB v2: stores meta (listado), data (geometría) y volume (TSDF)
scan/fusion-worker.js   worker: mensajes integrate / flush / reset
scan/fusion-core.js     TSDF disperso + color + Surface Nets + limpieza (sin DOM, testeable en Node)
diag/index.html         diagnóstico de capacidades (autocontenido)
tests/                  tests de node:test
```
