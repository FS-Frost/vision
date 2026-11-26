// Web Worker: recibe mapas de profundidad + poses, fusiona y devuelve mallas por supercelda.
import { Fusion } from './fusion-core.js';

const MESH_INTERVAL_MS = 250;
const MESH_BUDGET_MS = 30;

let fusion = new Fusion();
let lastMesh = 0;

function send(type, extra, meshes = []) {
  const transfer = [];
  for (const m of meshes) transfer.push(m.positions.buffer, m.normals.buffer, m.indices.buffer);
  self.postMessage({ type, meshes, stats: fusion.stats(), ...extra }, transfer);
}

self.onmessage = (ev) => {
  const m = ev.data;
  try {
    switch (m.type) {
      case 'init':
        fusion = new Fusion(m.options || {});
        break;

      case 'reset':
        fusion = new Fusion(fusion.opts);
        send('reset', {});
        break;

      case 'integrate': {
        const t0 = performance.now();
        const depth = m.format === 'float32' ? new Float32Array(m.buffer) : new Uint16Array(m.buffer);
        const r = fusion.integrate({
          depth, width: m.width, height: m.height, rawToMeters: m.rawToMeters,
          normViewFromNormDepth: m.normViewFromNormDepth, projection: m.projection, viewMatrix: m.viewMatrix,
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
        // termina todo el mallado pendiente (al cerrar la sesión)
        const t0 = performance.now();
        let meshes = [];
        for (let i = 0; i < 1000; i++) {
          const r = fusion.extract(Infinity);
          meshes = meshes.concat(r.meshes);
          if (r.pending === 0) break;
        }
        const latest = [...new Map(meshes.map((x) => [x.key, x])).values()];
        send('flushed', { meshMs: performance.now() - t0 }, latest);
        break;
      }
    }
  } catch (err) {
    self.postMessage({ type: 'error', message: `${err.name}: ${err.message}`, stack: err.stack });
  }
};
