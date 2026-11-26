// Escáner: sesión WebXR (ARCore) -> profundidad + pose -> worker de fusión -> malla en three.js.
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { GLTFExporter } from 'three/addons/exporters/GLTFExporter.js';

const params = new URLSearchParams(location.search);
const VOXEL = parseFloat(params.get('voxel')) || 0.04;      // metros
const SEND_INTERVAL_MS = 1000 / (parseFloat(params.get('hz')) || 10);

const $ = (s) => document.querySelector(s);
const ui = {
  sheet: $('#sheet'), title: $('#sheet-title'), text: $('#sheet-text'), sheetStats: $('#sheet-stats'),
  scan: $('#btn-scan'), exportBtn: $('#btn-export'), newBtn: $('#btn-new'),
  overlay: $('#overlay'), hud: $('#hud'), hint: $('#hint'), pause: $('#btn-pause'), stop: $('#btn-stop'),
};

// Estado observable (también desde DevTools remoto: window.vision)
const state = window.vision = {
  mode: 'idle',          // idle | scan | view
  paused: false,
  tracking: '-',
  fps: 0,
  sent: 0,
  dropped: 0,
  integrateMs: 0,
  meshMs: 0,
  depth: null,           // { width, height, format }
  fusion: null,          // stats del worker
  chunks: 0,
  errors: [],
  voxel: VOXEL,
};
const logError = (where, err) => {
  const msg = `${where}: ${err?.name ?? ''} ${err?.message ?? err}`;
  state.errors.push(msg);
  if (state.errors.length > 20) state.errors.shift();
  console.error(msg, err);
};

// ---------------------------------------------------------------- three.js

const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.setSize(innerWidth, innerHeight);
renderer.xr.enabled = true;
renderer.xr.setReferenceSpaceType('local');
$('#stage').append(renderer.domElement);

const scene = new THREE.Scene();
const BG = new THREE.Color(0x0f1115);
scene.background = BG;
const camera = new THREE.PerspectiveCamera(60, innerWidth / innerHeight, 0.01, 200);
camera.position.set(0, 1.2, 2.5);
const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;

scene.add(new THREE.HemisphereLight(0xffffff, 0x404858, 2.2));
const sun = new THREE.DirectionalLight(0xffffff, 1.4);
sun.position.set(1.5, 3, 2);
scene.add(sun);

const material = new THREE.MeshStandardMaterial({
  color: 0x9cc8ff, roughness: 0.9, metalness: 0, side: THREE.DoubleSide,
});
const world = new THREE.Group();
world.name = 'scan';
scene.add(world);
let grid = null;

const chunks = new Map(); // clave de supercelda -> THREE.Mesh

function applyMeshes(list) {
  for (const m of list) {
    const old = chunks.get(m.key);
    if (old) { old.geometry.dispose(); world.remove(old); chunks.delete(m.key); }
    if (!m.indices.length) continue;
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(m.positions, 3));
    g.setAttribute('normal', new THREE.BufferAttribute(m.normals, 3));
    g.setIndex(new THREE.BufferAttribute(m.indices, 1));
    g.computeBoundingSphere();
    const mesh = new THREE.Mesh(g, material);
    mesh.matrixAutoUpdate = false;
    world.add(mesh);
    chunks.set(m.key, mesh);
  }
  state.chunks = chunks.size;
}

function clearWorld() {
  for (const mesh of chunks.values()) { mesh.geometry.dispose(); world.remove(mesh); }
  chunks.clear();
  state.chunks = 0;
}

addEventListener('resize', () => {
  if (renderer.xr.isPresenting) return;
  camera.aspect = innerWidth / innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(innerWidth, innerHeight);
});

// ---------------------------------------------------------------- worker

const worker = new Worker(new URL('./fusion-worker.js', import.meta.url), { type: 'module' });
worker.postMessage({ type: 'init', options: { voxel: VOXEL } });
let workerBusy = false;
let flushResolve = null;

worker.onmessage = (ev) => {
  const m = ev.data;
  if (m.type === 'error') { workerBusy = false; logError('worker', { name: '', message: m.message }); return; }
  if (m.meshes?.length) applyMeshes(m.meshes);
  if (m.stats) state.fusion = m.stats;
  if (m.type === 'frame') {
    workerBusy = false;
    state.integrateMs = m.integrateMs;
    state.meshMs = m.meshMs;
  } else if (m.type === 'flushed') {
    flushResolve?.();
    flushResolve = null;
  }
};
worker.onerror = (e) => { workerBusy = false; logError('worker', e); };

// ---------------------------------------------------------------- sesión AR

async function startScan() {
  let session;
  try {
    session = await navigator.xr.requestSession('immersive-ar', {
      requiredFeatures: ['local', 'depth-sensing'],
      optionalFeatures: ['dom-overlay'],
      domOverlay: { root: ui.overlay },
      depthSensing: { usagePreference: ['cpu-optimized'], dataFormatPreference: ['luminance-alpha', 'float32'] },
    });
  } catch (err) {
    logError('requestSession', err);
    ui.text.textContent = `No se pudo iniciar AR: ${err.message}`;
    return;
  }
  state.mode = 'scan';
  state.paused = false;
  ui.pause.textContent = 'Pausar';
  ui.sheet.hidden = true;
  ui.overlay.hidden = false;
  scene.background = null;
  if (grid) grid.visible = false;
  material.transparent = true;
  material.opacity = 0.55;
  session.addEventListener('end', onSessionEnd);
  await renderer.xr.setSession(session);
}

async function onSessionEnd() {
  ui.overlay.hidden = true;
  state.mode = 'view';
  // terminar el mallado pendiente antes de mostrar la escena
  await new Promise((res) => { flushResolve = res; worker.postMessage({ type: 'flush' }); });
  showViewer();
}

let fpsFrames = 0, fpsT0 = 0, lastSend = -Infinity;

function onXRFrame(time, frame) {
  fpsFrames++;
  if (time - fpsT0 > 1000) { state.fps = +(fpsFrames * 1000 / (time - fpsT0)).toFixed(1); fpsFrames = 0; fpsT0 = time; }

  const pose = frame.getViewerPose(renderer.xr.getReferenceSpace());
  if (!pose || pose.emulatedPosition) { state.tracking = 'perdido'; return; }
  state.tracking = 'ok';
  if (state.paused || time - lastSend < SEND_INTERVAL_MS) return;
  if (workerBusy) { state.dropped++; return; }

  const view = pose.views[0];
  let di;
  try {
    di = (frame.getDepthInformation ?? frame.getDepthInfo).call(frame, view);
  } catch (err) {
    logError('depth', err);
    return;
  }
  if (!di) return;
  const format = frame.session.depthDataFormat;
  const buffer = di.data.slice(0); // copia: el buffer original sólo vale durante este frame
  state.depth = { width: di.width, height: di.height, format };
  workerBusy = true;
  lastSend = time;
  state.sent++;
  worker.postMessage({
    type: 'integrate', buffer, format,
    width: di.width, height: di.height, rawToMeters: di.rawValueToMeters,
    normViewFromNormDepth: Array.from(di.normDepthBufferFromNormView.inverse.matrix),
    projection: Array.from(view.projectionMatrix),
    viewMatrix: Array.from(view.transform.matrix),
  }, [buffer]);
}

renderer.setAnimationLoop((time, frame) => {
  if (frame) {
    try { onXRFrame(time, frame); } catch (err) { logError('frame', err); }
  } else {
    controls.update();
  }
  renderer.render(scene, camera);
});

// ---------------------------------------------------------------- vista

function showViewer() {
  scene.background = BG;
  material.transparent = false;
  material.opacity = 1;
  material.needsUpdate = true;

  const box = new THREE.Box3().setFromObject(world);
  if (!box.isEmpty()) {
    const center = box.getCenter(new THREE.Vector3());
    const size = box.getSize(new THREE.Vector3()).length();
    controls.target.copy(center);
    camera.position.copy(center).add(new THREE.Vector3(0, size * 0.45, size * 0.8));
    camera.near = size / 500;
    camera.far = size * 20;
    camera.updateProjectionMatrix();
    if (grid) scene.remove(grid);
    grid = new THREE.GridHelper(Math.ceil(size * 1.5), Math.ceil(size * 1.5) * 2, 0x3a4150, 0x252a33);
    grid.position.set(center.x, box.min.y, center.z);
    scene.add(grid);
  }
  controls.update();

  const f = state.fusion;
  ui.title.textContent = 'Escaneo listo';
  ui.text.textContent = chunks.size
    ? 'Arrastra para orbitar, pellizca para acercar.'
    : 'No se capturó geometría. Prueba de nuevo moviéndote más despacio.';
  ui.sheetStats.textContent = f ? `${f.triangles.toLocaleString('es')} triángulos · ${f.blocks} bloques · ${f.memoryMB} MB` : '';
  ui.scan.hidden = true;
  ui.exportBtn.hidden = !chunks.size;
  ui.newBtn.hidden = false;
  ui.sheet.hidden = false;
}

async function exportGlb() {
  ui.exportBtn.disabled = true;
  try {
    const glb = await new GLTFExporter().parseAsync(world, { binary: true });
    const blob = new Blob([glb], { type: 'model/gltf-binary' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `vision-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.glb`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 10000);
  } catch (err) {
    logError('export', err);
  } finally {
    ui.exportBtn.disabled = false;
  }
}

// ---------------------------------------------------------------- HUD

setInterval(() => {
  if (state.mode !== 'scan') return;
  const f = state.fusion;
  ui.hud.textContent = [
    `fps ${state.fps}   tracking ${state.tracking}${state.paused ? '   PAUSA' : ''}`,
    `depth ${state.depth ? `${state.depth.width}x${state.depth.height}` : '-'}   enviados ${state.sent}`,
    `fusión ${state.integrateMs.toFixed(1)} ms   malla ${state.meshMs.toFixed(1)} ms`,
    f ? `bloques ${f.blocks}   tri ${f.triangles.toLocaleString('es')}   ${f.memoryMB} MB` : 'bloques -',
    state.errors.length ? `ERROR: ${state.errors.at(-1)}` : '',
  ].filter(Boolean).join('\n');
  ui.hint.hidden = state.tracking === 'ok';
  ui.hint.textContent = 'Tracking perdido: mueve el teléfono despacio y apunta a zonas con textura';
}, 250);

// ---------------------------------------------------------------- botones

ui.scan.addEventListener('click', startScan);
ui.pause.addEventListener('click', () => {
  state.paused = !state.paused;
  ui.pause.textContent = state.paused ? 'Reanudar' : 'Pausar';
});
ui.stop.addEventListener('click', () => renderer.xr.getSession()?.end());
ui.exportBtn.addEventListener('click', exportGlb);
ui.newBtn.addEventListener('click', () => {
  clearWorld();
  worker.postMessage({ type: 'reset' });
  state.fusion = null;
  startScan();
});

// ---------------------------------------------------------------- arranque

(async () => {
  const ok = navigator.xr && await navigator.xr.isSessionSupported('immersive-ar').catch(() => false);
  if (!ok) {
    ui.text.textContent = 'Este navegador no soporta WebXR immersive-ar. Usa Chrome en Android con ARCore.';
    return;
  }
  ui.text.textContent = `Pulsa Escanear y recorre la habitación despacio. Voxel ${Math.round(VOXEL * 100)} cm.`;
  ui.scan.disabled = false;
})();
