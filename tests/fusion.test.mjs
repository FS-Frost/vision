// Tests del núcleo de fusión. Ejecutar: node --test tests/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Fusion, mul4, invertRigid, yawMatrix, depthWeight } from '../scan/fusion-core.js';
import { Collider } from '../scan/collider.js';
import { relocalize, RELOC_PARAMS, liveAgreement, startGuess } from '../scan/reloc.js';

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

// ---------------------------------------------------------------- escena sintética (habitación)

// Pose cámara -> mundo: traslación * Ry(yaw) * Rx(pitch)
function pose(yaw, pitch, x, y, z) {
  const cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
  return Float64Array.from([cy, 0, -sy, 0, sy * sp, cp, cy * sp, 0, sy * cp, -sp, cy * cp, 0, x, y, z, 1]);
}

// Habitación 3.5 x 4 m con una caja; profundidad plana por raycasting.
const BOX = { min: [0.2, 0, -1.4], max: [0.7, 0.6, -0.9] };
function roomDepth(M) {
  const depth = new Uint16Array(W * H);
  const o = [M[12], M[13], M[14]];
  for (let j = 0; j < H; j++) {
    for (let i = 0; i < W; i++) {
      const xc = (2 * ((i + 0.5) / W) - 1) / PROJ[0];
      const yc = (1 - 2 * ((j + 0.5) / H)) / PROJ[5];
      const D = [0, 1, 2].map((r) => M[r] * xc + M[4 + r] * yc - M[8 + r]);
      let t = Infinity;
      const plane = (axis, v) => {
        if (Math.abs(D[axis]) < 1e-9) return;
        const tt = (v - o[axis]) / D[axis];
        if (tt > 0.01 && tt < t) t = tt;
      };
      plane(1, 0); plane(1, 2.5); plane(0, -1.5); plane(0, 2); plane(2, -2.5); plane(2, 1.5);
      // caja (slabs)
      let t0 = 0.01, t1 = Infinity;
      for (let a = 0; a < 3; a++) {
        const inv = 1 / D[a];
        let ta = (BOX.min[a] - o[a]) * inv, tb = (BOX.max[a] - o[a]) * inv;
        if (ta > tb) [ta, tb] = [tb, ta];
        t0 = Math.max(t0, ta); t1 = Math.min(t1, tb);
      }
      if (t0 <= t1 && t0 < t) t = t0;
      depth[j * W + i] = Number.isFinite(t) && t < 60 ? Math.round(t * 1000) : 0;
    }
  }
  return depth;
}

function frameOf(depth, viewMatrix) {
  return { depth, width: W, height: H, rawToMeters: 0.001, normViewFromNormDepth: IDENT, projection: PROJ, viewMatrix };
}

function scanRoom(fusion) {
  for (let yaw = -1.4; yaw <= 1.41; yaw += 0.2) {
    for (const pitch of [-0.5, -0.15]) {
      const M = pose(yaw, pitch, 0, 1.3, 0);
      fusion.integrate(frameOf(roomDepth(M), M));
      fusion.integrate(frameOf(roomDepth(M), M));
    }
  }
}

test('volumen: exportar e importar reproduce la malla', () => {
  const a = new Fusion({ voxel: 0.04 });
  scanRoom(a);
  const ta = a.finalMeshes().totalTris;
  const b = new Fusion({ voxel: 0.04 });
  b.importVolume(a.exportVolume(), { maxWeight: 255 });
  const tb = b.finalMeshes().totalTris;
  assert.ok(ta > 1000);
  assert.ok(Math.abs(ta - tb) / ta < 0.02, `triángulos ${ta} vs ${tb}`);
});

test('alineación: recupera yaw y traslación de una segunda sesión', () => {
  const saved = new Fusion({ voxel: 0.04 });
  scanRoom(saved);
  const cont = new Fusion({ voxel: 0.04 });
  cont.importVolume(saved.exportVolume());

  // verdad: sesión -> mundo
  const A = yawMatrix(0.25, 0.15, 0.03, -0.2);
  const Ainv = invertRigid(A);
  const pts = [];
  for (const [yaw, pitch] of [[0.1, -0.35], [-0.3, -0.3], [0.5, -0.4], [-0.7, -0.2]]) {
    const Mw = pose(yaw, pitch, 0.05, 1.3, 0.1);          // pose real en el mundo
    const Ms = mul4(Ainv, Mw);                           // lo que reporta la nueva sesión
    pts.push(...cont.samplePoints(frameOf(roomDepth(Mw), Ms)));
  }
  const t0 = performance.now();
  const r = cont.alignPoints(Float32Array.from(pts));
  console.log(`alineación ${(performance.now() - t0).toFixed(0)} ms`, { yaw: r.yaw, t: r.t, ratio: r.inlierRatio, rms: r.rms });
  assert.ok(r.ok, `no alineó: ratio ${r.inlierRatio} rms ${r.rms}`);
  assert.ok(Math.abs(r.yaw - 0.25) < 0.02, `yaw ${r.yaw}`);
  assert.ok(Math.abs(r.t[0] - 0.15) < 0.03 && Math.abs(r.t[1] - 0.03) < 0.03 && Math.abs(r.t[2] + 0.2) < 0.03, `t ${r.t}`);

  // integrar con la alineación no debe crear una pared "fantasma" desplazada
  cont.setAlign(r.matrix);
  const before = cont.finalMeshes().totalTris;
  const Mw = pose(0, -0.3, 0.05, 1.3, 0.1);
  for (let k = 0; k < 3; k++) cont.integrate(frameOf(roomDepth(Mw), mul4(Ainv, Mw)));
  const after = cont.finalMeshes().totalTris;
  assert.ok(after < before * 1.1, `la malla creció demasiado (${before} -> ${after}): integración desalineada`);
});

test('alineación: rechaza datos que no corresponden a la escena guardada', () => {
  const saved = new Fusion({ voxel: 0.04 });
  scanRoom(saved);
  // nube aleatoria
  const pts = new Float32Array(3000);
  let seed = 1;
  const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
  for (let i = 0; i < pts.length; i++) pts[i] = rnd() * 3 - 1.5 + (i % 3 === 1 ? 1.2 : 0);
  const r = saved.alignPoints(pts);
  assert.equal(r.ok, false);
});

test('colisiones: no se atraviesan paredes y se desliza contra ellas', () => {
  const fusion = new Fusion({ voxel: 0.04 });
  scanRoom(fusion);
  const { meshes } = fusion.finalMeshes();
  const col = new Collider(meshes, 0);
  // la caja (0.2..0.7, -1.4..-0.9) llega a 0.6 m: obstáculo
  assert.equal(col.blocked(0.45, -1.15), true, 'la caja debe bloquear');
  assert.equal(col.blocked(0, 0), false, 'el centro debe estar libre');
  // caminar hacia la pared z=-2.5 desde el centro: debe detenerse antes
  let x = -0.6, z = 0;
  for (let k = 0; k < 400; k++) [x, z] = col.move(x, z, 0, -0.01);
  assert.ok(z > -2.5 + 0.1, `atravesó la pared: z=${z}`);
  // deslizamiento: empujando en diagonal contra la pared, x sigue avanzando
  const x0 = x;
  for (let k = 0; k < 50; k++) [x, z] = col.move(x, z, 0.01, -0.01);
  assert.ok(x > x0 + 0.3, 'debería deslizarse a lo largo de la pared');
  const [fx, fz] = col.nearestFree(0.45, -1.15);
  assert.equal(col.blocked(fx, fz), false);
});

test('alineación: refinar desde un candidato converge con una sola semilla', () => {
  const saved = new Fusion({ voxel: 0.04 });
  scanRoom(saved);
  const A = yawMatrix(-0.1, -0.08, 0.0, 0.12);
  const Ainv = invertRigid(A);
  const pts = [];
  for (const [yaw, pitch] of [[0.2, -0.35], [-0.4, -0.3], [0.6, -0.4]]) {
    const Mw = pose(yaw, pitch, 0, 1.3, 0);
    pts.push(...saved.samplePoints(frameOf(roomDepth(Mw), mul4(Ainv, Mw))));
  }
  // candidato con error de ~3 cm y ~1.5°
  const r = saved.alignPoints(Float32Array.from(pts), {
    yaws: [0], initial: [-0.075, -0.05, 0.01, 0.1], levels: [0.2, 0.12, 0.08, 0.05], minRatio: 0.35,
  });
  assert.ok(r.ok);
  assert.ok(Math.abs(r.yaw + 0.1) < 0.015, `yaw ${r.yaw}`);
  assert.ok(Math.hypot(r.t[0] + 0.08, r.t[1], r.t[2] - 0.12) < 0.03, `t ${r.t}`);
});

test('continuar: lo ya escaneado no cambia aunque la alineación tenga 5 cm de error', () => {
  const saved = new Fusion({ voxel: 0.03 });
  scanRoom(saved);
  const vol = saved.exportVolume();
  const key = (p, i) => `${Math.round(p[i] * 1e4)},${Math.round(p[i + 1] * 1e4)},${Math.round(p[i + 2] * 1e4)}`;
  const ref = new Fusion({ voxel: 0.03 });
  ref.importVolume(vol);
  const origSet = new Set();
  for (const m of ref.finalMeshes().meshes) for (let i = 0; i < m.positions.length; i += 3) origSet.add(key(m.positions, i));

  const run = (opts) => {
    const cont = new Fusion({ voxel: 0.03 });
    cont.importVolume(vol, opts);
    const Atrue = yawMatrix(0.1, 0.05, 0, -0.1);
    cont.setAlign(yawMatrix(0.1, 0.10, 0, -0.1)); // estimación con 5 cm de error en x
    const Ainv = invertRigid(Atrue);
    for (const [yaw, pitch] of [[0, -0.3], [0.4, -0.35], [-0.4, -0.3]]) {
      const Mw = pose(yaw, pitch, 0, 1.3, 0);
      for (let k = 0; k < 4; k++) cont.integrate(frameOf(roomDepth(Mw), mul4(Ainv, Mw)));
    }
    const after = new Set();
    for (const m of cont.finalMeshes().meshes) for (let i = 0; i < m.positions.length; i += 3) after.add(key(m.positions, i));
    let kept = 0;
    for (const k of origSet) if (after.has(k)) kept++;
    return kept / origSet.size;
  };
  const frozen = run({ freeze: true });
  const mixed = run({ freeze: false, maxWeight: 10 }); // comportamiento anterior (control)
  console.log(`vértices originales intactos: congelado ${(frozen * 100).toFixed(1)}%, mezclado ${(mixed * 100).toFixed(1)}%`);
  assert.ok(frozen > 0.97, `congelado debería conservar el original: ${frozen}`);
  assert.ok(mixed < frozen - 0.1, `el control debería alterar el original: ${mixed}`);
});

// ---------------------------------------------------------------- relocalización global
// Estos tests miden el ALGORITMO de búsqueda con un sensor ideal (profundidad perfecta a
// cualquier distancia), así que desactivan el corte/peso por distancia de la captura real.
const IDEAL = { maxDepth: 4, nearFull: 4 };

function relocTrial(saved, savedCloud, A, startX, startZ) {
  // sesión nueva: barrido de 360° desde (startX, startZ); la sesión reporta poses en su propio marco
  const Ainv = invertRigid(A);
  const sess = new Fusion({ voxel: 0.05, minWeight: 1, ...IDEAL });
  const frames = [];
  for (let yaw = 0; yaw < 2 * Math.PI; yaw += Math.PI / 8) {
    for (const pitch of [-0.35, 0]) {
      const Mw = pose(yaw, pitch, startX, 1.3, startZ);
      const fr = frameOf(roomDepth(Mw), mul4(Ainv, Mw));
      sess.integrate(fr);
      frames.push(fr);
    }
  }
  const sc = sess.surfaceCloud();
  return relocalize(savedCloud, sc, {
    refine: (init) => saved.alignPoints(sc.pos, { yaws: [0], initial: init, levels: [0.25, 0.15, 0.08, 0.05], itersPer: 4, minRatio: 0 }),
    score: (yaw, t) => saved.scorePose(sc.pos, sc.nor, yaw, t, RELOC_PARAMS),
    frames: frames.filter((_, i) => i % 2 === 0),
  });
}

test('relocalización global: 20 inicios al azar (cualquier punto y giro)', () => {
  const saved = new Fusion({ voxel: 0.03, ...IDEAL });
  scanRoom(saved);
  const savedCloud = saved.surfaceCloud();
  let seed = 7;
  const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
  let ok = 0, top3 = 0;
  const fails = [];
  const t0 = performance.now();
  for (let k = 0; k < 20; k++) {
    // punto libre al azar dentro de la habitación (lejos de paredes y de la caja)
    let x, z;
    do { x = -1.1 + rnd() * 2.7; z = -2.1 + rnd() * 3.2; } while (x > 0 && x < 0.9 && z > -1.7 && z < -0.6);
    const yawTrue = rnd() * 2 * Math.PI - Math.PI;
    const A = yawMatrix(yawTrue, (rnd() - 0.5) * 2, (rnd() - 0.5) * 0.3, (rnd() - 0.5) * 2);
    const r = relocTrial(saved, savedCloud, A, x, z);
    const good = (c) => c && Math.abs(Math.atan2(Math.sin(c.yaw - yawTrue), Math.cos(c.yaw - yawTrue))) < 1 * Math.PI / 180 &&
      Math.hypot(c.t[0] - A[12], c.t[1] - A[13], c.t[2] - A[14]) < 0.03;
    if (r.ok && good(r.candidates[0])) ok++;
    else fails.push({ k, best: r.candidates[0] && { q: +r.candidates[0].quality.toFixed(2), yaw: +r.candidates[0].yaw.toFixed(2) }, yawTrue: +yawTrue.toFixed(2) });
    if (r.candidates.slice(0, 3).some(good)) top3++;
  }
  const ms = (performance.now() - t0) / 20;
  console.log(`relocalización: ${ok}/20 primer candidato correcto, ${top3}/20 entre los 3 primeros, ${ms.toFixed(0)} ms por intento`, fails.length ? fails : '');
  assert.ok(ok >= 18, `solo ${ok}/20 correctos`);
});

test('relocalización: datos de otra habitación no dan un candidato válido', () => {
  const saved = new Fusion({ voxel: 0.03, ...IDEAL });
  scanRoom(saved);
  // "otra habitación": una sola pared lejana y suelo, sin la geometría de la guardada
  const other = new Fusion({ voxel: 0.05, minWeight: 1, ...IDEAL });
  for (let yaw = -0.6; yaw <= 0.6; yaw += 0.2) {
    const M = pose(yaw, -0.3, 0, 1.3, 0);
    other.integrate(frameOf(planeDepth(2.2), M));
  }
  const sc = other.surfaceCloud();
  const r = relocalize(saved.surfaceCloud(), sc, {
    refine: (init) => saved.alignPoints(sc.pos, { yaws: [0], initial: init, levels: [0.25, 0.15, 0.08, 0.05], itersPer: 4, minRatio: 0 }),
    score: (yaw, t) => saved.scorePose(sc.pos, sc.nor, yaw, t, RELOC_PARAMS),
  });
  // una pared plana encaja en cualquier pared: debe marcarse ambiguo o no válido, nunca un acierto "claro"
  assert.ok(!r.ok || r.ambiguous, `aceptó sin ambigüedad: ${JSON.stringify(r.candidates[0] && { q: r.candidates[0].quality })}`);
});

test('coincidencia en vivo: la pose correcta coincide más que la girada 180°', () => {
  const saved = new Fusion({ voxel: 0.03, ...IDEAL });
  scanRoom(saved);
  const cloud = saved.surfaceCloud();
  const A = yawMatrix(0.7, 0.4, 0, -0.3);                  // verdad: sesión -> guardado
  const wrong = mul4(yawMatrix(Math.PI, 0.25, 0, -1.15), A); // misma habitación girada 180°
  const Ainv = invertRigid(A);
  let good = 0, bad = 0;
  for (const yaw of [-0.6, 0, 0.6]) {
    const Mw = pose(yaw, -0.3, 0.1, 1.3, -0.2);
    const frame = frameOf(roomDepth(Mw), mul4(Ainv, Mw));
    good += liveAgreement(cloud, frame, A).ratio;
    bad += liveAgreement(cloud, frame, wrong).ratio;
  }
  good /= 3; bad /= 3;
  console.log(`coincidencia en vivo: correcta ${(good * 100).toFixed(0)}%, girada 180° ${(bad * 100).toFixed(0)}%`);
  assert.ok(good > 0.8, `la correcta debería coincidir: ${good}`);
  assert.ok(bad < good - 0.3, `la girada debería coincidir claramente menos: ${bad}`);
});

test('captura: el peso de la profundidad baja con la distancia y se corta a 3 m', () => {
  assert.equal(depthWeight(0.8), 1);
  assert.equal(depthWeight(1.5), 1);
  assert.ok(Math.abs(depthWeight(2.25) - 0.5) < 1e-9);
  assert.equal(depthWeight(3.0), 0);
  assert.equal(depthWeight(3.5), 0);
  // una pared a 3.5 m no se integra; a 1.5 m sí
  const far = new Fusion({ voxel: 0.04 });
  for (let k = 0; k < 4; k++) far.integrate({ depth: planeDepth(3.5), width: W, height: H, rawToMeters: 0.001, normViewFromNormDepth: IDENT, projection: PROJ, viewMatrix: IDENT });
  assert.equal(far.stats().blocks, 0);
  const near = new Fusion({ voxel: 0.04 });
  for (let k = 0; k < 4; k++) near.integrate({ depth: planeDepth(1.5), width: W, height: H, rawToMeters: 0.001, normViewFromNormDepth: IDENT, projection: PROJ, viewMatrix: IDENT });
  assert.ok(near.stats().blocks > 0);
});

test('pista de inicio: startGuess recupera la transformación y su candidato siempre aparece', () => {
  const saved = new Fusion({ voxel: 0.03, ...IDEAL });
  scanRoom(saved);
  const A = yawMatrix(-1.9, 0.8, 0.05, -0.6);          // verdad: sesión -> guardado
  const Ainv = invertRigid(A);
  // el escaneo guardado empezó aquí (marco guardado); la sesión nueva empieza en el mismo sitio
  const P0 = pose(0.15, -0.2, 0, 1.3, 0);
  const S0 = mul4(Ainv, P0);
  const g = startGuess(P0, S0);
  assert.ok(Math.abs(Math.atan2(Math.sin(g.yaw + 1.9), Math.cos(g.yaw + 1.9))) < 1e-6, `yaw ${g.yaw}`);
  assert.ok(Math.hypot(g.tx - 0.8, g.ty - 0.05, g.tz + 0.6) < 1e-6);
  // barrido corto (poca cobertura): aun así el candidato del inicio está y es correcto
  const sess = new Fusion({ voxel: 0.05, minWeight: 1, ...IDEAL });
  for (const yaw of [0.15, 0.35, -0.05]) {
    const Mw = pose(yaw, -0.3, 0, 1.3, 0);
    sess.integrate(frameOf(roomDepth(Mw), mul4(Ainv, Mw)));
  }
  const sc = sess.surfaceCloud();
  // pista aproximada: el usuario se puso ~10 cm y ~5° fuera del punto inicial
  const approx = { ...g, yaw: g.yaw + 0.09, tx: g.tx + 0.07, tz: g.tz - 0.07 };
  const r = relocalize(saved.surfaceCloud(), sc, {
    refine: (init) => saved.alignPoints(sc.pos, { yaws: [0], initial: init, levels: [0.25, 0.15, 0.08, 0.05], itersPer: 4, minRatio: 0 }),
    score: (yaw, tt) => saved.scorePose(sc.pos, sc.nor, yaw, tt, RELOC_PARAMS),
    extraSeeds: [approx],
  });
  const start = r.candidates.find((c) => c.tag === 'inicio');
  assert.ok(start, 'falta el candidato del punto inicial');
  const dyaw = Math.abs(Math.atan2(Math.sin(start.yaw + 1.9), Math.cos(start.yaw + 1.9)));
  assert.ok(dyaw < 0.02 && Math.hypot(start.t[0] - 0.8, start.t[2] + 0.6) < 0.04, `candidato inicio: yaw ${start.yaw} t ${start.t}`);
});
