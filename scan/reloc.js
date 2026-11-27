// Relocalización global: encuentra dónde está la sesión actual dentro de un escaneo guardado
// buscando en toda la habitación (posición en el suelo + giro; la gravedad +Y es común).
// Búsqueda gruesa-a-fina 3D (campo de proximidad de la superficie guardada) -> ICP 3D sobre las
// mejores semillas -> orden por calidad y visibilidad. La decisión final la toma el usuario
// (confirmación visual): aquí solo se ordenan candidatos.
// Sin dependencias ni DOM: funciones puras sobre nubes { pos, nor, n } (testeable en Node).

const DEG = Math.PI / 180;

/**
 * Parámetros ajustados con datos reales del moto g54: entre sesiones, la profundidad de ARCore
 * discrepa ±10–15 cm a 1,5 m, así que las tolerancias de centímetros rechazaban hasta la pose
 * correcta. minQuality solo descarta datos sin relación con la escena: el resto lo decide el
 * usuario viendo la malla sobre la cámara.
 */
/** Altura mínima de la cámara sobre el suelo con el teléfono en la mano (m). */
export const MIN_CAM_ABOVE_FLOOR = 0.8;

export const RELOC_PARAMS = { resTol: 0.05, cosTol: 0.5, visMargin: 0.25, visWeight: 2, minQuality: 0.03, maxShown: 5 };

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

// ---------------------------------------------------------------- búsqueda 3D

/** Campo de proximidad 3D: cada punto guardado salpica una gaussiana (máximo) en una grilla. */
function scoreVolume(cloud, cell, sigma, pad) {
  let mn = [Infinity, Infinity, Infinity], mx = [-Infinity, -Infinity, -Infinity];
  for (let i = 0; i < cloud.n; i++) for (let a = 0; a < 3; a++) {
    mn[a] = Math.min(mn[a], cloud.pos[i * 3 + a]); mx[a] = Math.max(mx[a], cloud.pos[i * 3 + a]);
  }
  const o = mn.map((v) => v - pad);
  const dim = [0, 1, 2].map((a) => Math.ceil((mx[a] - mn[a] + 2 * pad) / cell) + 1);
  const data = new Float32Array(dim[0] * dim[1] * dim[2]);
  const r = Math.ceil((2.5 * sigma) / cell);
  const k2 = 1 / (2 * sigma * sigma);
  for (let i = 0; i < cloud.n; i++) {
    const p = [cloud.pos[i * 3], cloud.pos[i * 3 + 1], cloud.pos[i * 3 + 2]];
    const g = [0, 1, 2].map((a) => Math.floor((p[a] - o[a]) / cell));
    for (let dz = -r; dz <= r; dz++) {
      const z = g[2] + dz;
      if (z < 0 || z >= dim[2]) continue;
      for (let dy = -r; dy <= r; dy++) {
        const y = g[1] + dy;
        if (y < 0 || y >= dim[1]) continue;
        for (let dx = -r; dx <= r; dx++) {
          const x = g[0] + dx;
          if (x < 0 || x >= dim[0]) continue;
          const ex = (x + 0.5) * cell + o[0] - p[0], ey = (y + 0.5) * cell + o[1] - p[1], ez = (z + 0.5) * cell + o[2] - p[2];
          const v = Math.exp(-(ex * ex + ey * ey + ez * ez) * k2);
          const idx = (z * dim[1] + y) * dim[0] + x;
          if (v > data[idx]) data[idx] = v;
        }
      }
    }
  }
  return { o, dim, cell, data, mn, mx };
}

function scorePose3D(vol, q, c, s, tx, ty, tz) {
  const { o, dim, cell, data } = vol;
  let sum = 0;
  for (let i = 0; i < q.length; i += 3) {
    const x = c * q[i] + s * q[i + 2] + tx, y = q[i + 1] + ty, z = -s * q[i] + c * q[i + 2] + tz;
    const gx = Math.floor((x - o[0]) / cell), gy = Math.floor((y - o[1]) / cell), gz = Math.floor((z - o[2]) / cell);
    if (gx >= 0 && gy >= 0 && gz >= 0 && gx < dim[0] && gy < dim[1] && gz < dim[2]) sum += data[(gz * dim[1] + gy) * dim[0] + gx];
  }
  return sum;
}

function subsample3(cloud, max) {
  const step = Math.max(1, cloud.n / max), n = Math.min(cloud.n, max), out = new Float32Array(n * 3);
  for (let k = 0; k < n; k++) {
    const i = Math.floor(k * step) * 3;
    out[k * 3] = cloud.pos[i]; out[k * 3 + 1] = cloud.pos[i + 1]; out[k * 3 + 2] = cloud.pos[i + 2];
  }
  return out;
}

/**
 * Búsqueda gruesa-a-fina 3D de (yaw, tx, tz) con la altura fijada por los suelos (dy).
 * Usa la forma completa (alturas de muebles, etc.), no solo su sombra en el suelo.
 * @returns {Array<{yaw, tx, ty, tz, score}>}
 */
export function searchVolume(savedCloud, sessionCloud, dy, opts = {}) {
  const maxCandidates = opts.maxCandidates ?? 8;
  if (savedCloud.n < 100 || sessionCloud.n < 100) return [];
  const coarse = scoreVolume(savedCloud, 0.1, 0.15, 0.4);
  const qc = subsample3(sessionCloud, 200);
  const step = 0.2, yawStep = (opts.yawStepDeg ?? 5) * DEG;
  const all = [];
  for (let yaw = 0; yaw < 2 * Math.PI - 1e-9; yaw += yawStep) {
    const c = Math.cos(yaw), s = Math.sin(yaw);
    const best = [];
    for (let tx = coarse.mn[0] - 0.4; tx <= coarse.mx[0] + 0.4; tx += step) {
      for (let tz = coarse.mn[2] - 0.4; tz <= coarse.mx[2] + 0.4; tz += step) {
        const sc = scorePose3D(coarse, qc, c, s, tx, dy, tz);
        if (best.length < 3 || sc > best[best.length - 1].score) {
          best.push({ yaw, tx, tz, score: sc });
          best.sort((a, b) => b.score - a.score);
          if (best.length > 3) best.pop();
        }
      }
    }
    all.push(...best);
  }
  all.sort((a, b) => b.score - a.score);
  const seeds = nms(all, 0.5, 20 * DEG, maxCandidates + 2);

  // fino: ±0.2 m cada 5 cm, ±6° cada 1.5°, altura ±6 cm
  const fine = scoreVolume(savedCloud, 0.05, 0.07, 0.2);
  const qf = subsample3(sessionCloud, 400);
  const refined = seeds.map((sd) => {
    let best = { ...sd, ty: dy, score: -1 };
    for (let da = -6; da <= 6; da += 1.5) {
      const yaw = sd.yaw + da * DEG, c = Math.cos(yaw), s = Math.sin(yaw);
      for (const ddy of [-0.06, 0, 0.06]) {
        for (let dx = -0.2; dx <= 0.2001; dx += 0.05) {
          for (let dz = -0.2; dz <= 0.2001; dz += 0.05) {
            const sc = scorePose3D(fine, qf, c, s, sd.tx + dx, dy + ddy, sd.tz + dz);
            if (sc > best.score) best = { yaw, tx: sd.tx + dx, ty: dy + ddy, tz: sd.tz + dz, score: sc };
          }
        }
      }
    }
    best.score /= qf.length / 3;
    return best;
  });
  refined.sort((a, b) => b.score - a.score);
  return nms(refined, 0.3, 8 * DEG, maxCandidates);
}

/**
 * Suposición "la sesión empezó en el punto inicial del escaneo guardado": dadas la pose de la
 * cámara al empezar el escaneo (marco guardado) y al empezar la sesión (marco de la sesión),
 * devuelve la transformación sesión -> guardado reducida a giro (yaw) + traslación.
 */
export function startGuess(savedStartPose, sessionStartPose) {
  const A = mul(savedStartPose, invRigid(sessionStartPose));
  return { yaw: Math.atan2(A[8], A[0]), tx: A[12], ty: A[13], tz: A[14], tag: 'inicio' };
}

function mul(a, b) {
  const o = new Float64Array(16);
  for (let c = 0; c < 4; c++) for (let r = 0; r < 4; r++) {
    o[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
  }
  return o;
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
  let violations = 0, support = 0, checked = 0;
  forEachProjected(savedCloud, frames, matrix, maxPoints, (dist, measured) => {
    checked++;
    if (measured > dist + margin) violations++;
    else if (Math.abs(measured - dist) <= margin) support++;
  });
  return { violations, support, checked, rate: violations / Math.max(1, violations + support) };
}

/**
 * Coincidencia en vivo de un candidato con UN frame actual (confirmación visual):
 * - match: la cámara mide la superficie guardada donde debe (± tol)
 * - through: la cámara ve a través de ella (mide más lejos)
 * - occluded: algo más cercano la tapa (no cuenta)
 * @returns {{ match, through, occluded, ratio }} ratio = match / (match + through)
 */
export function liveAgreement(savedCloud, frame, matrix, { tol = 0.15, maxPoints = 2500 } = {}) {
  let match = 0, through = 0, occluded = 0;
  forEachProjected(savedCloud, [frame], matrix, maxPoints, (dist, measured) => {
    if (Math.abs(measured - dist) <= tol) match++;
    else if (measured > dist) through++;
    else occluded++;
  });
  return { match, through, occluded, ratio: match / Math.max(1, match + through) };
}

/** Proyecta la superficie guardada (con la pose `matrix`) en cada frame y llama cb(dist, medida). */
function forEachProjected(savedCloud, frames, matrix, maxPoints, cb) {
  const Ainv = invRigid(matrix); // guardado -> sesión
  const step = Math.max(1, Math.floor(savedCloud.n / maxPoints));
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
      cb(dist, measured);
    }
  }
}

/**
 * Relocalización completa: búsqueda 2D + ICP 3D (con `refine`) + puntuación (con `score`).
 * `refine(initial[yaw,tx,ty,tz]) -> {yaw, t, matrix}` y `score(yaw, t) -> calidad [0,1]`
 * los aporta la Fusion guardada (así este módulo no depende de fusion-core).
 * @returns {{ ok, ambiguous, candidates: Array<{yaw, t, matrix, quality, planScore}>, reason? }}
 */
export function relocalize(savedCloud, sessionCloud, {
  refine, score, frames = null, sessionFloorFallback = null, method = 'volume', extraSeeds = [],
  // alturas de cámara (y) conocidas: un "suelo" estimado desde la malla solo es creíble si queda
  // al menos MIN_CAM_ABOVE_FLOOR por debajo de la cámara (si no, es una cama o una mesa)
  savedCamY = null, sessionCamY = null, savedFloorFallback = null, floorOverride = null,
  minQuality = RELOC_PARAMS.minQuality, visWeight = RELOC_PARAMS.visWeight, visMargin = RELOC_PARAMS.visMargin,
} = {}) {
  const plausible = (floor, camY, fallback) =>
    camY == null || camY - floor >= MIN_CAM_ABOVE_FLOOR || fallback == null ? floor : fallback;
  const savedFloor = floorOverride?.saved ?? plausible(estimateFloor(savedCloud, savedFloorFallback), savedCamY, savedFloorFallback);
  const sessionFloor = floorOverride?.session ?? plausible(estimateFloor(sessionCloud, sessionFloorFallback), sessionCamY, sessionFloorFallback);
  const dy = savedFloor - sessionFloor;
  let coarse;
  if (method === 'plan') {
    const sp = floorPlan(savedCloud, savedFloor), qp = floorPlan(sessionCloud, sessionFloor);
    if (qp.length / 2 < 20) return { ok: false, ambiguous: false, candidates: [], reason: 'pocas paredes o muebles a la vista' };
    coarse = searchFloorPlan(sp, qp).map((c) => ({ ...c, ty: dy }));
  } else {
    if (sessionCloud.n < 100) return { ok: false, ambiguous: false, candidates: [], reason: 'poca geometría a la vista' };
    coarse = searchVolume(savedCloud, sessionCloud, dy);
  }
  const out = [];
  // semillas extra (p. ej. "estás en el punto inicial del escaneo"): siempre se refinan e incluyen
  const seeds = [...extraSeeds.map((e) => ({ ...e, score: 0, extra: e.tag ?? true })), ...coarse];
  for (const c of seeds) {
    const r = refine([c.yaw, c.tx, c.ty, c.tz]);
    const quality = score(r.yaw, r.t);
    const dup = out.find((o) => Math.hypot(o.t[0] - r.t[0], o.t[2] - r.t[2]) < 0.1 && angDiff(o.yaw, r.yaw) < 3 * DEG);
    if (dup) {
      if (quality > dup.quality) Object.assign(dup, { ...r, quality, planScore: c.score });
      if (c.extra) dup.tag = c.extra;
      continue;
    }
    out.push({ ...r, quality, planScore: c.score, ...(c.extra ? { tag: c.extra } : {}) });
  }
  // orden final: calidad penalizada por "ver a través" de superficies guardadas
  for (const c of out) {
    c.visibility = frames?.length ? visibilityCheck(savedCloud, frames, c.matrix, { margin: visMargin }).rate : 0;
    c.rank = c.quality - visWeight * c.visibility;
  }
  out.sort((a, b) => b.rank - a.rank);
  const best = out[0], second = out[1];
  const ambiguous = !!(best && second && second.rank >= 0.9 * best.rank &&
    (Math.hypot(second.t[0] - best.t[0], second.t[2] - best.t[2]) > 0.3 || angDiff(second.yaw, best.yaw) > 15 * DEG));
  return {
    ok: !!best && best.quality >= minQuality, // el rank solo ordena; decide el usuario
    ambiguous, candidates: out, savedFloor, sessionFloor,
    reason: best ? (best.quality < minQuality ? 'coincidencia insuficiente' : undefined) : 'sin candidatos',
  };
}
