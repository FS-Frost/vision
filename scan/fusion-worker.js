// Web Worker: recibe mapas de profundidad (+ color) y poses, fusiona y devuelve mallas por supercelda.
import { Fusion } from './fusion-core.js';

const MESH_INTERVAL_MS = 250;
const MESH_BUDGET_MS = 30;

let fusion = new Fusion();
let lastMesh = 0;

function send(type, extra, meshes = []) {
  const transfer = [];
  for (const m of meshes) transfer.push(m.positions.buffer, m.normals.buffer, m.colors.buffer, m.indices.buffer);
  self.postMessage({ type, meshes, stats: fusion.stats(), ...extra }, transfer);
}

self.onmessage = (ev) => {
  const m = ev.data;
  try {
    switch (m.type) {
      case 'reset':
        fusion = new Fusion(m.options ?? fusion.opts);
        lastMesh = 0;
        send('reset', {});
        break;

      case 'integrate': {
        const t0 = performance.now();
        const depth = m.format === 'float32' ? new Float32Array(m.buffer) : new Uint16Array(m.buffer);
        const r = fusion.integrate({
          depth, width: m.width, height: m.height, rawToMeters: m.rawToMeters,
          normViewFromNormDepth: m.normViewFromNormDepth, projection: m.projection, viewMatrix: m.viewMatrix,
          color: m.color ? new Uint8Array(m.color) : null, colorWidth: m.colorWidth, colorHeight: m.colorHeight,
        });
        const t1 = performance.now();
        let meshes = [];
        if (t1 - lastMesh > MESH_INTERVAL_MS) {
          meshes = fusion.extract(MESH_BUDGET_MS).meshes;
          lastMesh = performance.now();
        }
        send('frame', { integrateMs: t1 - t0, meshMs: performance.now() - t1, used: r.used, updates: r.updates }, meshes);
        break;
      }

      case 'flush': {
        // malla final completa y limpia (al cerrar la sesión); reemplaza todo
        const t0 = performance.now();
        const r = fusion.finalMeshes();
        send('flushed', { meshMs: performance.now() - t0, removedTris: r.removedTris, replaceAll: true }, r.meshes);
        break;
      }
    }
  } catch (err) {
    self.postMessage({ type: 'error', message: `${err.name}: ${err.message}`, stack: err.stack });
  }
};
