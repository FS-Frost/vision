// Compartir e importar escaneos.
// - Compartir: un .html autocontenido (visor three.js + el .glb en base64) que se abre
//   en cualquier navegador. Si el sistema no puede compartir archivos, se descarga.
// - Importar: .glb exportado o .html compartido -> lista de chunks como los del escáner.
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';

const THREE_VERSION = '0.180.0';

function blobToBase64(blob) {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result).split(',')[1]);
    r.onerror = () => reject(r.error);
    r.readAsDataURL(blob);
  });
}

const escapeHtml = (s) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

export async function buildViewerHtml(glb, name) {
  const b64 = await blobToBase64(new Blob([glb]));
  const title = escapeHtml(name);
  const cdn = `https://cdn.jsdelivr.net/npm/three@${THREE_VERSION}`;
  return `<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="generator" content="vision-scan">
<title>${title}</title>
<style>
  html, body { margin: 0; height: 100%; overflow: hidden; background: #0f1115; color: #e6e8ee;
    font: 14px/1.4 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  canvas { display: block; touch-action: none; }
  #info { position: fixed; left: 12px; right: 12px; bottom: max(12px, env(safe-area-inset-bottom));
    background: rgba(24,27,34,.9); border-radius: 10px; padding: 10px 12px; }
  #info b { display: block; }
  #info span { color: #8b93a3; font-size: 12px; }
</style>
<script type="importmap">{"imports":{"three":"${cdn}/build/three.module.js","three/addons/":"${cdn}/examples/jsm/"}}</script>
</head>
<body>
<div id="info"><b>${title}</b><span id="msg">Cargando modelo 3D…</span></div>
<script type="module">
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
const msg = document.getElementById('msg');
const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.setSize(innerWidth, innerHeight);
document.body.prepend(renderer.domElement);
const scene = new THREE.Scene();
scene.background = new THREE.Color(0x0f1115);
scene.add(new THREE.HemisphereLight(0xffffff, 0x404858, 2.2));
const camera = new THREE.PerspectiveCamera(60, innerWidth / innerHeight, 0.01, 200);
const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
addEventListener('resize', () => {
  camera.aspect = innerWidth / innerHeight; camera.updateProjectionMatrix();
  renderer.setSize(innerWidth, innerHeight);
});
const bin = Uint8Array.from(atob(document.getElementById('glb').textContent.trim()), (c) => c.charCodeAt(0));
new GLTFLoader().parse(bin.buffer, '', (gltf) => {
  scene.add(gltf.scene);
  const box = new THREE.Box3().setFromObject(gltf.scene);
  const c = box.getCenter(new THREE.Vector3()), size = box.getSize(new THREE.Vector3()).length();
  controls.target.copy(c);
  camera.position.copy(c).add(new THREE.Vector3(0, size * 0.45, size * 0.8));
  camera.near = size / 500; camera.far = size * 20; camera.updateProjectionMatrix();
  msg.textContent = 'Arrastra para orbitar, pellizca para acercar. Escaneado con Vision.';
}, (err) => { msg.textContent = 'No se pudo cargar el modelo: ' + err.message; });
renderer.setAnimationLoop(() => { controls.update(); renderer.render(scene, camera); });
</script>
<script id="glb" type="application/octet-stream">
${b64}
</script>
</body>
</html>
`;
}

/** Comparte (o descarga) un archivo. Devuelve 'shared' | 'downloaded' | 'cancelled'. */
export async function shareOrDownload(data, filename, type) {
  const file = new File([data], filename, { type });
  if (navigator.canShare?.({ files: [file] })) {
    try {
      await navigator.share({ files: [file], title: filename });
      return 'shared';
    } catch (err) {
      if (err.name === 'AbortError') return 'cancelled';
      // si falla por otra razón, caer a descarga
    }
  }
  const a = document.createElement('a');
  a.href = URL.createObjectURL(file);
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 10000);
  return 'downloaded';
}

/**
 * Lee un .glb o un .html compartido y devuelve chunks compatibles con el escáner.
 * @returns {Promise<{ name: string, hasColor: boolean, chunks: Array }>}
 */
export async function importFile(file) {
  let buffer;
  if (/\.html?$/i.test(file.name) || file.type === 'text/html') {
    const text = await file.text();
    const m = text.match(/<script id="glb"[^>]*>\s*([A-Za-z0-9+/=\s]+?)\s*<\/script>/);
    if (!m) throw new Error('El .html no contiene un escaneo de Vision');
    const bin = Uint8Array.from(atob(m[1].replace(/\s+/g, '')), (c) => c.charCodeAt(0));
    buffer = bin.buffer;
  } else {
    buffer = await file.arrayBuffer();
  }
  const gltf = await new GLTFLoader().parseAsync(buffer, '');
  gltf.scene.updateMatrixWorld(true);
  const chunks = [];
  let hasColor = false;
  gltf.scene.traverse((obj) => {
    if (!obj.isMesh) return;
    const g = obj.geometry.clone();
    g.applyMatrix4(obj.matrixWorld);
    const n = g.attributes.position.count;
    if (!g.index) {
      const idx = new Uint32Array(n);
      for (let i = 0; i < n; i++) idx[i] = i;
      g.setIndex(new THREE.BufferAttribute(idx, 1));
    }
    if (!g.attributes.normal) g.computeVertexNormals();
    const positions = Float32Array.from(g.attributes.position.array.slice(0, n * 3));
    const normals = Float32Array.from(g.attributes.normal.array.slice(0, n * 3));
    let colors;
    const col = g.attributes.color;
    if (col) {
      hasColor = true;
      colors = new Float32Array(n * 3);
      for (let i = 0; i < n; i++) {
        colors[i * 3] = col.getX(i); colors[i * 3 + 1] = col.getY(i); colors[i * 3 + 2] = col.getZ(i);
      }
    } else {
      colors = new Float32Array(n * 3).fill(0.6);
    }
    chunks.push({ key: chunks.length, positions, normals, colors, indices: Uint32Array.from(g.index.array) });
  });
  if (!chunks.length) throw new Error('El archivo no contiene mallas');
  const name = file.name.replace(/\.(glb|html?)$/i, '');
  return { name, hasColor, chunks };
}
