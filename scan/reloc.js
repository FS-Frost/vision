// Relocalización global: encuentra dónde está la sesión actual dentro de un escaneo guardado
// buscando en toda la habitación (posición en el suelo + giro).
// Ambos marcos comparten la gravedad (+Y), así que la búsqueda gruesa es 2D: se comparan las
// "plantas" (paredes y muebles vistos desde arriba) y luego el ICP 3D refina los candidatos.
// Sin dependencias ni DOM: funciones puras sobre nubes { pos, nor, n } (testeable en Node).

const DEG = Math.PI / 180;

/** Altura del suelo: percentil bajo de los puntos con normal hacia arriba. */
export function estimateFloor(cloud, fallback = null) {
  const ys = [];
  for (let i = 0; i < cloud.n; i++) if (cloud.nor[i * 3 + 1] > 0.85) ys.push(cloud.pos[i * 3 + 1]);
  if (ys.length >= 30) {
    ys.sort((a, b) => a - b);
    return ys[Math.floor(ys.length * 0.05)];
  }
  if (fallback !== null) return fallback;
  let m = Infinity;
  for (let i = 0; i < cloud.n; i++) m = Math.min(m, cloud.pos[i * 3 + 1]);
  return m;
}

/**
 * Planta: celdas 2D (x, z) ocupadas por superficies verticales (paredes, lados de muebles)
 * en la franja de altura [minH, maxH] sobre el suelo. Una entrada por celda, sin pesos:
 * así una pared alta no pesa más que un mueble bajo.
 */
export function floorPlan(cloud, floor, { cell = 0.1, minH = 0.25, maxH = 2.0 } = {}) {
  const seen = new Set();
  const out = [];
  for (let i = 0; i < cloud.n; i++) {
    if (Math.abs(cloud.nor[i * 3 + 1]) > 0.6) continue;
    const h = cloud.pos[i * 3 + 1] - floor;
    if (h < minH || h > maxH) continue;
    const cx = Math.floor(cloud.pos[i * 3] / cell), cz = Math.floor(cloud.pos[i * 3 + 2] / cell);
    const k = cx * 100003 + cz;
    if (seen.has(k)) continue;
    seen.add(k);
    out.push((cx + 0.5) * cell, (cz + 0.5) * cell);
  }
  return Float32Array.from(out);
}

/** Grilla de puntuación: cada punto de la planta guardada "salpica" una gaussiana (máximo). */
function scoreGrid(pts, cell, sigma, pad) {
  let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
  for (let i = 0; i < pts.length; i += 2) {
    minX = Math.min(minX, pts[i]); maxX = Math.max(maxX, pts[i]);
    minZ = Math.min(minZ, pts[i + 1]); maxZ = Math.max(maxZ, pts[i + 1]);
  }
  const x0 = minX - pad, z0 = minZ - pad;
  const w = Math.ceil((maxX - minX + 2 * pad) / cell) + 1, h = Math.ceil((maxZ - minZ + 2 * pad) / cell) + 1;
  const data = new Float32Array(w * h);
  const r = Math.ceil((2.5 * sigma) / cell);
  for (let i = 0; i < pts.length; i += 2) {
    const gx = Math.floor((pts[i] - x0) / cell), gz = Math.floor((pts[i + 1] - z0) / cell);
    for (let dz = -r; dz <= r; dz++) {
      const z = gz + dz;
      if (z < 0 || z >= h) continue;
      for (let dx = -r; dx <= r; dx++) {
        const x = gx + dx;
        if (x < 0 || x >= w) continue;
        const ex = (x + 0.5) * cell + x0 - pts[i], ez = (z + 0.5) * cell + z0 - pts[i + 1];
        const v = Math.exp(-(ex * ex + ez * ez) / (2 * sigma * sigma));
        if (v > data[z * w + x]) data[z * w + x] = v;
      }
    }
  }
  return { x0, z0, w, h, cell, data, bounds: [minX, maxX, minZ, maxZ] };
}

function scorePose2D(grid, q, c, s, tx, tz) {
  let sum = 0;
  const { x0, z0, w, h, cell, data } = grid;
  for (let i = 0; i < q.length; i += 2) {
    // misma convención que yawMatrix: x' = c x + s z ; z' = -s x + c z
    const x = c * q[i] + s * q[i + 1] + tx, z = -s * q[i] + c * q[i + 1] + tz;
    const gx = Math.floor((x - x0) / cell), gz = Math.floor((z - z0) / cell);
    if (gx >= 0 && gz >= 0 && gx < w && gz < h) sum += data[gz * w + gx];
  }
  return sum;
}

function subsample(pts, max) {
  const n = pts.length / 2;
  if (n <= max) return pts;
  const step = n / max, out = new Float32Array(max * 2);
  for (let k = 0; k < max; k++) {
    const i = Math.floor(k * step) * 2;
    out[k * 2] = pts[i]; out[k * 2 + 1] = pts[i + 1];
  }
  return out;
}

const angDiff = (a, b) => Math.abs(Math.atan2(Math.sin(a - b), Math.cos(a - b)));

function nms(list, minDist, minAng, max) {
  const kept = [];
  for (const c of list) {
    if (kept.some((k) => Math.hypot(k.tx - c.tx, k.tz - c.tz) < minDist && angDiff(k.yaw, c.yaw) < minAng)) continue;
    kept.push(c);
    if (kept.length >= max) break;
  }
  return kept;
}

/**
 * Búsqueda gruesa-a-fina de (yaw, tx, tz) que lleva la planta de la sesión sobre la guardada.
 * @returns {Array<{yaw, tx, tz, score}>} candidatos ordenados (score en [0, 1])
 */
export function searchFloorPlan(savedPlan, sessionPlan, opts = {}) {
  const maxCandidates = opts.maxCandidates ?? 8;
  if (savedPlan.length < 40 || sessionPlan.length < 40) return [];

  // 1. grueso: giros cada 5°, traslaciones cada 20 cm sobre toda la planta guardada
  const coarse = scoreGrid(savedPlan, 0.1, 0.2, 0.6);
  const qc = subsample(sessionPlan, 150);
  const [minX, maxX, minZ, maxZ] = coarse.bounds;
  const step = 0.2, yawStep = (opts.yawStepDeg ?? 5) * DEG;
  const perYaw = [];
  for (let yaw = 0; yaw < 2 * Math.PI - 1e-9; yaw += yawStep) {
    const c = Math.cos(yaw), s = Math.sin(yaw);
    let best = [];
    for (let tx = minX - 0.4; tx <= maxX + 0.4; tx += step) {
      for (let tz = minZ - 0.4; tz <= maxZ + 0.4; tz += step) {
        const sc = scorePose2D(coarse, qc, c, s, tx, tz);
        if (best.length < 3 || sc > best[best.length - 1].score) {
          best.push({ yaw, tx, tz, score: sc });
          best.sort((a, b) => b.score - a.score);
          if (best.length > 3) best.pop();
        }
      }
    }
    perYaw.push(...best);
  }
  perYaw.sort((a, b) => b.score - a.score);
  const seeds = nms(perYaw, 0.6, 20 * DEG, maxCandidates * 2);

  // 2. fino: alrededor de cada semilla, ±0.2 m cada 5 cm y ±6° cada 1.5°
  const fine = scoreGrid(savedPlan, 0.05, 0.07, 0.3);
  const qf = subsample(sessionPlan, 400);
  const refined = seeds.map((sd) => {
    let best = { ...sd, score: -1 };
    for (let dy = -6; dy <= 6; dy += 1.5) {
      const yaw = sd.yaw + dy * DEG, c = Math.cos(yaw), s = Math.sin(yaw);
      for (let dx = -0.2; dx <= 0.2001; dx += 0.05) {
        for (let dz = -0.2; dz <= 0.2001; dz += 0.05) {
          const sc = scorePose2D(fine, qf, c, s, sd.tx + dx, sd.tz + dz);
          if (sc > best.score) best = { yaw, tx: sd.tx + dx, tz: sd.tz + dz, score: sc };
        }
      }
    }
    best.score /= qf.length / 2;
    return best;
  });
  refined.sort((a, b) => b.score - a.score);
  return nms(refined, 0.3, 8 * DEG, maxCandidates);
}

function invRigid(m) {
  const o = new Float64Array(16);
  o[0] = m[0]; o[1] = m[4]; o[2] = m[8];
  o[4] = m[1]; o[5] = m[5]; o[6] = m[9];
  o[8] = m[2]; o[9] = m[6]; o[10] = m[10];
  o[12] = -(o[0] * m[12] + o[4] * m[13] + o[8] * m[14]);
  o[13] = -(o[1] * m[12] + o[5] * m[13] + o[9] * m[14]);
  o[14] = -(o[2] * m[12] + o[6] * m[13] + o[10] * m[14]);
  o[15] = 1;
  return o;
}

/**
 * Prueba de visibilidad: con la pose candidata `matrix` (sesión -> guardado), proyecta la
 * superficie guardada en los frames de la sesión. Si la cámara mide MÁS profundidad que la
 * superficie guardada (+ margen), "ve a través" de algo que debería taparle la vista: la pose
 * es incoherente. Discrimina simetrías (p. ej. la habitación girada 180°) que la planta no ve.
 * @param frames [{ depth, width, height, rawToMeters, normViewFromNormDepth, projection, viewMatrix }]
 * @returns {{ violations, support, checked, rate }} rate = violations / (violations + support)
 */
export function visibilityCheck(savedCloud, frames, matrix, { maxPoints = 2500, margin = 0.1 } = {}) {
  const Ainv = invRigid(matrix); // guardado -> sesión
  const step = Math.max(1, Math.floor(savedCloud.n / maxPoints));
  let violations = 0, support = 0, checked = 0;
  for (const f of frames) {
    const C = invRigid(f.viewMatrix); // sesión -> cámara
    const P = f.projection, N = f.normViewFromNormDepth;
    // inversa 2D de N (vista normalizada -> buffer de profundidad normalizado)
    const det = N[0] * N[5] - N[4] * N[1];
    if (Math.abs(det) < 1e-9) continue;
    const a = N[5] / det, b = -N[4] / det, c = -N[1] / det, d = N[0] / det;
    const W = f.width, H = f.height, k = f.rawToMeters;
    for (let i = 0; i < savedCloud.n; i += step) {
      const x = savedCloud.pos[i * 3], y = savedCloud.pos[i * 3 + 1], z = savedCloud.pos[i * 3 + 2];
      const sx = Ainv[0] * x + Ainv[4] * y + Ainv[8] * z + Ainv[12];
      const sy = Ainv[1] * x + Ainv[5] * y + Ainv[9] * z + Ainv[13];
      const sz = Ainv[2] * x + Ainv[6] * y + Ainv[10] * z + Ainv[14];
      const cx = C[0] * sx + C[4] * sy + C[8] * sz + C[12];
      const cy = C[1] * sx + C[5] * sy + C[9] * sz + C[13];
      const cz = C[2] * sx + C[6] * sy + C[10] * sz + C[14];
      const dist = -cz;
      if (dist < 0.3 || dist > 3.5) continue;
      const xn = (P[0] * cx) / dist - P[8], yn = (P[5] * cy) / dist - P[9];
      if (xn < -1 || xn > 1 || yn < -1 || yn > 1) continue;
      const u = (xn + 1) / 2 - N[12], v = (1 - yn) / 2 - N[13];
      const ud = a * u + b * v, vd = c * u + d * v;
      const pi = Math.floor(ud * W), pj = Math.floor(vd * H);
      if (pi < 0 || pj < 0 || pi >= W || pj >= H) continue;
      const measured = f.depth[pj * W + pi] * k;
      if (!(measured > 0)) continue;
      checked++;
      if (measured > dist + margin) violations++;
      else if (Math.abs(measured - dist) <= margin) support++;
    }
  }
  return { violations, support, checked, rate: violations / Math.max(1, violations + support) };
}

/**
 * Relocalización completa: búsqueda 2D + ICP 3D (con `refine`) + puntuación (con `score`).
 * `refine(initial[yaw,tx,ty,tz]) -> {yaw, t, matrix}` y `score(yaw, t) -> calidad [0,1]`
 * los aporta la Fusion guardada (así este módulo no depende de fusion-core).
 * @returns {{ ok, ambiguous, candidates: Array<{yaw, t, matrix, quality, planScore}>, reason? }}
 */
export function relocalize(savedCloud, sessionCloud, { refine, score, frames = null, sessionFloorFallback = null, minQuality = 0.2, visWeight = 5 } = {}) {
  const savedFloor = estimateFloor(savedCloud);
  const sessionFloor = estimateFloor(sessionCloud, sessionFloorFallback);
  const sp = floorPlan(savedCloud, savedFloor), qp = floorPlan(sessionCloud, sessionFloor);
  if (qp.length / 2 < 20) return { ok: false, ambiguous: false, candidates: [], reason: 'pocas paredes o muebles a la vista' };
  const coarse = searchFloorPlan(sp, qp);
  const dy = savedFloor - sessionFloor;
  const out = [];
  for (const c of coarse) {
    const r = refine([c.yaw, c.tx, dy, c.tz]);
    const quality = score(r.yaw, r.t);
    const dup = out.find((o) => Math.hypot(o.t[0] - r.t[0], o.t[2] - r.t[2]) < 0.1 && angDiff(o.yaw, r.yaw) < 3 * DEG);
    if (dup) { if (quality > dup.quality) Object.assign(dup, { ...r, quality, planScore: c.score }); continue; }
    out.push({ ...r, quality, planScore: c.score });
  }
  // orden final: calidad penalizada por "ver a través" de superficies guardadas
  for (const c of out) {
    c.visibility = frames?.length ? visibilityCheck(savedCloud, frames, c.matrix).rate : 0;
    c.rank = c.quality - visWeight * c.visibility;
  }
  out.sort((a, b) => b.rank - a.rank);
  const best = out[0], second = out[1];
  const ambiguous = !!(best && second && second.rank >= 0.9 * best.rank &&
    (Math.hypot(second.t[0] - best.t[0], second.t[2] - best.t[2]) > 0.3 || angDiff(second.yaw, best.yaw) > 15 * DEG));
  return {
    ok: !!best && best.quality >= minQuality && best.rank > 0,
    ambiguous, candidates: out, savedFloor, sessionFloor,
    reason: best ? (best.quality < minQuality ? 'coincidencia insuficiente' : undefined) : 'sin candidatos',
  };
}
