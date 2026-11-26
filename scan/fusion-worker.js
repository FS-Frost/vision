// Web Worker: recibe mapas de profundidad (+ color) y poses, fusiona y devuelve mallas por supercelda.
// También guarda/carga el volumen TSDF y alinea una sesión nueva con un escaneo guardado.
import { Fusion } from './fusion-core.js';

const MESH_INTERVAL_MS = 250;
const MESH_BUDGET_MS = 30;
const ALIGN_MIN_FRAMES = 5;   // frames acumulados antes de intentar alinear
const ALIGN_WINDOW = 10;      // ventana deslizante de frames para alinear
const ALIGN_OPTS = { minRatio: 0.35, maxRms: 0.025 };
const AGREE_YAW = 0.02;       // rad: dos soluciones consecutivas deben coincidir
const AGREE_T = 0.03;         // m

let fusion = new Fusion();
let lastMesh = 0;
let alignFrames = [];
let alignAttempts = 0;
let candidate = null;         // última solución "ok" aún no confirmada

const agrees = (a, b) => Math.abs(a.yaw - b.yaw) < AGREE_YAW && Math.hypot(a.t[0] - b.t[0], a.t[1] - b.t[1], a.t[2] - b.t[2]) < AGREE_T;

function send(type, extra, meshes = []) {
  const transfer = [];
  for (const m of meshes) transfer.push(m.positions.buffer, m.normals.buffer, m.colors.buffer, m.indices.buffer);
  self.postMessage({ type, meshes, stats: fusion.stats(), ...extra }, transfer);
}

function frameFrom(m) {
  return {
    depth: m.format === 'float32' ? new Float32Array(m.buffer) : new Uint16Array(m.buffer),
    width: m.width, height: m.height, rawToMeters: m.rawToMeters,
    normViewFromNormDepth: m.normViewFromNormDepth, projection: m.projection, viewMatrix: m.viewMatrix,
    color: m.color ? new Uint8Array(m.color) : null, colorWidth: m.colorWidth, colorHeight: m.colorHeight,
  };
}

self.onmessage = (ev) => {
  const m = ev.data;
  try {
    switch (m.type) {
      case 'reset':
        fusion = new Fusion(m.options ?? fusion.opts);
        lastMesh = 0;
        alignFrames = [];
        alignAttempts = 0;
        candidate = null;
        if (m.volume) fusion.importVolume(m.volume);
        send('reset', { imported: !!m.volume });
        break;

      case 'integrate': {
        const t0 = performance.now();
        const r = fusion.integrate(frameFrom(m));
        const t1 = performance.now();
        let meshes = [];
        if (t1 - lastMesh > MESH_INTERVAL_MS) {
          meshes = fusion.extract(MESH_BUDGET_MS).meshes;
          lastMesh = performance.now();
        }
        send('frame', { integrateMs: t1 - t0, meshMs: performance.now() - t1, used: r.used, updates: r.updates }, meshes);
        break;
      }

      case 'align-frame': {
        // acumula puntos de la sesión nueva; cuando hay suficientes, intenta alinear
        alignFrames.push(fusion.samplePoints(frameFrom(m), 4));
        if (alignFrames.length > ALIGN_WINDOW) alignFrames.shift();
        if (alignFrames.length < ALIGN_MIN_FRAMES) {
          self.postMessage({ type: 'aligning', frames: alignFrames.length, needed: ALIGN_MIN_FRAMES });
          break;
        }
        const total = alignFrames.reduce((s, a) => s + a.length, 0);
        const pts = new Float32Array(total);
        let o = 0;
        for (const a of alignFrames) { pts.set(a, o); o += a.length; }
        const t0 = performance.now();
        // con un candidato: refinar desde él con una sola semilla (rápido); si no, búsqueda multi-yaw
        const r = candidate
          ? fusion.alignPoints(pts, { ...ALIGN_OPTS, yaws: [0], initial: [candidate.yaw, ...candidate.t], levels: [0.2, 0.12, 0.08, 0.05] })
          : fusion.alignPoints(pts, ALIGN_OPTS);
        alignAttempts++;
        // aceptar sólo si dos soluciones consecutivas coinciden (evita falsos positivos)
        let ok = false;
        if (r.ok) {
          ok = !!candidate && agrees(candidate, r);
          candidate = r;
        } else {
          candidate = null;
        }
        if (ok) fusion.setAlign(r.matrix);
        self.postMessage({
          type: 'aligned', ok, candidate: r.ok && !ok, matrix: r.matrix, yaw: r.yaw, t: r.t,
          inlierRatio: r.inlierRatio, rms: r.rms, reason: r.reason, attempts: alignAttempts,
          ms: performance.now() - t0,
        });
        break;
      }

      case 'flush': {
        // malla final completa y limpia (al cerrar la sesión); reemplaza todo
        const t0 = performance.now();
        const r = fusion.finalMeshes();
        const volume = fusion.exportVolume();
        send('flushed', { meshMs: performance.now() - t0, removedTris: r.removedTris, replaceAll: true, volume }, r.meshes);
        break;
      }
    }
  } catch (err) {
    self.postMessage({ type: 'error', message: `${err.name}: ${err.message}`, stack: err.stack });
  }
};
