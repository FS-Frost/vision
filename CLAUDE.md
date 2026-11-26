# CLAUDE.md

Guía para agentes que trabajen en este repo.

## Proyecto

Escáner 3D de habitaciones 100 % en el navegador (móvil Android + Chrome). Usa WebXR/ARCore para la pose y la profundidad, y WebGPU para la fusión TSDF y el mallado. Detalle en [README.md](README.md).

## Restricciones

- **Sitio estático, sin build ni bundler.** HTML + ES modules nativos. Las dependencias, si se necesitan, se cargan por CDN con la versión fijada (ej. `three@0.170.0`). No añadir `package.json` salvo que se pida.
- **Contexto seguro obligatorio** (HTTPS o `localhost`). WebXR, la cámara y WebGPU fallan sin él.
- **Dispositivo objetivo:** Motorola g54 (Mali-G57 MC2, GPU modesta). Diseñar para su rendimiento: grillas dispersas, fusión a 5–10 Hz y límites WebGPU por defecto.
- **APIs experimentales** (`depth-sensing`, `camera-access`): detectar siempre la feature, envolver en `try/catch` y degradar con mensajes claros. Los nombres de los enums han cambiado entre versiones de Chrome (`luminance-alpha` / `unsigned-short`).
- **UI y textos en español.**

## Estructura

- `index.html`: landing.
- `diag/index.html`: diagnóstico de capacidades (autocontenido). El JSON del reporte es el insumo para decidir la arquitectura.

## Verificación

No hay tests automáticos. Antes de cerrar un cambio:
- Comprobar la sintaxis del JS inline: extraer el `<script type="module">` y pasarlo por `node --check`.
- La prueba real se hace en el teléfono (ver README, `adb reverse`). Pedir al usuario el reporte JSON si hace falta.
