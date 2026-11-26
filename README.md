# Vision

Web app estática que escanea una habitación con la cámara del teléfono y construye progresivamente una escena 3D navegable. Todo se ejecuta en el navegador: sin servidor ni build.

**Sitio:** https://fs-frost.github.io/vision/

- [Escáner](https://fs-frost.github.io/vision/scan/): recorre la habitación, mira la malla crecer sobre la cámara, orbita el resultado y exporta a `.glb`.
- [Diagnóstico](https://fs-frost.github.io/vision/diag/): comprueba las capacidades del dispositivo (WebGPU, WebXR/ARCore, profundidad, cámara, benchmarks).

## Estado

Prototipo funcional: escaneo en vivo, visor orbital y exportación `.glb`.

Pendiente: color desde `camera-access`, navegación en primera persona, persistencia en IndexedDB y mejor cierre de huecos.

## Arquitectura

```
WebXR immersive-ar (ARCore)
  ├─ pose 6DoF por frame          (XRViewerPose, espacio 'local')
  └─ profundidad CPU 160x90       (XRFrame.getDepthInformation, luminance-alpha)
        │  ~10 Hz, ArrayBuffer transferido
        ▼
Web Worker (scan/fusion-worker.js → scan/fusion-core.js)
  ├─ fusión TSDF dispersa         bloques 8³, voxel 4 cm, truncamiento 3 voxels
  └─ Surface Nets por bloque      fusionado en superceldas 4³ (pocas draw calls)
        │  geometrías por supercelda cada ~250 ms
        ▼
Hilo principal (scan/main.js, three.js 0.180)
  ├─ malla semitransparente sobre la cámara durante el escaneo
  └─ visor OrbitControls + GLTFExporter al terminar
```

### Decisiones

- **Fusión en CPU, no en WebGPU.** El dispositivo de referencia (moto g54, GPU PowerVR BXM-8-256) no obtiene adapter WebGPU en Chrome Android porque PowerVR está en la blocklist. Con profundidad de 160×90, la fusión en CPU tarda ~5–25 ms por frame en un worker.
- **three.js ≥ 0.175.** Las versiones anteriores llaman a `XRWebGLBinding.getDepthInformation` aunque la sesión use `cpu-optimized`, y eso rompe el bucle de render.
- **`getDepthInformation`**. Chrome 158 renombró `getDepthInfo`; el código acepta ambos nombres.

### Parámetros por URL (escáner)

| Parámetro | Por defecto | Efecto |
|---|---|---|
| `voxel` | `0.04` | Tamaño de voxel en metros |
| `hz` | `10` | Frecuencia máxima de integración de profundidad |

Ejemplo: `scan/?voxel=0.03&hz=8`.

## Resultados en el moto g54 (Chrome 158, Android 15)

| Prueba | Resultado |
|---|---|
| Tracking 6DoF | OK, ~30 fps |
| Profundidad | 160×90, `luminance-alpha`, 100 % de píxeles válidos, ~28 Hz |
| Cámara | 864×1920 |
| WebGPU | Sin adapter (blocklist PowerVR) |
| Benchmark TSDF CPU | ~4 ms por 100k actualizaciones de voxel |
| Escaneo real | 171 frames, 203 bloques, ~15k triángulos, 0,8 MB |

Brave **no** sirve: oculta WebGPU y elimina la API de profundidad CPU.

## Requisitos

- Android con **Chrome** reciente y *Google Play Services for AR*.
- Contexto seguro: HTTPS (GitHub Pages) o `http://localhost`.

## Desarrollo

### Deploy

GitHub Pages sirve `main` desde la raíz (`.nojekyll` incluido). Cada push a `main` se publica en 1–2 minutos.

### Tests

```bash
node --test tests/*.test.mjs
```

Los tests cubren el núcleo de fusión: posición de la superficie, normales, orientación, costuras entre bloques, cámara rotada y rendimiento.

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
scan/main.js            sesión XR, render, visor, exportación
scan/fusion-worker.js   worker: mensajes integrate / flush / reset
scan/fusion-core.js     TSDF disperso + Surface Nets (sin DOM, testeable en Node)
diag/index.html         diagnóstico de capacidades (autocontenido)
tests/                  tests de node:test
```
