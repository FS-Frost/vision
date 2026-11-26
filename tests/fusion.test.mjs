// Tests del núcleo de fusión. Ejecutar: node --test tests/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Fusion } from '../scan/fusion-core.js';

const W = 160, H = 90;
const F = 1 / Math.tan(Math.PI / 6); // fov vertical 60°
const PROJ = (() => {
  const p = new Float32Array(16);
  p[0] = F / (W / H); p[5] = F; p[10] = -1; p[11] = -1; p[14] = -0.02;
  return p;
})();
const IDENT = Float32Array.from([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]);

// Rotación alrededor de Y (column-major) + traslación
function viewMatrix(angleY, tx = 0, ty = 0, tz = 0) {
  const c = Math.cos(angleY), s = Math.sin(angleY);
  return Float32Array.from([c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, tx, ty, tz, 1]);
}

function planeDepth(dMeters) {
  const depth = new Uint16Array(W * H);
  depth.fill(Math.round(dMeters * 1000));
  return depth;
}

function scan(fusion, view, frames = 5, d = 1.5) {
  for (let i = 0; i < frames; i++) {
    fusion.integrate({
      depth: planeDepth(d), width: W, height: H, rawToMeters: 0.001,
      normViewFromNormDepth: IDENT, projection: PROJ, viewMatrix: view,
    });
  }
  let meshes = [];
  for (let guard = 0; guard < 100; guard++) {
    const r = fusion.extract(Infinity);
    meshes = meshes.concat(r.meshes);
    if (r.pending === 0) break;
  }
  // quedarse con la última versión de cada supercelda
  const bySuper = new Map(meshes.map((m) => [m.key, m]));
  return [...bySuper.values()];
}

function collect(meshes) {
  const verts = [], norms = [], tris = [];
  for (const m of meshes) {
    const base = verts.length;
    for (let i = 0; i < m.positions.length; i += 3) {
      verts.push([m.positions[i], m.positions[i + 1], m.positions[i + 2]]);
      norms.push([m.normals[i], m.normals[i + 1], m.normals[i + 2]]);
    }
    for (let i = 0; i < m.indices.length; i += 3) {
      tris.push([m.indices[i] + base, m.indices[i + 1] + base, m.indices[i + 2] + base]);
    }
  }
  return { verts, norms, tris };
}

test('pared frontal: vértices en z=-1.5, normales hacia la cámara', () => {
  const fusion = new Fusion({ voxel: 0.04 });
  const { verts, norms, tris } = collect(scan(fusion, IDENT));
  assert.ok(tris.length > 1000, `pocos triángulos: ${tris.length}`);
  const used = new Set(tris.flat());
  let maxErr = 0;
  for (const i of used) {
    maxErr = Math.max(maxErr, Math.abs(verts[i][2] + 1.5));
    assert.ok(norms[i][2] > 0.9, `normal no apunta a +Z: ${norms[i]}`);
  }
  assert.ok(maxErr < 0.02, `error de posición ${maxErr} m`);
  // orientación de triángulos coherente con las normales
  for (const [a, b, c] of tris) {
    const e1 = verts[b].map((v, k) => v - verts[a][k]);
    const e2 = verts[c].map((v, k) => v - verts[a][k]);
    const fz = e1[0] * e2[1] - e1[1] * e2[0];
    assert.ok(fz >= 0, 'triángulo con orientación invertida');
  }
});

test('sin huecos en costuras entre bloques', () => {
  const fusion = new Fusion({ voxel: 0.04 });
  const { verts, tris } = collect(scan(fusion, IDENT));
  // soldar vértices duplicados entre bloques
  const weld = new Map();
  const id = (i) => {
    const k = verts[i].map((v) => Math.round(v * 1e5)).join(',');
    if (!weld.has(k)) weld.set(k, weld.size);
    return weld.get(k);
  };
  const edges = new Map();
  for (const t of tris) {
    const w = t.map(id);
    for (let e = 0; e < 3; e++) {
      const a = w[e], b = w[(e + 1) % 3];
      const k = a < b ? `${a}-${b}` : `${b}-${a}`;
      edges.set(k, (edges.get(k) || 0) + 1);
    }
  }
  const pts = [...weld.keys()].map((k) => k.split(',').map((v) => v / 1e5));
  const minX = Math.min(...pts.map((p) => p[0])), maxX = Math.max(...pts.map((p) => p[0]));
  const minY = Math.min(...pts.map((p) => p[1])), maxY = Math.max(...pts.map((p) => p[1]));
  const margin = 0.13;
  let interior = 0;
  for (const [k, count] of edges) {
    if (count !== 1) continue;
    const [a, b] = k.split('-').map(Number);
    const mx = (pts[a][0] + pts[b][0]) / 2, my = (pts[a][1] + pts[b][1]) / 2;
    const nearBorder = mx < minX + margin || mx > maxX - margin || my < minY + margin || my > maxY - margin;
    if (!nearBorder) interior++;
  }
  assert.equal(interior, 0, `${interior} aristas de borde en el interior (huecos)`);
});

test('cámara rotada 90° y trasladada: pared en x = tx - 1.5', () => {
  const fusion = new Fusion({ voxel: 0.04 });
  // rotación +90° en Y: el -Z de la cámara apunta a -X del mundo
  const { verts, tris } = collect(scan(fusion, viewMatrix(Math.PI / 2, 0.3, 1.2, -0.7)));
  const used = new Set(tris.flat());
  assert.ok(used.size > 500);
  for (const i of used) assert.ok(Math.abs(verts[i][0] - (0.3 - 1.5)) < 0.02, `x=${verts[i][0]}`);
});

test('rendimiento: integración 160x90 y mallado', () => {
  const fusion = new Fusion({ voxel: 0.04 });
  const t0 = performance.now();
  for (let i = 0; i < 20; i++) {
    fusion.integrate({
      depth: planeDepth(1.5 + 0.01 * i), width: W, height: H, rawToMeters: 0.001,
      normViewFromNormDepth: IDENT, projection: PROJ, viewMatrix: viewMatrix(i * 0.02),
    });
  }
  const tInt = (performance.now() - t0) / 20;
  const t1 = performance.now();
  fusion.extract(Infinity);
  const tMesh = performance.now() - t1;
  const st = fusion.stats();
  console.log(`integrate ${tInt.toFixed(2)} ms/frame, mesh completo ${tMesh.toFixed(1)} ms`, st);
  assert.ok(st.triangles > 0);
});
