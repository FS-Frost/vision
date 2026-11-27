# CLAUDE.md

Guía para agentes que trabajen en este repo.

## Proyecto

Escáner 3D de habitaciones 100 % en el navegador (Android + Chrome). WebXR/ARCore da la pose y la profundidad; un Web Worker hace la fusión TSDF y el mallado en CPU; three.js dibuja el resultado. Arquitectura, decisiones y resultados medidos en [README.md](README.md).

Sitio: https://fs-frost.github.io/vision/ (GitHub Pages, publicado por el workflow `.github/workflows/pages.yml` en cada push a `main`).

## Restricciones

- **Sitio estático, sin build ni bundler.** HTML + ES modules nativos. El único paso del deploy es sellar `__BUILD__`; no es un build. three.js por import map con versión fijada (`three@0.180.0`). No añadir `package.json` salvo que se pida.
- **three.js ≥ 0.175.** Las versiones anteriores rompen las sesiones con profundidad `cpu-optimized`.
- **Contexto seguro obligatorio** (HTTPS o `localhost`).
- **Dispositivo objetivo:** moto g54 5G, GPU **PowerVR BXM-8-256** (no Mali). WebGPU no está disponible ahí, así que todo el cómputo pesado va en CPU, dentro del worker. WebGPU solo como aceleración opcional futura.
- **APIs experimentales:** detectar siempre la feature y usar `try/catch`. La profundidad CPU se obtiene con `frame.getDepthInformation ?? frame.getDepthInfo`. El buffer de profundidad solo es válido durante su frame: copiarlo antes de transferirlo.
- **`scan/fusion-core.js` sin DOM ni three.js.** Así se puede testear en Node.
- **Costuras:** el mallado de un bloque lee la ventana local -2..9 (por la dilatación). Si cambias la ventana o la dilatación, actualiza `NEIGHBOR_MASK` y vuelve a pasar el test de costuras.
- **Color:** la imagen de cámara se lee con `renderer.xr.getCameraTexture` y se dibuja en un render target con `renderer.xr.enabled = false` durante ese pase (si no, three usa la cámara XR). La fila 0 del buffer leído es la parte **inferior** de la vista (verificado en el g54). Los colores de vértice van en espacio lineal.
- **Versionado de módulos (`__BUILD__`):** el deploy lo hace `.github/workflows/pages.yml`: tests → reemplazo de `__BUILD__` por el hash del commit → Pages. Reglas:
  - Todo módulo local nuevo de `scan/` se añade al import map de `scan/index.html` con `?v=__BUILD__`.
  - El worker no ve el import map: hereda la versión por la query de su URL (`self.location.search`) e importa el núcleo dinámicamente. No uses imports estáticos de módulos locales dentro del worker.
  - En código, usa `__BUILD__` solo donde deba reemplazarse: el workflow lo sustituye en todos los archivos (en la documentación es inofensivo).
- **Caché del navegador al probar por USB:** `Network.setCacheDisabled` no afecta a los módulos que carga el worker. Si hace falta limpiar, usa `Network.clearBrowserCache`.
- **Continuar escaneos:** lógica pura en `scan/reloc.js`, que no importa nada: si el worker lo importara con un import estático de otro módulo local, se rompería el versionado `?v=`.
  - **Método principal: anclas** (ver más abajo).
  - **Respaldo: búsqueda global** (`relocalize`):
    1. Búsqueda gruesa-a-fina **3D** de (yaw, x, z) contra un campo de proximidad de la superficie guardada (`searchVolume`). La altura sale de los suelos.
    2. ICP punto-plano de 4 grados de libertad (`Fusion.alignPoints`) sobre las mejores semillas, más la semilla del punto inicial.
    3. Orden por `Fusion.scorePose`, penalizado por `visibilityCheck` (la cámara «ve a través» de superficies guardadas), que rompe simetrías como la de 180°.
  - **Tolerancias en una sola constante, `RELOC_PARAMS`**, calibradas con datos reales: residuo de 5 cm, coseno 0,5 y margen de visibilidad de 25 cm. Las de centímetros rechazaban incluso la pose correcta. `minQuality` solo descarta datos ajenos y el rank solo ordena: nunca rechaces candidatos por rank.
  - La búsqueda 2D por planta (`method: 'plan'`) se conserva para comparar, pero falló con datos reales: en zonas con muebles blandos las plantas son manchas.
  - Los tests de búsqueda usan un sensor ideal (`IDEAL`, sin corte por distancia) porque miden el algoritmo, no la política de captura.

  Nunca sobrescribas el escaneo original con una continuación: si la alineación falla, se pierde.
- **Continuar = solo añadir zonas nuevas.** `importVolume` congela los voxels ya conocidos y `integrate` no los toca. Medido en el g54: la profundidad de ARCore no coincide a nivel de cm entre sesiones, y mezclar ambas dejaba ~42 % de superficie reobservada doble (>4 cm), sin arreglo con ninguna alineación rígida. No reintroduzcas la mezcla. Tampoco el refinamiento continuo de la alineación: se probó y solo metía ruido (saltos de 5–20 cm entre segundos). La métrica `rms` de `alignPoints` no distingue soluciones buenas de malas: no la uses como prueba de calidad.
- **Relocalización global** ("buscar en toda la habitación"): reglas decididas (implementadas en `main.js` + worker, mensajes `reloc-*`):
  - Sin anclas, barrido guiado obligatorio: entre 6 s (con ≥50 % de direcciones cubiertas) y 15 s.
  - **Toda** alineación al continuar requiere confirmación visual del usuario («Coincide» / «Probar otra»), aunque no haya duda. La app puede **ordenar y recomendar** candidatos por coincidencia en vivo, pero nunca aceptar sola.
  - La confirmación muestra cada candidato como **semáforo**: verde si coincide con la profundidad en vivo (±15 cm, el ruido medido entre sesiones), rojo si la cámara ve a través, gris sin dato. Hay un modo **parpadeo** con colores reales. No se vuelve a la malla azul uniforme: en 3D no permite juzgar la alineación.
  - La altura del suelo se estima desde la malla, pero solo se acepta si queda ≥ 0,8 m (`MIN_CAM_ABOVE_FLOOR`) bajo la cámara. Si no, se usa `local-floor` (y = 0). Medido en el g54: un barrido que no ve el suelo tomó una cama a 0,67 m como suelo y desplazó 70 cm en vertical a todos los candidatos.
  - Los frames del barrido se integran tras confirmar, no se descartan.
  - **Anclas (método principal para continuar):** al escanear, el usuario marca 2–3 puntos nítidos (esquinas, bordes, enchufes) con la mirilla. Se guarda el punto 3D, una foto con una cruz, la distancia desde la que se marcó y la posición de la cámara. La mirilla calcula el punto con la **profundidad propia** (mediana de 9 muestras de `getDepthInMeters` alrededor del centro, ≤ 3,5 m). El `hit-test` de WebXR queda solo de respaldo: choca con *planos* de ARCore y marcó el suelo a 5 m a través de un mueble que estaba a 2 m. Al volver a marcar, la UI muestra «Tomada a X m · altura Y m» frente a «Ahora: …», y avisa si la altura difiere más de 25 cm o si las distancias entre anclas difieren más de 20 cm; un segundo toque acepta igual. Al continuar, los vuelve a marcar y `solveAnchors` (Procrustes 2D + altura) da la pose sin búsqueda. Luego se afina con ICP local y se confirma con el semáforo. Si el residuo supera 25 cm, se avisa de que las anclas no cuadran (medido en el g54: una alineación buena da residuos de 12–18 cm; anclas mal marcadas, 80–140 cm). La búsqueda global queda como respaldo («Buscar sin anclas» o escaneos sin anclas). Las anclas nativas de WebXR no persisten entre sesiones en Chrome Android: son anclas propias.
  - **Pista de inicio:** cada escaneo nuevo guarda su pose inicial y una foto pequeña. Al continuar, se muestra la foto y la relocalización **siempre** incluye un candidato que supone que la sesión está en el punto inicial. Es una ayuda: nunca reemplaza la búsqueda global.
  - **Vistas de referencia** (etapa 2, solo si hacen falta): presupuesto máximo de ~3 MB por escaneo.
- **Captura: profundidad cercana.** La precisión de ARCore cae con la distancia. En `integrate` (`depthWeight`), el peso por muestra es 1 hasta 1,5 m, baja linealmente hasta 0 a 3 m, y se descarta lo que está más lejos.
- **Diagnóstico en el teléfono:** `window.vision.lastReloc` guarda candidatos, % en vivo, anclas y residuos de la última continuación. Si no se confirmó, también guarda los frames del barrido. Para analizar fuera del teléfono, descárgalo por CDP en lugar de ajustar a ciegas.
- **IndexedDB:** si cambias los stores, sube `DB_VERSION` en `scan/db.js` y crea los stores que falten en `onupgradeneeded`, sin borrar datos.
- **No hay Python en esta máquina.** Para scripts usa Node o Bash.
- **CSS:** cualquier página que use el atributo `hidden` en elementos con `display` propio necesita `[hidden] { display: none !important; }`.
- **UI y textos en español.**

## Verificación

1. `node --test tests/*.test.mjs` (ojo: `node --test tests/` no funciona en Windows).
2. `node --check` sobre los módulos modificados.
3. Prueba real en el teléfono: push a `main`, esperar el deploy de Pages y depurar por USB con adb + CDP (ver README, sección "Probar en el teléfono"). La captura `adb exec-out screencap -p` muestra también la vista AR.

ARCore no da pose si el teléfono está quieto o tapado: pide al usuario que lo mueva antes de evaluar el tracking.
