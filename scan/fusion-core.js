// Núcleo de fusión TSDF dispersa + extracción de malla (Surface Nets) por bloques.
// Sin dependencias ni APIs del DOM: lo usan el Web Worker y los tests en Node.
//
// Convenciones:
// - Voxel (ix, iy, iz) cubre [i*voxel, (i+1)*voxel); su muestra representa el centro.
// - TSDF normalizado a [-1, 1]: positivo = espacio libre (delante de la superficie),
//   negativo = detrás. Peso 0 = desconocido.
// - Bloques de 8³ voxels en un Map con clave numérica. Las mallas se agrupan en
//   "superceldas" de 4³ bloques para limitar las draw calls en el hilo principal.

export const BLOCK = 8;
export const SUPER = 4;
const BLOCK3 = BLOCK * BLOCK * BLOCK;
const OFF = 512;
const SPAN = 1024; // coordenadas de bloque válidas: [-512, 511]

export const keyOf = (x, y, z) => ((x + OFF) * SPAN + (y + OFF)) * SPAN + (z + OFF);

export function coordsOf(key) {
  const z = (key % SPAN) - OFF;
  const r = Math.floor(key / SPAN);
  return [Math.floor(r / SPAN) - OFF, (r % SPAN) - OFF, z];
}

// Para cada voxel local: máscara de 27 bits con los bloques (propio + vecinos) cuyo
// mallado lee ese voxel. Un bloque muestrea su rango local -1..8, así que un voxel
// en la cara 0 afecta al vecino -1 y uno en la cara 7 al vecino +1.
const NEIGHBOR_MASK = new Int32Array(BLOCK3);
for (let li = 0; li < BLOCK3; li++) {
  const lx = li & 7, ly = (li >> 3) & 7, lz = li >> 6;
  let m = 0;
  for (let dx = lx === 0 ? -1 : 0; dx <= (lx === 7 ? 1 : 0); dx++) {
    for (let dy = ly === 0 ? -1 : 0; dy <= (ly === 7 ? 1 : 0); dy++) {
      for (let dz = lz === 0 ? -1 : 0; dz <= (lz === 7 ? 1 : 0); dz++) {
        m |= 1 << ((dx + 1) * 9 + (dy + 1) * 3 + (dz + 1));
      }
    }
  }
  NEIGHBOR_MASK[li] = m;
}

// Aristas del cubo: esquina c -> (x = c&1, y = (c>>1)&1, z = c>>2)
const EDGE_A = [], EDGE_B = [];
for (let c = 0; c < 8; c++) {
  for (const bit of [1, 2, 4]) {
    if (!(c & bit)) { EDGE_A.push(c); EDGE_B.push(c | bit); }
  }
}

const S = 10;      // muestras por eje al mallar un bloque (local -1..8)
const C = 9;       // cubos por eje (local -1..7)
const sIdx = (x, y, z) => x + S * (y + S * z);
const cIdx = (x, y, z) => x + C * (y + C * z);

const now = () => (globalThis.performance ? performance.now() : Date.now());

export class Fusion {
  constructor(opts = {}) {
    this.opts = { ...opts };
    this.voxel = opts.voxel ?? 0.04;
    this.trunc = opts.truncation ?? this.voxel * 3;
    this.minDepth = opts.minDepth ?? 0.2;
    this.maxDepth = opts.maxDepth ?? 4.0;
    this.maxWeight = opts.maxWeight ?? 32;
    this.stride = opts.stride ?? 1;
    this.edgeJump = opts.edgeJump ?? 0.08; // salto relativo de profundidad que marca un borde
    this.blocks = new Map();     // key -> { key, t: Float32Array, w: Float32Array, mask }
    this.touched = [];           // bloques modificados desde la última extracción
    this.pending = new Set();    // bloques por re-mallar
    this.blockMeshes = new Map();
    this.frames = 0;

    this._T = new Float32Array(S * S * S);
    this._W = new Float32Array(S * S * S);
    this._V = new Int32Array(C * C * C);
    this._val = new Float32Array(8);
    this._pos = new Float32Array(C * C * C * 3);
    this._nor = new Float32Array(C * C * C * 3);
    this._idx = new Uint32Array(3 * BLOCK3 * 6);
  }

  /**
   * Integra un mapa de profundidad.
   * @param {object} f
   * @param {Uint16Array|Float32Array} f.depth   valores crudos, fila mayor
   * @param {number} f.width
   * @param {number} f.height
   * @param {number} f.rawToMeters
   * @param {ArrayLike<number>} f.normViewFromNormDepth  4x4 column-major (inversa de normDepthBufferFromNormView)
   * @param {ArrayLike<number>} f.projection             4x4 column-major de la vista
   * @param {ArrayLike<number>} f.viewMatrix             4x4 column-major, cámara -> mundo
   */
  integrate(f) {
    const { depth, width, height, rawToMeters: k } = f;
    const N = f.normViewFromNormDepth, P = f.projection, M = f.viewMatrix;
    const V = this.voxel, invV = 1 / V, trunc = this.trunc;
    const K = Math.ceil(trunc / V);
    const ox = M[12], oy = M[13], oz = M[14];
    const P0 = P[0], P5 = P[5], P8 = P[8], P9 = P[9];
    const minD = this.minDepth, maxD = this.maxDepth, jump = this.edgeJump, st = this.stride;
    const maxW = this.maxWeight;
    const blocks = this.blocks;
    let used = 0, updates = 0;

    for (let j = 0; j < height; j += st) {
      for (let i = 0; i < width; i += st) {
        const id = j * width + i;
        const d = depth[id] * k;
        if (!(d >= minD && d <= maxD)) continue;
        // "flying pixels": descarta píxeles en discontinuidades de profundidad
        if (i + 1 < width && Math.abs(depth[id + 1] * k - d) > jump * d) continue;
        if (j + 1 < height && Math.abs(depth[id + width] * k - d) > jump * d) continue;

        // píxel del buffer de profundidad -> coords normalizadas de la vista (origen arriba-izq.)
        const ud = (i + 0.5) / width, vd = (j + 0.5) / height;
        const u = N[0] * ud + N[4] * vd + N[12];
        const v = N[1] * ud + N[5] * vd + N[13];
        const xn = 2 * u - 1, yn = 1 - 2 * v;
        // profundidad plana -> espacio de cámara (mira hacia -Z)
        const xc = (d * (xn + P8)) / P0;
        const yc = (d * (yn + P9)) / P5;
        const zc = -d;
        // cámara -> mundo
        const px = M[0] * xc + M[4] * yc + M[8] * zc + ox;
        const py = M[1] * xc + M[5] * yc + M[9] * zc + oy;
        const pz = M[2] * xc + M[6] * yc + M[10] * zc + oz;
        let rx = px - ox, ry = py - oy, rz = pz - oz;
        const dist = Math.hypot(rx, ry, rz);
        rx /= dist; ry /= dist; rz /= dist;
        used++;

        for (let n = -K; n <= K; n++) {
          const s = n * V;
          const t = dist + s;
          const ix = Math.floor((ox + rx * t) * invV);
          const iy = Math.floor((oy + ry * t) * invV);
          const iz = Math.floor((oz + rz * t) * invV);
          const bkey = keyOf(ix >> 3, iy >> 3, iz >> 3);
          let b = blocks.get(bkey);
          if (!b) {
            b = { key: bkey, t: new Float32Array(BLOCK3), w: new Float32Array(BLOCK3), mask: 0 };
            blocks.set(bkey, b);
          }
          const li = (ix & 7) | ((iy & 7) << 3) | ((iz & 7) << 6);
          let tsdf = -s / trunc;
          if (tsdf > 1) tsdf = 1; else if (tsdf < -1) tsdf = -1;
          const w0 = b.w[li], w1 = w0 + 1;
          b.t[li] = (b.t[li] * w0 + tsdf) / w1;
          b.w[li] = w1 < maxW ? w1 : maxW;
          if (b.mask === 0) this.touched.push(b);
          b.mask |= NEIGHBOR_MASK[li];
          updates++;
        }
      }
    }
    this.frames++;
    return { used, updates };
  }

  /**
   * Re-malla los bloques afectados (con presupuesto de tiempo) y devuelve las
   * superceldas cambiadas, cada una como geometría indexada fusionada.
   */
  extract(budgetMs = 25) {
    for (const b of this.touched) {
      const m = b.mask;
      b.mask = 0;
      const [bx, by, bz] = coordsOf(b.key);
      for (let bit = 0; bit < 27; bit++) {
        if (!(m & (1 << bit))) continue;
        const nk = keyOf(bx + Math.floor(bit / 9) - 1, by + (Math.floor(bit / 3) % 3) - 1, bz + (bit % 3) - 1);
        if (this.blocks.has(nk)) this.pending.add(nk);
      }
    }
    this.touched.length = 0;

    const t0 = now();
    const supers = new Set();
    let meshed = 0;
    for (const key of this.pending) {
      this.pending.delete(key);
      const mesh = this._meshBlock(key);
      if (mesh) this.blockMeshes.set(key, mesh);
      else this.blockMeshes.delete(key);
      const [bx, by, bz] = coordsOf(key);
      supers.add(keyOf(bx >> 2, by >> 2, bz >> 2));
      meshed++;
      if (now() - t0 > budgetMs) break;
    }
    const meshes = [];
    for (const sk of supers) meshes.push(this._mergeSuper(sk));
    return { meshes, meshed, pending: this.pending.size };
  }

  stats() {
    let vertices = 0, triangles = 0;
    for (const m of this.blockMeshes.values()) {
      vertices += m.positions.length / 3;
      triangles += m.indices.length / 3;
    }
    return {
      frames: this.frames,
      blocks: this.blocks.size,
      meshedBlocks: this.blockMeshes.size,
      pending: this.pending.size + this.touched.length,
      vertices, triangles,
      memoryMB: +((this.blocks.size * BLOCK3 * 8) / 1e6).toFixed(1),
    };
  }

  _mergeSuper(sk) {
    const [sx, sy, sz] = coordsOf(sk);
    const parts = [];
    let nv = 0, ni = 0;
    for (let x = 0; x < SUPER; x++) {
      for (let y = 0; y < SUPER; y++) {
        for (let z = 0; z < SUPER; z++) {
          const m = this.blockMeshes.get(keyOf(sx * SUPER + x, sy * SUPER + y, sz * SUPER + z));
          if (m) { parts.push(m); nv += m.positions.length / 3; ni += m.indices.length; }
        }
      }
    }
    const positions = new Float32Array(nv * 3);
    const normals = new Float32Array(nv * 3);
    const indices = new Uint32Array(ni);
    let vo = 0, io = 0;
    for (const m of parts) {
      positions.set(m.positions, vo * 3);
      normals.set(m.normals, vo * 3);
      for (let i = 0; i < m.indices.length; i++) indices[io + i] = m.indices[i] + vo;
      vo += m.positions.length / 3;
      io += m.indices.length;
    }
    return { key: sk, positions, normals, indices };
  }

  _meshBlock(key) {
    const [bx, by, bz] = coordsOf(key);
    const T = this._T, W = this._W, Vx = this._V, val = this._val;
    const pos = this._pos, nor = this._nor, idx = this._idx;
    const vs = this.voxel;
    const gx0 = bx * BLOCK - 1, gy0 = by * BLOCK - 1, gz0 = bz * BLOCK - 1;

    // 1. Muestras locales -1..8 (incluye una capa de cada vecino)
    let cacheKey = -1, cb = null;
    for (let z = 0; z < S; z++) {
      const gz = gz0 + z;
      for (let y = 0; y < S; y++) {
        const gy = gy0 + y;
        for (let x = 0; x < S; x++) {
          const gx = gx0 + x;
          const k = keyOf(gx >> 3, gy >> 3, gz >> 3);
          if (k !== cacheKey) { cacheKey = k; cb = this.blocks.get(k); }
          const s = sIdx(x, y, z);
          if (cb) {
            const li = (gx & 7) | ((gy & 7) << 3) | ((gz & 7) << 6);
            T[s] = cb.t[li]; W[s] = cb.w[li];
          } else {
            T[s] = 0; W[s] = 0;
          }
        }
      }
    }

    // 2. Un vértice por cubo con cambio de signo (media de los cruces en sus aristas)
    Vx.fill(-1);
    let nv = 0;
    for (let cz = 0; cz < C; cz++) {
      for (let cy = 0; cy < C; cy++) {
        for (let cx = 0; cx < C; cx++) {
          let mask = 0, ok = true;
          for (let c = 0; c < 8; c++) {
            const s = sIdx(cx + (c & 1), cy + ((c >> 1) & 1), cz + (c >> 2));
            if (W[s] <= 0) { ok = false; break; }
            const t = T[s];
            val[c] = t;
            if (t < 0) mask |= 1 << c;
          }
          if (!ok || mask === 0 || mask === 255) continue;
          let sx = 0, sy = 0, sz = 0, n = 0;
          for (let e = 0; e < 12; e++) {
            const a = EDGE_A[e], b = EDGE_B[e];
            const va = val[a], vb = val[b];
            if ((va < 0) === (vb < 0)) continue;
            const f = va / (va - vb);
            const ax = a & 1, ay = (a >> 1) & 1, az = a >> 2;
            sx += ax + f * ((b & 1) - ax);
            sy += ay + f * (((b >> 1) & 1) - ay);
            sz += az + f * ((b >> 2) - az);
            n++;
          }
          const o = nv * 3;
          pos[o] = (gx0 + cx + sx / n + 0.5) * vs;
          pos[o + 1] = (gy0 + cy + sy / n + 0.5) * vs;
          pos[o + 2] = (gz0 + cz + sz / n + 0.5) * vs;
          // normal = gradiente del TSDF (apunta al espacio libre)
          const gx = (val[1] + val[3] + val[5] + val[7]) - (val[0] + val[2] + val[4] + val[6]);
          const gy = (val[2] + val[3] + val[6] + val[7]) - (val[0] + val[1] + val[4] + val[5]);
          const gz = (val[4] + val[5] + val[6] + val[7]) - (val[0] + val[1] + val[2] + val[3]);
          const len = Math.hypot(gx, gy, gz) || 1;
          nor[o] = gx / len; nor[o + 1] = gy / len; nor[o + 2] = gz / len;
          Vx[cIdx(cx, cy, cz)] = nv++;
        }
      }
    }
    if (nv === 0) return null;

    // 3. Un quad por arista de la grilla con cambio de signo. Cada bloque es dueño de las
    //    aristas que nacen en sus muestras locales 0..7 (índice de muestra 1..8): sin costuras.
    let ni = 0;
    const quad = (a, b, c, d) => {
      if (a < 0 || b < 0 || c < 0 || d < 0) return;
      // orientar el quad según las normales de sus vértices
      const a3 = a * 3, b3 = b * 3, c3 = c * 3, d3 = d * 3;
      const e1x = pos[b3] - pos[a3], e1y = pos[b3 + 1] - pos[a3 + 1], e1z = pos[b3 + 2] - pos[a3 + 2];
      const e2x = pos[c3] - pos[a3], e2y = pos[c3 + 1] - pos[a3 + 1], e2z = pos[c3 + 2] - pos[a3 + 2];
      const fx = e1y * e2z - e1z * e2y, fy = e1z * e2x - e1x * e2z, fz = e1x * e2y - e1y * e2x;
      const nx = nor[a3] + nor[b3] + nor[c3] + nor[d3];
      const ny = nor[a3 + 1] + nor[b3 + 1] + nor[c3 + 1] + nor[d3 + 1];
      const nz = nor[a3 + 2] + nor[b3 + 2] + nor[c3 + 2] + nor[d3 + 2];
      if (fx * nx + fy * ny + fz * nz >= 0) {
        idx[ni++] = a; idx[ni++] = b; idx[ni++] = c;
        idx[ni++] = a; idx[ni++] = c; idx[ni++] = d;
      } else {
        idx[ni++] = a; idx[ni++] = c; idx[ni++] = b;
        idx[ni++] = a; idx[ni++] = d; idx[ni++] = c;
      }
    };
    for (let z = 1; z <= BLOCK; z++) {
      for (let y = 1; y <= BLOCK; y++) {
        for (let x = 1; x <= BLOCK; x++) {
          const s = sIdx(x, y, z);
          if (W[s] <= 0) continue;
          const neg = T[s] < 0;
          let s1 = s + 1; // eje X: cubos (x, y-1|y, z-1|z)
          if (W[s1] > 0 && (T[s1] < 0) !== neg) {
            quad(Vx[cIdx(x, y - 1, z - 1)], Vx[cIdx(x, y, z - 1)], Vx[cIdx(x, y, z)], Vx[cIdx(x, y - 1, z)]);
          }
          s1 = s + S; // eje Y: cubos (x-1|x, y, z-1|z)
          if (W[s1] > 0 && (T[s1] < 0) !== neg) {
            quad(Vx[cIdx(x - 1, y, z - 1)], Vx[cIdx(x, y, z - 1)], Vx[cIdx(x, y, z)], Vx[cIdx(x - 1, y, z)]);
          }
          s1 = s + S * S; // eje Z: cubos (x-1|x, y-1|y, z)
          if (W[s1] > 0 && (T[s1] < 0) !== neg) {
            quad(Vx[cIdx(x - 1, y - 1, z)], Vx[cIdx(x, y - 1, z)], Vx[cIdx(x, y, z)], Vx[cIdx(x - 1, y, z)]);
          }
        }
      }
    }
    if (ni === 0) return null;
    return {
      positions: pos.slice(0, nv * 3),
      normals: nor.slice(0, nv * 3),
      indices: idx.slice(0, ni),
    };
  }
}
