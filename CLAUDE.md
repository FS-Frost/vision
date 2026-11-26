# CLAUDE.md

Guía para agentes que trabajen en este repo.

## Proyecto

Escáner 3D de habitaciones 100 % en el navegador (Android + Chrome). WebXR/ARCore da la pose y la profundidad; un Web Worker hace la fusión TSDF y el mallado en CPU; three.js dibuja el resultado. Arquitectura, decisiones y resultados medidos en [README.md](README.md).

Sitio: https://fs-frost.github.io/vision/ (GitHub Pages desde `main`, raíz).

## Restricciones

- **Sitio estático, sin build ni bundler.** HTML + ES modules nativos. three.js por import map con versión fijada (`three@0.180.0`). No añadir `package.json` salvo que se pida.
- **three.js ≥ 0.175.** Las versiones anteriores rompen las sesiones con profundidad `cpu-optimized`.
- **Contexto seguro obligatorio** (HTTPS o `localhost`).
- **Dispositivo objetivo:** moto g54 5G, GPU **PowerVR BXM-8-256** (no Mali). WebGPU no está disponible ahí, así que todo el cómputo pesado va en CPU, dentro del worker. WebGPU solo como aceleración opcional futura.
- **APIs experimentales:** detectar siempre la feature y usar `try/catch`. La profundidad CPU se obtiene con `frame.getDepthInformation ?? frame.getDepthInfo`. El buffer de profundidad solo es válido durante su frame: copiarlo antes de transferirlo.
- **`scan/fusion-core.js` sin DOM ni three.js.** Así se puede testear en Node.
- **Costuras:** el mallado de un bloque lee la ventana local -2..9 (por la dilatación). Si cambias la ventana o la dilatación, actualiza `NEIGHBOR_MASK` y vuelve a pasar el test de costuras.
- **Color:** la imagen de cámara se lee con `renderer.xr.getCameraTexture` y se dibuja en un render target con `renderer.xr.enabled = false` durante ese pase (si no, three usa la cámara XR). La fila 0 del buffer leído es la parte **inferior** de la vista (verificado en el g54). Los colores de vértice van en espacio lineal.
- **Caché de Pages (10 min):** al probar un deploy recién hecho, recarga sin caché.
- **No hay Python en esta máquina.** Para scripts usa Node o Bash.
- **CSS:** cualquier página que use el atributo `hidden` en elementos con `display` propio necesita `[hidden] { display: none !important; }`.
- **UI y textos en español.**

## Verificación

1. `node --test tests/*.test.mjs` (ojo: `node --test tests/` no funciona en Windows).
2. `node --check` sobre los módulos modificados.
3. Prueba real en el teléfono: push a `main`, esperar el deploy de Pages y depurar por USB con adb + CDP (ver README, sección "Probar en el teléfono"). La captura `adb exec-out screencap -p` muestra también la vista AR.

ARCore no da pose si el teléfono está quieto o tapado: pide al usuario que lo mueva antes de evaluar el tracking.
