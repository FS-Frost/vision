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
- **Continuar escaneos:** relocalización global en `scan/reloc.js` (puro, sin imports). Pasos:
  1. Planta 2D de las superficies verticales, con el suelo estimado desde cada malla.
  2. Búsqueda gruesa-a-fina de (yaw, x, z) sobre toda la planta guardada.
  3. ICP punto-plano de 4 grados de libertad (`Fusion.alignPoints`) sobre las mejores semillas.
  4. Orden por `Fusion.scorePose` (residuo < 2 cm con normal compatible), penalizado por `visibilityCheck` (la cámara «ve a través» de superficies guardadas). La penalización rompe simetrías como la de 180°.

  Nunca sobrescribas el escaneo original con una continuación: si la alineación falla, se pierde.
- **Continuar = solo añadir zonas nuevas.** `importVolume` congela los voxels ya conocidos y `integrate` no los toca. Medido en el g54: la profundidad de ARCore no coincide a nivel de cm entre sesiones, y mezclar ambas dejaba ~42 % de superficie reobservada doble (>4 cm), sin arreglo con ninguna alineación rígida. No reintroduzcas la mezcla. Tampoco el refinamiento continuo de la alineación: se probó y solo metía ruido (saltos de 5–20 cm entre segundos). La métrica `rms` de `alignPoints` no distingue soluciones buenas de malas: no la uses como prueba de calidad.
- **Relocalización global** ("buscar en toda la habitación"): reglas decididas (implementadas en `main.js` + worker, mensajes `reloc-*`):
  - Barrido guiado obligatorio: entre 6 s (con ≥50 % de direcciones cubiertas) y 15 s.
  - **Toda** alineación al continuar requiere confirmación visual del usuario («Coincide» / «Probar otra»), aunque no haya duda. La app puede **ordenar y recomendar** candidatos por coincidencia en vivo, pero nunca aceptar sola.
  - La confirmación muestra cada candidato como **semáforo**: verde si coincide con la profundidad en vivo (±15 cm, el ruido medido entre sesiones), rojo si la cámara ve a través, gris sin dato. Hay un modo **parpadeo** con colores reales. No se vuelve a la malla azul uniforme: en 3D no permite juzgar la alineación.
  - La altura del suelo se calcula desde la malla, no se confía en `local-floor`.
  - Los frames del barrido se integran tras confirmar, no se descartan.
  - **Pista de inicio:** cada escaneo nuevo guarda su pose inicial y una foto pequeña. Al continuar, se muestra la foto y la relocalización **siempre** incluye un candidato que supone que la sesión está en el punto inicial. Es una ayuda: nunca reemplaza la búsqueda global.
  - **Vistas de referencia** (etapa 2, solo si hacen falta): presupuesto máximo de ~3 MB por escaneo.
- **Captura: profundidad cercana.** La precisión de ARCore cae con la distancia. En `integrate`, el peso por muestra es 1 hasta 1,5 m, baja linealmente hasta 0 a 3 m, y se descarta lo que está más lejos.
  - `scan/reloc.js` no importa nada: si el worker lo importara con un import estático de otro módulo local, se rompería el versionado `?v=`.
- **IndexedDB:** si cambias los stores, sube `DB_VERSION` en `scan/db.js` y crea los stores que falten en `onupgradeneeded`, sin borrar datos.
- **No hay Python en esta máquina.** Para scripts usa Node o Bash.
- **CSS:** cualquier página que use el atributo `hidden` en elementos con `display` propio necesita `[hidden] { display: none !important; }`.
- **UI y textos en español.**

## Verificación

1. `node --test tests/*.test.mjs` (ojo: `node --test tests/` no funciona en Windows).
2. `node --check` sobre los módulos modificados.
3. Prueba real en el teléfono: push a `main`, esperar el deploy de Pages y depurar por USB con adb + CDP (ver README, sección "Probar en el teléfono"). La captura `adb exec-out screencap -p` muestra también la vista AR.

ARCore no da pose si el teléfono está quieto o tapado: pide al usuario que lo mueva antes de evaluar el tracking.
