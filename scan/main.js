// Escáner: sesión WebXR (ARCore) -> profundidad + color + pose -> worker de fusión -> malla en three.js.
// Al terminar: visor (orbitar / caminar con colisiones), guardado en IndexedDB, continuar un
// escaneo guardado (alineando la sesión nueva), compartir como .html y exportar .glb.
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { GLTFExporter } from 'three/addons/exporters/GLTFExporter.js';
import { WalkControls } from './walk.js';
import { Collider } from './collider.js';
import { buildViewerHtml, shareOrDownload, importFile } from './share.js';
import * as db from './db.js';

const params = new URLSearchParams(location.search);
const SEND_INTERVAL_MS = 1000 / (parseFloat(params.get('hz')) || 10);
const ALIGN_INTERVAL_MS = 250;
const COLOR_TOP_DOWN = params.get('colorTopDown') === '1';
const COLOR_WIDTH = 120;   // ancho de la imagen de color leída de la GPU
const EYE_HEIGHT = 1.5;    // m sobre el suelo en modo paseo (máximo)

const $ = (s) => document.querySelector(s);
const ui = {
  sheet: $('#sheet'),
  home: $('#home'), homeText: $('#home-text'), scan: $('#btn-scan'), quality: $('#quality'),
  importBtn: $('#btn-import'), fileInput: $('#file-input'),
  saved: $('#saved'), savedList: $('#saved-list'),
  viewer: $('#viewer'), viewerTitle: $('#viewer-title'), viewerText: $('#viewer-text'), viewerStats: $('#viewer-stats'),
  walk: $('#btn-walk'), shade: $('#btn-shade'), cont: $('#btn-continue'), share: $('#btn-share'),
  exportBtn: $('#btn-export'), homeBtn: $('#btn-home'), newBtn: $('#btn-new'),
  overlay: $('#overlay'), hud: $('#hud'), hint: $('#hint'), pause: $('#btn-pause'), stop: $('#btn-stop'),
  joy: $('#joy'), joyKnob: $('#joy-knob'), walkHint: $('#walk-hint'),
  walkBar: $('#walk-bar'), walkExit: $('#btn-walk-exit'), collide: $('#btn-collide'),
};
if (params.get('voxel')) {
  const opt = new Option(`${parseFloat(params.get('voxel')) * 100} cm (URL)`, params.get('voxel'));
  ui.quality.add(opt, 0);
  ui.quality.value = opt.value;
}

// Estado observable (también desde DevTools remoto: window.vision)
const state = window.vision = {
  mode: 'home',          // home | scan | view
  paused: false,
  tracking: '-',
  refSpace: null,
  fps: 0,
  sent: 0,
  dropped: 0,
  colorFrames: 0,
  colorMs: 0,
  integrateMs: 0,
  meshMs: 0,
  depth: null,
  fusion: null,
  chunks: 0,
  hasColor: false,
  meta: null,            // escaneo mostrado: { id, name, createdAt, voxel, hasVolume }
  continuing: false,     // sesión actual continúa un escaneo guardado
  aligning: false,
  align: null,           // último resultado de alineación
  volume: null,          // TSDF exportado al terminar
  walking: false,
  errors: [],
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
$('#stage').append(renderer.domElement);

const scene = new THREE.Scene();
const BG = new THREE.Color(0x0f1115);
scene.background = BG;
const camera = new THREE.PerspectiveCamera(60, innerWidth / innerHeight, 0.01, 200);
camera.position.set(0, 1.2, 2.5);
const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
const walk = new WalkControls(camera, renderer.domElement, ui.joy, ui.joyKnob);

scene.add(new THREE.HemisphereLight(0xffffff, 0x404858, 2.2));
const sun = new THREE.DirectionalLight(0xffffff, 1.4);
sun.position.set(1.5, 3, 2);
scene.add(sun);

const materials = {
  ar: new THREE.MeshStandardMaterial({ color: 0x9cc8ff, roughness: 0.9, transparent: true, opacity: 0.55, side: THREE.DoubleSide }),
  color: new THREE.MeshBasicMaterial({ vertexColors: true, side: THREE.DoubleSide }),
  shade: new THREE.MeshStandardMaterial({ color: 0xd0d4dc, roughness: 0.9, side: THREE.DoubleSide }),
};
let currentMaterial = materials.ar;

// `world` contiene la malla en el marco del escaneo guardado. Al continuar un escaneo,
// su matriz es la inversa de la alineación (marco guardado -> marco de la sesión AR).
const world = new THREE.Group();
world.name = 'scan';
world.matrixAutoUpdate = false;
scene.add(world);
let grid = null;

function setWorldMatrix(arr) {
  if (arr) world.matrix.fromArray(arr); else world.matrix.identity();
  world.matrixWorldNeedsUpdate = true;
}

const chunks = new Map(); // clave de supercelda -> { mesh, data }

function applyMeshes(list, replaceAll = false) {
  if (replaceAll) clearWorld();
  for (const m of list) {
    const old = chunks.get(m.key);
    if (old) { old.mesh.geometry.dispose(); world.remove(old.mesh); chunks.delete(m.key); }
    if (!m.indices.length) continue;
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(m.positions, 3));
    g.setAttribute('normal', new THREE.BufferAttribute(m.normals, 3));
    g.setAttribute('color', new THREE.BufferAttribute(m.colors, 3));
    g.setIndex(new THREE.BufferAttribute(m.indices, 1));
    g.computeBoundingSphere();
    const mesh = new THREE.Mesh(g, currentMaterial);
    mesh.matrixAutoUpdate = false;
    world.add(mesh);
    chunks.set(m.key, { mesh, data: m });
  }
  state.chunks = chunks.size;
}

function clearWorld() {
  for (const { mesh } of chunks.values()) { mesh.geometry.dispose(); world.remove(mesh); }
  chunks.clear();
  state.chunks = 0;
}

function setMaterial(mat) {
  currentMaterial = mat;
  for (const { mesh } of chunks.values()) mesh.material = mat;
}

function countTriangles() {
  let t = 0;
  for (const { data } of chunks.values()) t += data.indices.length / 3;
  return t;
}

addEventListener('resize', () => {
  if (renderer.xr.isPresenting) return;
  camera.aspect = innerWidth / innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(innerWidth, innerHeight);
});

// ---------------------------------------------------------------- worker

// el worker no ve el import map: se le pasa la misma versión (?v=...) con la que cargó main.js
const BUILD_QUERY = new URL(import.meta.url).search;
const worker = new Worker(new URL(`./fusion-worker.js${BUILD_QUERY}`, import.meta.url), { type: 'module' });
let workerBusy = false;
let flushResolve = null;

worker.onmessage = (ev) => {
  const m = ev.data;
  if (m.type === 'error') { workerBusy = false; logError('worker', { name: '', message: m.message }); return; }
  if (m.meshes?.length || m.replaceAll) applyMeshes(m.meshes ?? [], !!m.replaceAll);
  if (m.stats) state.fusion = m.stats;
  switch (m.type) {
    case 'frame':
      workerBusy = false;
      state.integrateMs = m.integrateMs;
      state.meshMs = m.meshMs;
      break;
    case 'aligning':
      workerBusy = false;
      state.align = { pending: true, frames: m.frames, needed: m.needed };
      break;
    case 'aligned':
      workerBusy = false;
      state.align = m;
      if (m.ok) {
        state.aligning = false;
        // la malla guardada está en el marco guardado; para verla en la sesión: inversa
        setWorldMatrix(new THREE.Matrix4().fromArray(m.matrix).invert().toArray());
        world.visible = true;
      }
      break;
    case 'flushed':
      state.meshMs = m.meshMs;
      state.removedTris = m.removedTris;
      state.volume = m.volume;
      flushResolve?.();
      flushResolve = null;
      break;
  }
};
worker.onerror = (e) => { workerBusy = false; logError('worker', e); };

// ---------------------------------------------------------------- color de cámara

// La imagen de cámara (textura GL válida sólo durante el frame) se dibuja en un
// render target pequeño y se lee de forma asíncrona (PBO) para no bloquear la GPU.
const quadScene = new THREE.Scene();
const quadCam = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1);
const quadMat = new THREE.MeshBasicMaterial({ depthTest: false, depthWrite: false });
quadScene.add(new THREE.Mesh(new THREE.PlaneGeometry(2, 2), quadMat));
let colorRT = null;

function grabCameraColor(view) {
  if (!view.camera) return null;
  const tex = renderer.xr.getCameraTexture(view.camera);
  if (!tex) return null;
  const w = COLOR_WIDTH;
  const h = Math.round((w * view.camera.height) / view.camera.width);
  if (!colorRT || colorRT.width !== w || colorRT.height !== h) {
    colorRT?.dispose();
    colorRT = new THREE.WebGLRenderTarget(w, h, { depthBuffer: false });
  }
  if (quadMat.map !== tex) { quadMat.map = tex; quadMat.needsUpdate = true; }
  const prevTarget = renderer.getRenderTarget();
  renderer.xr.enabled = false; // dibujar con la cámara ortográfica, no la de XR
  renderer.setRenderTarget(colorRT);
  renderer.render(quadScene, quadCam);
  renderer.setRenderTarget(prevTarget);
  renderer.xr.enabled = true;
  const buf = new Uint8Array(w * h * 4);
  const t0 = performance.now();
  return renderer.readRenderTargetPixelsAsync(colorRT, 0, 0, w, h, buf)
    .then(() => { state.colorMs = performance.now() - t0; return { buf, w, h }; });
}

// ---------------------------------------------------------------- sesión AR

/** @param {{ id, name, createdAt } | null} continueFrom escaneo guardado a continuar */
async function startScan(continueFrom = null) {
  // requestSession debe llamarse sin awaits previos (gesto de usuario)
  let session;
  try {
    session = await navigator.xr.requestSession('immersive-ar', {
      requiredFeatures: ['depth-sensing'],
      optionalFeatures: ['local-floor', 'camera-access', 'dom-overlay'],
      domOverlay: { root: ui.overlay },
      depthSensing: { usagePreference: ['cpu-optimized'], dataFormatPreference: ['luminance-alpha', 'float32'] },
    });
  } catch (err) {
    logError('requestSession', err);
    showHome(`No se pudo iniciar AR: ${err.message}`);
    return;
  }

  let volume = null;
  if (continueFrom) {
    volume = await db.loadVolume(continueFrom.id).catch((err) => { logError('db', err); return null; });
    if (!volume) {
      session.end();
      ui.viewerText.textContent = 'Este escaneo no tiene volumen guardado: no se puede continuar.';
      return;
    }
  }
  const voxel = volume?.voxel ?? (parseFloat(ui.quality.value) || 0.03);
  worker.postMessage({ type: 'reset', options: { voxel, colorTopDown: COLOR_TOP_DOWN }, volume });
  workerBusy = false;
  stopWalking();
  if (!continueFrom) { clearWorld(); state.meta = null; }
  setWorldMatrix(null);
  world.visible = !continueFrom; // la malla guardada se muestra cuando la sesión esté alineada
  Object.assign(state, {
    mode: 'scan', paused: false, sent: 0, dropped: 0, colorFrames: 0, fusion: null,
    continuing: !!continueFrom, aligning: !!continueFrom, align: null, volume: null,
  });
  ui.pause.textContent = 'Pausar';
  ui.sheet.hidden = true;
  ui.overlay.hidden = false;
  scene.background = null;
  if (grid) grid.visible = false;
  setMaterial(materials.ar);

  const features = session.enabledFeatures ?? [];
  state.refSpace = features.includes('local-floor') ? 'local-floor' : 'local';
  renderer.xr.setReferenceSpaceType(state.refSpace);
  session.addEventListener('end', onSessionEnd);
  await renderer.xr.setSession(session);
}

async function onSessionEnd() {
  ui.overlay.hidden = true;
  setWorldMatrix(null);
  world.visible = true;
  state.mode = 'view';
  ui.viewerTitle.textContent = 'Procesando…';
  ui.viewerText.textContent = 'Cerrando la malla y limpiando fragmentos.';
  showSheet('viewer');

  if (state.continuing && state.aligning) {
    // nunca se alineó: no se integró nada, se deja el escaneo como estaba
    state.continuing = state.aligning = false;
    showViewer(state.meta);
    ui.viewerText.textContent = 'No se logró alinear con el escaneo guardado; no se modificó.';
    return;
  }
  await new Promise((res) => { flushResolve = res; worker.postMessage({ type: 'flush' }); });
  state.hasColor = (state.fusion?.colorFrames ?? 0) > 0 || (state.continuing && state.hasColor);
  const prev = state.continuing ? state.meta : null;
  state.continuing = false;
  showViewer(prev ?? { name: 'Escaneo nuevo' });
  if (!chunks.size) return;
  try {
    // una continuación se guarda como escaneo nuevo: el original se conserva por si la alineación falló
    const stamp = new Date().toLocaleString('es', { dateStyle: 'short', timeStyle: 'short' });
    const name = prev ? `${prev.name.replace(/ \+ ampliado.*$/, '')} + ampliado ${stamp}` : `Escaneo ${stamp}`;
    const id = await db.saveScan({
      name,
      voxel: state.fusion?.voxel, hasColor: state.hasColor, volume: state.volume,
      chunks: [...chunks.values()].map((c) => c.data),
    });
    state.meta = { id, name, createdAt: Date.now(), voxel: state.fusion?.voxel, hasVolume: !!state.volume };
    showViewer(state.meta);
    ui.viewerText.textContent = prev
      ? 'Ampliación guardada como escaneo nuevo; el original se conserva en Mis escaneos.'
      : 'Guardado en el teléfono. Arrastra para orbitar o pulsa Caminar.';
  } catch (err) {
    logError('save', err);
    ui.viewerText.textContent = `No se pudo guardar: ${err.message}`;
  }
}

let fpsFrames = 0, fpsT0 = 0, lastSend = -Infinity;

function depthMessage(type, frame, view, di) {
  const buffer = di.data.slice(0); // copia: el buffer original sólo vale durante este frame
  return {
    msg: {
      type, buffer, format: frame.session.depthDataFormat,
      width: di.width, height: di.height, rawToMeters: di.rawValueToMeters,
      normViewFromNormDepth: Array.from(di.normDepthBufferFromNormView.inverse.matrix),
      projection: Array.from(view.projectionMatrix),
      viewMatrix: Array.from(view.transform.matrix),
    },
    transfer: [buffer],
  };
}

function onXRFrame(time, frame) {
  fpsFrames++;
  if (time - fpsT0 > 1000) { state.fps = +((fpsFrames * 1000) / (time - fpsT0)).toFixed(1); fpsFrames = 0; fpsT0 = time; }

  const pose = frame.getViewerPose(renderer.xr.getReferenceSpace());
  if (!pose || pose.emulatedPosition) { state.tracking = 'perdido'; return; }
  state.tracking = 'ok';
  const interval = state.aligning ? ALIGN_INTERVAL_MS : SEND_INTERVAL_MS;
  if (state.paused || time - lastSend < interval) return;
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
  state.depth = { width: di.width, height: di.height, format: frame.session.depthDataFormat };
  workerBusy = true;
  lastSend = time;

  if (state.aligning) {
    const { msg, transfer } = depthMessage('align-frame', frame, view, di);
    worker.postMessage(msg, transfer);
    return;
  }

  const { msg, transfer } = depthMessage('integrate', frame, view, di);
  state.sent++;
  let colorPromise = null;
  try { colorPromise = grabCameraColor(view); } catch (err) { logError('color', err); }
  if (!colorPromise) { worker.postMessage(msg, transfer); return; }
  colorPromise
    .then((c) => {
      msg.color = c.buf.buffer; msg.colorWidth = c.w; msg.colorHeight = c.h;
      transfer.push(c.buf.buffer);
      state.colorFrames++;
    })
    .catch((err) => logError('color', err))
    .finally(() => worker.postMessage(msg, transfer));
}

const clock = new THREE.Clock();
renderer.setAnimationLoop((time, frame) => {
  const dt = Math.min(clock.getDelta(), 0.1);
  if (frame) {
    try { onXRFrame(time, frame); } catch (err) { logError('frame', err); }
  } else if (state.walking) {
    walk.update(dt);
  } else {
    controls.update();
  }
  renderer.render(scene, camera);
});

// ---------------------------------------------------------------- vista

function showSheet(which) {
  ui.home.hidden = which !== 'home';
  ui.viewer.hidden = which !== 'viewer';
  ui.sheet.hidden = false;
}

function frameCamera() {
  const box = new THREE.Box3().setFromObject(world);
  if (box.isEmpty()) return null;
  const center = box.getCenter(new THREE.Vector3());
  const size = box.getSize(new THREE.Vector3()).length();
  controls.target.copy(center);
  camera.position.copy(center).add(new THREE.Vector3(0, size * 0.45, size * 0.8));
  camera.near = Math.max(0.01, size / 500);
  camera.far = size * 20;
  camera.updateProjectionMatrix();
  return { box, center, size };
}

function estimateFloor() {
  const ys = [];
  for (const { data } of chunks.values()) {
    const p = data.positions, n = data.normals;
    for (let i = 0; i < p.length; i += 12) if (n[i + 1] > 0.85) ys.push(p[i + 1]);
  }
  if (ys.length < 50) return null;
  ys.sort((a, b) => a - b);
  return ys[Math.floor(ys.length * 0.03)];
}

function showViewer(meta) {
  state.mode = 'view';
  scene.background = BG;
  stopWalking();
  setMaterial(state.hasColor ? materials.color : materials.shade);
  ui.shade.textContent = currentMaterial === materials.color ? 'Sombreado' : 'Color';
  ui.shade.disabled = !state.hasColor;

  const framed = frameCamera();
  if (grid) { scene.remove(grid); grid = null; }
  if (framed) {
    const floor = estimateFloor() ?? framed.box.min.y;
    const n = Math.ceil(framed.size * 1.5);
    grid = new THREE.GridHelper(n, n * 2, 0x3a4150, 0x252a33);
    grid.position.set(framed.center.x, floor - 0.005, framed.center.z);
    scene.add(grid);
  }
  controls.enabled = true;
  controls.update();

  const tris = countTriangles();
  ui.viewerTitle.textContent = meta?.name ?? 'Escaneo';
  ui.viewerText.textContent = chunks.size
    ? 'Arrastra para orbitar, pellizca para acercar, o pulsa Caminar.'
    : 'No se capturó geometría. Prueba de nuevo moviéndote más despacio.';
  const voxel = meta?.voxel ?? state.fusion?.voxel;
  ui.viewerStats.textContent = [
    `${tris.toLocaleString('es')} triángulos`,
    voxel ? `voxel ${Math.round(voxel * 100)} cm` : null,
    state.hasColor ? 'con color' : 'sin color',
    state.removedTris ? `${state.removedTris.toLocaleString('es')} tri sueltos eliminados` : null,
  ].filter(Boolean).join(' · ');
  const has = chunks.size > 0;
  ui.walk.disabled = ui.exportBtn.disabled = ui.share.disabled = !has;
  ui.cont.hidden = !meta?.hasVolume;
  showSheet('viewer');
}

function startWalking() {
  const box = new THREE.Box3().setFromObject(world);
  if (box.isEmpty()) return;
  const floor = estimateFloor() ?? box.min.y;
  const collider = new Collider([...chunks.values()].map((c) => c.data), floor);
  // empezar donde empezó el escaneo (origen) si está dentro del recinto; si no, en el centro
  const center = box.getCenter(new THREE.Vector3());
  const inside = box.min.x < 0 && box.max.x > 0 && box.min.z < 0 && box.max.z > 0;
  let [sx, sz] = inside ? [0, 0] : [center.x, center.z];
  [sx, sz] = collider.nearestFree(sx, sz);
  // ojos a 1.5 m o, si se escaneó poca altura, a 3/4 de lo escaneado (para quedar "dentro")
  const eye = Math.min(EYE_HEIGHT, Math.max(0.6, (box.max.y - floor) * 0.75));
  camera.near = 0.02;
  camera.far = 100;
  camera.updateProjectionMatrix();
  controls.enabled = false;
  walk.collider = collider;
  walk.start(new THREE.Vector3(sx, floor + eye, sz), 0);
  state.walking = true;
  ui.walk.textContent = 'Orbitar';
  ui.sheet.hidden = true;
  ui.walkBar.hidden = false;
  ui.walkHint.hidden = false;
  setTimeout(() => { ui.walkHint.hidden = true; }, 4000);
}

function stopWalking() {
  if (!state.walking) return;
  walk.stop();
  state.walking = false;
  ui.walk.textContent = 'Caminar';
  ui.walkHint.hidden = true;
  ui.walkBar.hidden = true;
  if (state.mode === 'view') ui.sheet.hidden = false;
  controls.enabled = true;
  frameCamera();
}

async function buildGlb() {
  const prev = currentMaterial;
  setMaterial(state.hasColor ? materials.color : materials.shade);
  try {
    return await new GLTFExporter().parseAsync(world, { binary: true });
  } finally {
    setMaterial(prev);
  }
}

const fileSlug = () => (state.meta?.name ?? 'escaneo').replace(/[^\p{L}\p{N}]+/gu, '-').replace(/^-|-$/g, '').toLowerCase() || 'escaneo';

async function exportGlb() {
  ui.exportBtn.disabled = true;
  try {
    const glb = await buildGlb();
    await shareOrDownload(glb, `${fileSlug()}.glb`, 'model/gltf-binary');
  } catch (err) {
    logError('export', err);
  } finally {
    ui.exportBtn.disabled = false;
  }
}

async function shareScan() {
  ui.share.disabled = true;
  const prevText = ui.share.textContent;
  ui.share.textContent = 'Preparando…';
  try {
    const glb = await buildGlb();
    const html = await buildViewerHtml(glb, state.meta?.name ?? 'Escaneo 3D');
    const r = await shareOrDownload(html, `${fileSlug()}.html`, 'text/html');
    ui.viewerText.textContent = r === 'downloaded'
      ? 'Se descargó un .html con el visor: envíalo y se abre en cualquier navegador.'
      : r === 'shared' ? 'Compartido como página .html (se abre en cualquier navegador).' : ui.viewerText.textContent;
  } catch (err) {
    logError('share', err);
    ui.viewerText.textContent = `No se pudo compartir: ${err.message}`;
  } finally {
    ui.share.textContent = prevText;
    ui.share.disabled = false;
  }
}

// ---------------------------------------------------------------- inicio / guardados

async function showHome(message) {
  stopWalking();
  state.mode = 'home';
  if (message) ui.homeText.textContent = message;
  showSheet('home');
  await renderSaved();
}

async function renderSaved() {
  let list = [];
  try { list = await db.listScans(); } catch (err) { logError('db', err); }
  ui.saved.hidden = !list.length;
  ui.savedList.replaceChildren(...list.map((s) => {
    const row = document.createElement('div');
    row.className = 'scan-item';
    const info = document.createElement('div');
    info.className = 'info';
    const name = document.createElement('div');
    name.className = 'name';
    name.textContent = s.name;
    const meta = document.createElement('div');
    meta.className = 'meta';
    meta.textContent = [
      `${Math.round(s.triangles).toLocaleString('es')} tri`,
      `${(s.bytes / 1e6).toFixed(1)} MB`,
      s.hasColor ? 'color' : null,
      s.hasVolume ? 'ampliable' : null,
    ].filter(Boolean).join(' · ');
    info.append(name, meta);
    const open = document.createElement('button');
    open.className = 'small';
    open.textContent = 'Abrir';
    open.addEventListener('click', () => openScan(s.id));
    const del = document.createElement('button');
    del.className = 'small secondary';
    del.textContent = 'Borrar';
    let armed = false;
    del.addEventListener('click', async () => {
      if (!armed) {
        armed = true;
        del.textContent = '¿Seguro?';
        del.className = 'small danger';
        setTimeout(() => { armed = false; del.textContent = 'Borrar'; del.className = 'small secondary'; }, 3000);
        return;
      }
      await db.deleteScan(s.id).catch((err) => logError('db', err));
      if (state.meta?.id === s.id) { clearWorld(); state.meta = null; }
      renderSaved();
    });
    row.append(info, open, del);
    return row;
  }));
}

async function openScan(id) {
  try {
    const scan = await db.loadScan(id);
    if (!scan) return;
    applyMeshes(scan.chunks, true);
    state.meta = scan;
    state.hasColor = !!scan.hasColor;
    state.removedTris = 0;
    state.fusion = null;
    showViewer(scan);
  } catch (err) {
    logError('db', err);
  }
}

async function importFromFile(file) {
  ui.homeText.textContent = `Abriendo ${file.name}…`;
  try {
    const r = await importFile(file);
    applyMeshes(r.chunks, true);
    state.hasColor = r.hasColor;
    state.removedTris = 0;
    state.fusion = null;
    const id = await db.saveScan({ name: r.name, hasColor: r.hasColor, chunks: r.chunks });
    state.meta = { id, name: r.name, hasVolume: false };
    showViewer(state.meta);
    ui.viewerText.textContent = 'Importado y guardado. Los escaneos importados no se pueden continuar.';
  } catch (err) {
    logError('import', err);
    ui.homeText.textContent = `No se pudo abrir el archivo: ${err.message}`;
  }
}

// ---------------------------------------------------------------- HUD

setInterval(() => {
  if (state.mode !== 'scan') return;
  const f = state.fusion;
  let alignLine = '';
  if (state.continuing) {
    const a = state.align;
    alignLine = state.aligning
      ? `alineando… ${a?.pending ? `${a.frames}/${a.needed} frames` : a ? `intento ${a.attempts}: ${Math.round((a.inlierRatio ?? 0) * 100)}% coincide${a.candidate ? ', confirmando' : ''}` : ''}`
      : `alineado ✓ ${a ? `${Math.round(a.inlierRatio * 100)}% · ${(a.rms * 100).toFixed(1)} cm` : ''} · solo zonas nuevas`;
  }
  ui.hud.textContent = [
    `fps ${state.fps}   tracking ${state.tracking}${state.paused ? '   PAUSA' : ''}`,
    alignLine,
    `depth ${state.depth ? `${state.depth.width}x${state.depth.height}` : '-'}   color ${state.colorFrames ? `ok ${state.colorMs.toFixed(0)} ms` : '-'}`,
    `fusión ${state.integrateMs.toFixed(1)} ms   malla ${state.meshMs.toFixed(1)} ms`,
    f ? `bloques ${f.blocks}   tri ${f.triangles.toLocaleString('es')}   ${f.memoryMB} MB` : 'bloques -',
    state.errors.length ? `ERROR: ${state.errors.at(-1)}` : '',
  ].filter(Boolean).join('\n');
  if (state.tracking !== 'ok') {
    ui.hint.hidden = false;
    ui.hint.textContent = 'Tracking perdido: mueve el teléfono despacio y apunta a zonas con textura';
  } else if (state.aligning) {
    ui.hint.hidden = false;
    ui.hint.textContent = 'Ponte donde empezaste el escaneo y apunta a zonas ya escaneadas';
  } else {
    ui.hint.hidden = true;
  }
}, 250);

// ---------------------------------------------------------------- botones

ui.scan.addEventListener('click', () => startScan());
ui.newBtn.addEventListener('click', () => startScan());
ui.cont.addEventListener('click', () => state.meta && startScan(state.meta));
ui.pause.addEventListener('click', () => {
  state.paused = !state.paused;
  ui.pause.textContent = state.paused ? 'Reanudar' : 'Pausar';
});
ui.stop.addEventListener('click', () => renderer.xr.getSession()?.end());
ui.exportBtn.addEventListener('click', exportGlb);
ui.share.addEventListener('click', shareScan);
ui.homeBtn.addEventListener('click', () => showHome());
ui.walk.addEventListener('click', () => (state.walking ? stopWalking() : startWalking()));
ui.walkExit.addEventListener('click', stopWalking);
ui.collide.addEventListener('click', () => {
  walk.collide = !walk.collide;
  ui.collide.textContent = `Colisiones: ${walk.collide ? 'sí' : 'no'}`;
});
ui.shade.addEventListener('click', () => {
  setMaterial(currentMaterial === materials.color ? materials.shade : materials.color);
  ui.shade.textContent = currentMaterial === materials.color ? 'Sombreado' : 'Color';
});
ui.importBtn.addEventListener('click', () => ui.fileInput.click());
ui.fileInput.addEventListener('change', () => {
  const file = ui.fileInput.files?.[0];
  ui.fileInput.value = '';
  if (file) importFromFile(file);
});

// ---------------------------------------------------------------- arranque

(async () => {
  const ok = navigator.xr && await navigator.xr.isSessionSupported('immersive-ar').catch(() => false);
  if (!ok) {
    await showHome('Este navegador no soporta WebXR immersive-ar. Puedes abrir escaneos guardados o archivos .glb/.html.');
    return;
  }
  ui.scan.disabled = false;
  await showHome('Pulsa Escanear y recorre la habitación despacio.');
})();
