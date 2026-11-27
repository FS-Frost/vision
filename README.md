# Vision

Web app estática que escanea una habitación con la cámara del teléfono y construye progresivamente una escena 3D navegable. Todo se ejecuta en el navegador: sin servidor ni build.

**Sitio:** https://fs-frost.github.io/vision/

- [Escáner](https://fs-frost.github.io/vision/scan/): recorre la habitación, mira cómo crece la malla sobre la cámara y después orbita o camina por el resultado con color real. Los escaneos se guardan en el teléfono, se pueden continuar otro día, compartir como `.html` y exportar a `.glb`.
- [Diagnóstico](https://fs-frost.github.io/vision/diag/): comprueba las capacidades del dispositivo (WebGPU, WebXR/ARCore, profundidad, cámara y benchmarks).

## Estado

Prototipo funcional, probado en un moto g54.

**Escanear**
- Malla en vivo con color de cámara (TSDF en un Web Worker).
- Guía de cobertura: anillo de direcciones cubiertas, y avisos de «falta mirar arriba/abajo» y de mirar paredes y esquinas.
- Profundidad cercana: se pondera por distancia y se descarta más allá de 3 m.
- **Anclas:** mirilla en el centro y botón «Ancla n/3» para marcar 2–3 puntos nítidos. Cada ancla guarda su foto, la distancia desde la que se marcó y su altura.
- Calidad: voxel de 3 cm, filtro de ruido, cierre de huecos pequeños y limpieza de fragmentos.

**Ver y usar**
- Visor orbital y paseo en primera persona (joystick virtual) con colisiones.
- Guardado automático en IndexedDB; exportación `.glb`; importación de `.glb` y `.html`.
- Compartir como `.html` autocontenido (visor three.js + modelo en base64) que se abre en cualquier navegador.

**Continuar un escaneo guardado**
1. **Con anclas (método principal):** la app muestra la foto de cada ancla con «Tomada a X m · altura Y m» y la comparación en vivo («Ahora: … ✓/✗»). Vuelves a marcar los mismos puntos y `solveAnchors` calcula la posición directamente; luego se afina con ICP local. Si la altura o las distancias entre anclas no cuadran, la app avisa al marcar.
2. **Sin anclas (respaldo):** barrido guiado de 6–15 s y búsqueda en toda la habitación (3D gruesa-a-fina + ICP). Se muestran los mejores candidatos, incluido siempre el «punto inicial» (pose y foto guardadas al empezar el escaneo).
3. **Confirmación visual obligatoria:** cada candidato se ve como semáforo (verde = coincide con la profundidad en vivo, rojo = la cámara ve a través, gris = sin dato), con su % de coincidencia y un modo parpadeo con colores reales. La app recomienda, pero nunca acepta sola.
4. La sesión nueva **solo añade zonas nuevas**: lo ya escaneado queda congelado. La ampliación se guarda como escaneo nuevo y el original se conserva.

### Limitaciones conocidas

- **La profundidad de ARCore no coincide entre sesiones:** difiere ±10–15 cm a 1,5 m. Por eso, aunque la posición sea la correcta, el semáforo ronda el 55–60 % de verde, y en los bordes entre zona vieja y nueva puede quedar un escalón de pocos centímetros.
- **La búsqueda automática sin anclas es poco fiable** en este teléfono: con datos reales no pasó del 39 % de coincidencia y a menudo deja varios candidatos parecidos. Funciona bien en la habitación simulada de los tests (19/20), pero ahí la profundidad es perfecta.
- **Precisión de las anclas:** 12–18 cm de residuo en la prueba real, por la puntería más el ruido de profundidad. Necesitan puntos nítidos que no se muevan.
- **Superficies lisas o lejanas:** las superficies sin textura (paredes blancas, techo) y lo que está a más de 3 m dan poca o ninguna geometría.
- **Deriva:** no hay cierre de bucles. En recorridos largos, la deriva de ARCore dentro de una sesión deforma un poco la malla.

## Mejoras pendientes

Ideas por orden aproximado de impacto. Algunas no tienen todavía un diseño claro; se indica qué se sabe y qué falta decidir.

| Mejora | Qué resolvería | Estado / dudas |
|---|---|---|
| **Vistas de referencia** | Puntuar candidatos comparando vista contra vista (profundidades guardadas al capturar) en lugar de contra la malla fusionada, que mezcla el ruido de muchos frames. | Acordada como «etapa 2» con un presupuesto de ~3 MB por escaneo (~100 vistas, 1 por segundo). Falta decidir cómo emparejar vistas guardadas con frames nuevos (¿por pose del candidato?, ¿por similitud de imagen?) y si mejora algo con un ruido de ±10–15 cm. |
| **Sugerir buenas anclas** | Hoy el usuario elige los puntos. La app podría proponer esquinas o bordes nítidos (alta curvatura, buen contraste) a la distancia ideal (1–2 m) y avisar si las anclas están demasiado juntas o alineadas. | Idea sin diseñar. Habría que detectar esquinas en la malla o en la imagen y elegir un criterio de «nitidez». |
| **Relocalización visual** | Continuar sin anclas usando la imagen además de la profundidad, por ejemplo con puntos de interés tipo ORB comparados con fotos guardadas. Más robusto que la forma 3D con profundidad ruidosa. | Sin decidir. Opciones: rasgos ORB en WASM, o pocas fotos de referencia con emparejamiento grueso. Pesa en CPU en un teléfono de gama media. Habría que medir el costo. |
| **Corregir lo ya escaneado** | Hoy una continuación nunca modifica lo existente (congelado), para no duplicar superficies. Podría permitirse reemplazar zonas con observaciones mejores (más cercanas, más frescas). | Sin decidir el criterio de «mejor observación». Con el ruido actual entre sesiones existe el riesgo de reintroducir capas dobles. |
| **Cierre de bucles / corrección de deriva** | En escaneos largos, al volver a una zona ya vista, corregir la deriva acumulada. | Requiere guardar el historial de poses y re-fusionar o deformar el volumen. Es un cambio grande que no se ha explorado. |
| **Texturas en lugar de color por vértice** | Más detalle visual sin subir la resolución de la malla. | Habría que guardar fotos de referencia y proyectarlas (atlas de texturas). Hay que vigilar el tamaño. |
| **Simplificación de mallas grandes** | Los escaneos ampliados llegan a cientos de miles de triángulos. Simplificar aliviaría el visor, el paseo y el archivo compartido. | Decimación al guardar o niveles de detalle (LOD) en el visor. Falta elegir algoritmo y presupuesto de triángulos. |
| **Mallado al ritmo del escaneo** | Con 3 cm, el mallado en vivo se queda atrás (cientos de bloques en cola) aunque al cerrar se pone al día. | Opciones: más presupuesto por ciclo, priorizar los bloques visibles o un segundo worker. Falta medirlo. |
| **Paseo: escalones y alturas** | Las colisiones son 2D (franja del cuerpo). No hay subir o bajar desniveles. | Seguir la altura del suelo bajo el jugador. Hay que diseñar qué pasa en huecos sin geometría. |
| **Medir en el modelo** | Distancias y alturas tocando dos puntos (cinta métrica). | Sencillo con la mirilla y la profundidad. Falta definir la UI. |
| **Aceleración WebGPU opcional** | En teléfonos donde WebGPU sí está disponible, fusión y mallado más rápidos. | El g54 no tiene adapter (PowerVR en la blocklist), así que no se puede probar aquí. |
| **Escaneos de varias habitaciones** | Unir habitaciones en un mismo modelo. | Fuera del alcance actual. Las anclas podrían servir de puente entre escaneos. |
| **Mantenimiento** | Las acciones del workflow (`actions/checkout@v4`, etc.) apuntan a Node 20, que está obsoleto. | Subirlas cuando haya versiones estables nuevas. |

## Arquitectura

```
WebXR immersive-ar (ARCore)
  ├─ pose 6DoF por frame          (XRViewerPose, espacio 'local-floor' o 'local')
  ├─ profundidad CPU 160x90       (XRFrame.getDepthInformation, luminance-alpha)
  └─ imagen de cámara             (camera-access → render target 120 px → lectura asíncrona)
        │  ~10 Hz, ArrayBuffers transferidos
        ▼
Web Worker (scan/fusion-worker.js → scan/fusion-core.js + scan/reloc.js)
  ├─ fusión TSDF dispersa         bloques 8³, voxel 3 cm, truncamiento 3 voxels, color sRGB por voxel,
  │                               peso por distancia (1 hasta 1,5 m → 0 a 3 m)
  ├─ Surface Nets por bloque      peso mínimo 2, dilatación de 1 voxel (ventana 12³), color por vértice
  ├─ malla final                  elimina componentes conexas pequeñas
  └─ relocalización (continuar)   anclas → ICP local, o búsqueda 3D + ICP; volumen guardado congelado
        │  geometrías por supercelda 4³ cada ~250 ms
        ▼
Hilo principal (scan/main.js, three.js 0.180)
  ├─ malla semitransparente sobre la cámara durante el escaneo; mirilla con profundidad propia
  ├─ confirmación: shader semáforo con la profundidad en vivo, parpadeo, recomendación
  ├─ visor: OrbitControls o paseo (walk.js + collider.js), color sin iluminación o sombreado
  └─ guardado en IndexedDB (db.js), compartir/importar (share.js) y GLTFExporter
```

### Decisiones

Cada una está respaldada por mediciones en el g54. El detalle está en `CLAUDE.md`.

- **Fusión en CPU, no en WebGPU.** El g54 (GPU PowerVR BXM-8-256) no obtiene adapter WebGPU en Chrome Android porque PowerVR está en la blocklist. Con profundidad de 160×90, la fusión en CPU tarda ~5–25 ms por frame en un worker.
- **three.js ≥ 0.175.** Las versiones anteriores llaman a `XRWebGLBinding.getDepthInformation` aunque la sesión use `cpu-optimized`, y eso rompe el bucle de render.
- **`getDepthInformation`.** Chrome 158 renombró `getDepthInfo`; el código acepta ambos nombres.
- **Continuar = solo añadir.** Mezclar dos sesiones dejaba ~42 % de la superficie reobservada doble, y ninguna alineación rígida lo corregía.
- **La mirilla usa la profundidad propia, no `hit-test`.** El `hit-test` de WebXR choca con *planos* detectados por ARCore: llegó a marcar el suelo a 5 m a través de un mueble que estaba a 2 m.
- **Suelo verificado.** Un suelo estimado desde la malla solo vale si queda ≥ 0,8 m bajo la cámara. Si no, se usa `local-floor`: una cama a 0,67 m llegó a pasar por suelo.
- **Confirmación siempre humana.** Con el ruido de profundidad entre sesiones, ninguna métrica automática distinguió de forma fiable la pose correcta.

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
| Profundidad | 160×90, `luminance-alpha`, ~28 Hz |
| Cámara | 864×1920 |
| WebGPU | Sin adapter (blocklist PowerVR) |
| Benchmark TSDF CPU | ~4 ms por 100k actualizaciones de voxel |
| Escaneo real (voxel 3 cm, con color) | fusión ~10–18 ms, lectura de color ~13–35 ms (asíncrona); escaneos de 100k–340k triángulos |
| Profundidad entre sesiones | discrepancia de ±10–15 cm a 1,5 m (mediana medida/predicha 0,97–0,99, dispersión p25–p75 de −13 % a +9 %) |
| Búsqueda global (sin anclas) | ~0,5 s en PC y 2–3,5 s en el g54; coincidencia en vivo ≤ 39 % con datos reales |
| Continuar con 3 anclas | residuos de 12–18 cm; coincidencia en vivo 57 %; confirmado y ampliado sin capas dobles |

Brave **no** sirve: oculta WebGPU y elimina la API de profundidad CPU.

## Requisitos

- Android con **Chrome** reciente y *Google Play Services for AR*.
- Contexto seguro: HTTPS (GitHub Pages) o `http://localhost`.

## Desarrollo

### Deploy

Cada push a `main` dispara el workflow [`.github/workflows/pages.yml`](.github/workflows/pages.yml), con dos jobs (`build` y `deploy`):

1. Corre los tests (`node --test`). Si fallan, no se publica.
2. Sella la versión: reemplaza `__BUILD__` por los primeros 8 caracteres del hash del commit.
3. Publica en GitHub Pages (1–2 minutos).

Pages manda `Cache-Control: max-age=600`. Sin el sello, el navegador podía mezclar módulos de deploys distintos. Con él, `scan/index.html` carga `main.js?v=<hash>`, el import map remapea los demás módulos locales a la misma versión, y el worker hereda la query de `main.js` para importar `fusion-core.js` y `reloc.js`. El HTML puede seguir en caché hasta 10 minutos, pero siempre trae su propio conjunto coherente de módulos.

En local (`npx serve` / `adb reverse`), `__BUILD__` queda literal en la query y funciona igual.

### Tests

```bash
node --test tests/*.test.mjs
```

20 tests en Node, sin dependencias:

- **Núcleo de fusión:** posición de la superficie, normales, orientación, costuras entre bloques (con y sin dilatación), cámara rotada, orientación del color, limpieza de fragmentos, rendimiento y peso por distancia.
- **Habitación sintética con raycasting:**
  - Volumen: ida y vuelta, y congelación al continuar.
  - Alineación local.
  - Relocalización global (20 inicios al azar, sensor ideal).
  - Rechazo de datos ajenos.
  - Coincidencia en vivo.
  - Pista de inicio y anclas (con detección de un ancla mal marcada).
  - Validación del suelo.
  - Colisiones.

### Probar en el teléfono con depuración remota

```bash
ADB=/c/android-sdk/platform-tools/adb.exe   # ruta en esta máquina
"$ADB" devices                                # aceptar el diálogo de depuración USB en el teléfono
"$ADB" shell "cat /proc/net/unix | grep devtools"   # socket de Chrome: chrome_devtools_remote_<pid>
"$ADB" forward tcp:9223 localabstract:chrome_devtools_remote_<pid>
curl http://127.0.0.1:9223/json/list         # pestañas abiertas
"$ADB" exec-out screencap -p > screen.png     # captura (incluye la vista AR)
```

`chrome://inspect` en el PC también funciona. El escáner expone su estado en `window.vision`. Al terminar una continuación, `window.vision.lastReloc` guarda el diagnóstico: candidatos, % en vivo, anclas y residuos. Si no se confirmó, también guarda los frames del barrido, que se pueden descargar y analizar en el PC.

Si hay varios navegadores Chromium instalados (por ejemplo Brave), `chrome_devtools_remote` sin sufijo puede pertenecer a otro navegador. Comprueba el PID con `adb shell ps -A | grep com.android.chrome`. La redirección del puerto se cae a veces: repite el `adb forward`.

## Estructura

```
index.html              landing
scan/index.html         escáner (UI + import map versionado)
scan/main.js            sesión XR, color, mirilla y anclas, relocalización (UI), semáforo, visor, guardado
scan/walk.js            paseo en primera persona (joystick virtual / WASD)
scan/collider.js        colisiones: grilla 2D de ocupación inflada con el radio del jugador
scan/share.js           visor .html autocontenido para compartir; importación de .glb/.html
scan/db.js              IndexedDB v2: stores meta (listado, pista de inicio, anclas), data (geometría) y volume (TSDF)
scan/fusion-worker.js   worker: integrate / flush / reset / reloc-* (barrido, búsqueda, anclas, confirmación)
scan/fusion-core.js     TSDF disperso + color + Surface Nets + limpieza + ICP + calidad (sin DOM, testeable en Node)
scan/reloc.js           relocalización: búsqueda 3D, visibilidad, coincidencia en vivo, suelo, pista de inicio, anclas (puro)
diag/index.html         diagnóstico de capacidades (autocontenido)
tests/                  tests de node:test
.github/workflows/      deploy a Pages (tests + sellado de versión)
```
