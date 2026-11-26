# Vision

Web app estática que escanea una habitación con la cámara del teléfono y construye progresivamente una escena 3D navegable. Todo se ejecuta en el navegador: sin servidor ni build.

## Estado

**Fase 0: diagnóstico.** [`diag/`](diag/) comprueba si un dispositivo soporta lo necesario. El escáner aún no existe.

## Arquitectura objetivo (camino A)

1. **WebXR `immersive-ar` (ARCore)** entrega la pose 6DoF del teléfono en cada frame. No hace falta implementar SLAM.
2. **`depth-sensing`** entrega un mapa de profundidad por frame (~160×90, estimado por ARCore).
3. **`camera-access`** entrega el frame RGB para colorear la malla.
4. **Fusión TSDF** en una grilla de voxels dispersa, en compute shaders WebGPU.
5. **Marching cubes** incremental (WebGPU) sobre los bloques modificados produce la malla.
6. Render y modo de navegación libre; exportación `.glb` y persistencia en IndexedDB.

Camino B (fallback sin ARCore): SLAM monocular en WASM + red de profundidad monocular. Es lento en gama media y queda descartado salvo que el camino A no sea viable.

## Requisitos

- Android con **Chrome** reciente (121+ para WebGPU).
- Dispositivo con **Google Play Services for AR** (ARCore).
- **Contexto seguro**: HTTPS o `http://localhost`.
- Dispositivo de referencia: Motorola g54 (Dimensity 7020, Mali-G57 MC2).

## Ejecutar el diagnóstico en el teléfono

### Opción 1: USB + `adb reverse` (desarrollo local)

```bash
# En el PC, en la raíz del repo
npx serve -l 8080 .          # o: python -m http.server 8080

# Teléfono conectado por USB con depuración USB activada
adb reverse tcp:8080 tcp:8080
```

Abre `http://localhost:8080/diag/` en Chrome Android. `localhost` cuenta como contexto seguro. Para la consola remota usa `chrome://inspect` en el PC.

### Opción 2: hosting estático HTTPS

Sube el repo a GitHub Pages o Cloudflare Pages y abre `https://<host>/diag/`.

### Uso

1. La página detecta entorno, WebGPU, WebGL2 y WebXR automáticamente.
2. **Benchmark TSDF** mide cuántas integraciones por segundo de una grilla 128³ soporta la GPU.
3. **Probar sesión AR** abre una sesión de AR con profundidad y cámara. Muévete despacio 20–30 s y pulsa *Terminar*.
4. **Copiar reporte JSON** copia los resultados para analizarlos.

### Problemas comunes

- `depth-sensing` o `camera-access` no concedidos: activa `chrome://flags/#webxr-incubations` y reinicia Chrome.
- `immersive-ar` no soportado: instala o actualiza *Google Play Services for AR* desde Play Store.
- Panel negro durante la sesión: `dom-overlay` no está concedido. Sal con el botón Atrás; los resultados se muestran igual.

## Estructura

```
index.html        landing con enlaces
diag/index.html   página de diagnóstico autocontenida (HTML + CSS + JS módulo inline)
```
