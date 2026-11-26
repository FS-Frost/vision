// Colisiones del paseo: grilla 2D de ocupación (vista cenital) construida con los
// vértices de la malla que caen en la franja de altura del cuerpo, inflada con el
// radio del jugador para que la prueba de colisión sea un punto. Sin DOM: testeable en Node.

export class Collider {
  /**
   * @param {Array<{positions: Float32Array}>} meshes
   * @param {number} floorY
   * @param {{cell?: number, radius?: number, minH?: number, maxH?: number}} opts
   *   minH: altura sobre el suelo desde la que algo es obstáculo (se pueden "pisar" cosas más bajas)
   */
  constructor(meshes, floorY, { cell = 0.05, radius = 0.2, minH = 0.3, maxH = 1.8 } = {}) {
    this.cell = cell;
    let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
    const y0 = floorY + minH, y1 = floorY + maxH;
    for (const m of meshes) {
      const p = m.positions;
      for (let i = 0; i < p.length; i += 3) {
        if (p[i + 1] < y0 || p[i + 1] > y1) continue;
        if (p[i] < minX) minX = p[i]; if (p[i] > maxX) maxX = p[i];
        if (p[i + 2] < minZ) minZ = p[i + 2]; if (p[i + 2] > maxZ) maxZ = p[i + 2];
      }
    }
    this.empty = !Number.isFinite(minX);
    if (this.empty) return;
    const pad = radius + cell;
    this.x0 = minX - pad; this.z0 = minZ - pad;
    this.w = Math.ceil((maxX - minX + 2 * pad) / cell) + 1;
    this.h = Math.ceil((maxZ - minZ + 2 * pad) / cell) + 1;
    const raw = new Uint8Array(this.w * this.h);
    for (const m of meshes) {
      const p = m.positions;
      for (let i = 0; i < p.length; i += 3) {
        if (p[i + 1] < y0 || p[i + 1] > y1) continue;
        raw[this._idx(p[i], p[i + 2])] = 1;
      }
    }
    // inflar con un disco de radio `radius`
    const r = Math.ceil(radius / cell);
    const disk = [];
    for (let dz = -r; dz <= r; dz++) for (let dx = -r; dx <= r; dx++) if (dx * dx + dz * dz <= r * r) disk.push([dx, dz]);
    this.grid = new Uint8Array(this.w * this.h);
    for (let z = 0; z < this.h; z++) {
      for (let x = 0; x < this.w; x++) {
        if (!raw[z * this.w + x]) continue;
        for (const [dx, dz] of disk) {
          const nx = x + dx, nz = z + dz;
          if (nx >= 0 && nx < this.w && nz >= 0 && nz < this.h) this.grid[nz * this.w + nx] = 1;
        }
      }
    }
  }

  _idx(x, z) {
    return Math.floor((z - this.z0) / this.cell) * this.w + Math.floor((x - this.x0) / this.cell);
  }

  /** true si un jugador centrado en (x, z) chocaría. Fuera de la zona escaneada: libre. */
  blocked(x, z) {
    if (this.empty) return false;
    const gx = Math.floor((x - this.x0) / this.cell), gz = Math.floor((z - this.z0) / this.cell);
    if (gx < 0 || gz < 0 || gx >= this.w || gz >= this.h) return false;
    return this.grid[gz * this.w + gx] === 1;
  }

  /**
   * Mueve de (x, z) a (x+dx, z+dz) deslizando contra obstáculos. Si ya está dentro
   * de un obstáculo, permite moverse para poder salir.
   */
  move(x, z, dx, dz) {
    if (this.blocked(x, z) || !this.blocked(x + dx, z + dz)) return [x + dx, z + dz];
    if (!this.blocked(x + dx, z)) return [x + dx, z];
    if (!this.blocked(x, z + dz)) return [x, z + dz];
    return [x, z];
  }

  /** Celda libre más cercana (búsqueda en anillos), para no empezar dentro de un mueble. */
  nearestFree(x, z, maxDist = 3) {
    if (!this.blocked(x, z)) return [x, z];
    const steps = Math.ceil(maxDist / this.cell);
    for (let r = 1; r <= steps; r++) {
      for (let k = -r; k <= r; k++) {
        for (const [dx, dz] of [[k, -r], [k, r], [-r, k], [r, k]]) {
          const nx = x + dx * this.cell, nz = z + dz * this.cell;
          if (!this.blocked(nx, nz)) return [nx, nz];
        }
      }
    }
    return [x, z];
  }
}
