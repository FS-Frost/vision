// Tests del núcleo de fusión. Ejecutar: node --test tests/*.test.mjs
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

function planeDepth(dMeters, hole = null, patch = null) {
  const depth = new Uint16Array(W * H);
  for (let j = 0; j < H; j++) {
    for (let i = 0; i < W; i++) {
      let d = dMeters;
      if (patch && i >= patch.x && i < patch.x + patch.size && j >= patch.y && j < patch.y + patch.size) d = patch.d;
      if (hole && i >= hole.x && i < hole.x + hole.size && j >= hole.y && j < hole.y + hole.size) d = 0;
      depth[j * W + i] = Math.round(d * 1000);
    }
  }
  return depth;
}

function scan(fusion, view, { frames = 5, d = 1.5, depth = null, color = null } = {}) {
  for (let i = 0; i < frames; i++) {
    fusion.integrate({
      depth: depth ?? planeDepth(d), width: W, height: H, rawToMeters: 0.001,
      normViewFromNormDepth: IDENT, projection: PROJ, viewMatrix: view,
      ...(color || {}),
    });
  }
  let meshes = [];
  for (let guard = 0; guard < 100; guard++) {
    const r = fusion.extract(Infinity);
    meshes = meshes.concat(r.meshes);
    if (r.pending === 0) break;
  }
  return [...new Map(meshes.map((m) => [m.key, m])).values()];
}

function collect(meshes) {
  const verts = [], norms = [], cols = [], tris = [];
  for (const m of meshes) {
    const base = verts.length;
    for (let i = 0; i < m.positions.length; i += 3) {
      verts.push([m.positions[i], m.positions[i + 1], m.positions[i + 2]]);
      norms.push([m.normals[i], m.normals[i + 1], m.normals[i + 2]]);
      cols.push([m.colors[i], m.colors[i + 1], m.colors[i + 2]]);
    }
    for (let i = 0; i < m.indices.length; i += 3) {
      tris.push([m.indices[i] + base, m.indices[i + 1] + base, m.indices[i + 2] + base]);
    }
  }
  return { verts, norms, cols, tris };
}

function interiorBoundaryEdges({ verts, tris }, margin = 0.13) {
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
  let interior = 0;
  for (const [k, count] of edges) {
    if (count !== 1) continue;
    const [a, b] = k.split('-').map(Number);
    const mx = (pts[a][0] + pts[b][0]) / 2, my = (pts[a][1] + pts[b][1]) / 2;
    if (!(mx < minX + margin || mx > maxX - margin || my < minY + margin || my > maxY - margin)) interior++;
  }
  return interior;
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
  for (const [a, b, c] of tris) {
    const e1 = verts[b].map((v, k) => v - verts[a][k]);
    const e2 = verts[c].map((v, k) => v - verts[a][k]);
    assert.ok(e1[0] * e2[1] - e1[1] * e2[0] >= 0, 'triángulo con orientación invertida');
  }
});

test('sin huecos en costuras entre bloques (con y sin dilatación)', () => {
  for (const dilate of [true, false]) {
    for (const voxel of [0.03, 0.04]) {
      const n = interiorBoundaryEdges(collect(scan(new Fusion({ voxel, dilate }), IDENT)));
      assert.equal(n, 0, `dilate=${dilate} voxel=${voxel}: ${n} aristas de borde interiores`);
    }
  }
});

test('cámara rotada 90° y trasladada: pared en x = tx - 1.5', () => {
  const fusion = new Fusion({ voxel: 0.04 });
  const { verts, tris } = collect(scan(fusion, viewMatrix(Math.PI / 2, 0.3, 1.2, -0.7)));
  const used = new Set(tris.flat());
  assert.ok(used.size > 500);
  for (const i of used) assert.ok(Math.abs(verts[i][0] - (0.3 - 1.5)) < 0.02, `x=${verts[i][0]}`);
});

test('color: mitad superior roja, inferior verde (fila 0 = abajo)', () => {
  const cw = 40, ch = 80;
  const color = new Uint8Array(cw * ch * 4);
  for (let r = 0; r < ch; r++) {
    for (let c = 0; c < cw; c++) {
      const o = (r * cw + c) * 4;
      const top = r >= ch / 2; // fila 0 = parte inferior de la vista
      color[o] = top ? 255 : 0; color[o + 1] = top ? 0 : 255; color[o + 2] = 0; color[o + 3] = 255;
    }
  }
  const fusion = new Fusion({ voxel: 0.04 });
  const { verts, cols, tris } = collect(scan(fusion, IDENT, { color: { color, colorWidth: cw, colorHeight: ch } }));
  const used = new Set(tris.flat());
  let checked = 0;
  for (const i of used) {
    const y = verts[i][1];
    if (Math.abs(y) < 0.1) continue; // zona de transición
    const [r, g] = cols[i];
    if (y > 0) assert.ok(r > 0.9 && g < 0.1, `arriba debería ser rojo: ${cols[i]}`);
    else assert.ok(g > 0.9 && r < 0.1, `abajo debería ser verde: ${cols[i]}`);
    checked++;
  }
  assert.ok(checked > 300);
});

test('finalMeshes elimina fragmentos sueltos y conserva la pared', () => {
  const depth = planeDepth(1.5, null, { x: 20, y: 20, size: 12, d: 0.8 });
  const fusion = new Fusion({ voxel: 0.04 });
  const before = collect(scan(fusion, IDENT, { depth }));
  const near = (c) => [...new Set(c.tris.flat())].filter((i) => c.verts[i][2] > -1.2).length;
  assert.ok(near(before) > 0, 'el fragmento cercano debería existir antes de limpiar');
  const { meshes, removedTris } = fusion.finalMeshes();
  const after = collect(meshes);
  assert.equal(near(after), 0, 'el fragmento debería eliminarse');
  assert.ok(removedTris > 0);
  assert.ok(after.tris.length > 1000, 'la pared debe conservarse');
});

test('dilatación no reduce la cobertura con un hueco en la profundidad', () => {
  const depth = planeDepth(1.5, { x: 70, y: 40, size: 4 });
  const a = collect(scan(new Fusion({ voxel: 0.04, dilate: false }), IDENT, { depth })).tris.length;
  const b = collect(scan(new Fusion({ voxel: 0.04, dilate: true }), IDENT, { depth })).tris.length;
  assert.ok(b >= a, `con dilatación ${b} < sin dilatación ${a}`);
});

test('rendimiento: integración 160x90 y mallado', () => {
  for (const voxel of [0.04, 0.03]) {
    const fusion = new Fusion({ voxel });
    const t0 = performance.now();
    for (let i = 0; i < 20; i++) {
      fusion.integrate({
        depth: planeDepth(1.5 + 0.01 * i), width: W, height: H, rawToMeters: 0.001,
        normViewFromNormDepth: IDENT, projection: PROJ, viewMatrix: viewMatrix(i * 0.02),
      });
    }
    const tInt = (performance.now() - t0) / 20;
    const t1 = performance.now();
    const { totalTris } = fusion.finalMeshes();
    const tMesh = performance.now() - t1;
    console.log(`voxel ${voxel}: integrate ${tInt.toFixed(2)} ms/frame, malla final ${tMesh.toFixed(1)} ms, ${totalTris} tri, ${fusion.stats().memoryMB} MB`);
    assert.ok(totalTris > 0);
  }
});
