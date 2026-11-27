// Web Worker: recibe mapas de profundidad (+ color) y poses, fusiona y devuelve mallas por supercelda.
// También guarda/carga el volumen TSDF y relocaliza una sesión nueva dentro de un escaneo guardado.
//
// El worker no ve el import map de la página: importa sus módulos con su misma versión (?v=...)
// para no mezclar módulos de deploys distintos que estén en caché.
let Fusion, reloc;
const V = self.location.search;
const ready = Promise.all([import(`./fusion-core.js${V}`), import(`./reloc.js${V}`)]).then(([core, r]) => {
  Fusion = core.Fusion;
  reloc = r;
  fusion = new Fusion();
});

const MESH_INTERVAL_MS = 250;
const MESH_BUDGET_MS = 30;
const RELOC_MAX_FRAMES = 150;   // frames del barrido guardados para integrarlos al confirmar
const RELOC_VIS_FRAMES = 20;    // frames usados en la prueba de visibilidad

let fusion = null;
let lastMesh = 0;
// relocalización: mini-escaneo provisional del barrido (marco de la sesión) + frames guardados
let relocFusion = null;
let relocFrames = [];
let relocFloorFallback = null;

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

function resetReloc() {
  relocFusion = null;
  relocFrames = [];
}

// los mensajes que llegan antes de cargar los módulos esperan en orden (misma promesa)
self.onmessage = (ev) => {
  ready.then(() => handle(ev.data)).catch((err) => {
    self.postMessage({ type: 'error', message: `carga del núcleo: ${err.message}` });
  });
};

function handle(m) {
  try {
    switch (m.type) {
      case 'reset':
        fusion = new Fusion(m.options ?? fusion.opts);
        lastMesh = 0;
        resetReloc();
        relocFloorFallback = m.sessionFloorFallback ?? null;
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

      case 'reloc-frame': {
        // barrido: integra en el mini-escaneo provisional y guarda el frame para después
        if (!relocFusion) relocFusion = new Fusion({ voxel: 0.05, minWeight: 1 });
        const f = frameFrom(m);
        relocFusion.integrate(f);
        relocFrames.push(f);
        if (relocFrames.length > RELOC_MAX_FRAMES) relocFrames.shift();
        self.postMessage({ type: 'reloc-progress', frames: relocFrames.length });
        break;
      }

      case 'reloc-solve': {
        const t0 = performance.now();
        if (!relocFusion) { self.postMessage({ type: 'reloc-result', ok: false, candidates: [], reason: 'sin barrido' }); break; }
        // nube fresca: entre intentos el barrido sigue sumando frames
        const sc = relocFusion.surfaceCloud(true);
        const step = Math.max(1, Math.floor(relocFrames.length / RELOC_VIS_FRAMES));
        const r = reloc.relocalize(fusion.surfaceCloud(), sc, {
          refine: (init) => fusion.alignPoints(sc.pos, { yaws: [0], initial: init, levels: [0.25, 0.15, 0.08, 0.05], itersPer: 4, minRatio: 0 }),
          score: (yaw, t) => fusion.scorePose(sc.pos, sc.nor, yaw, t, { resTol: reloc.RELOC_PARAMS.resTol, cosTol: reloc.RELOC_PARAMS.cosTol }),
          frames: relocFrames.filter((_, i) => i % step === 0),
          sessionFloorFallback: relocFloorFallback,
        });
        self.postMessage({
          type: 'reloc-result', ok: r.ok, ambiguous: r.ambiguous, reason: r.reason, ms: performance.now() - t0,
          candidates: r.candidates.slice(0, reloc.RELOC_PARAMS.maxShown).map((c) => ({
            matrix: Array.from(c.matrix), yaw: c.yaw, t: c.t, quality: c.quality, visibility: c.visibility, rank: c.rank,
          })),
        });
        break;
      }

      case 'reloc-confirm': {
        // el usuario confirmó visualmente: fijar alineación e integrar todo el barrido
        const t0 = performance.now();
        fusion.setAlign(m.matrix);
        for (const f of relocFrames) fusion.integrate(f);
        const n = relocFrames.length;
        resetReloc();
        const meshes = fusion.extract(Infinity).meshes;
        lastMesh = performance.now();
        send('reloc-confirmed', { frames: n, ms: performance.now() - t0 }, meshes);
        break;
      }

      case 'reloc-reset':
        resetReloc();
        break;

      case 'reloc-dump':
        // diagnóstico: devuelve los frames del barrido (sin color) para analizarlos fuera del teléfono
        self.postMessage({
          type: 'reloc-dump',
          frames: relocFrames.map((f) => ({
            depth: f.depth, width: f.width, height: f.height, rawToMeters: f.rawToMeters,
            normViewFromNormDepth: Array.from(f.normViewFromNormDepth), projection: Array.from(f.projection),
            viewMatrix: Array.from(f.viewMatrix),
          })),
          sessionFloorFallback: relocFloorFallback,
        });
        break;

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
}
