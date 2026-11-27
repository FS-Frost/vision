package org.frost.vision.core;

import java.util.List;

/**
 * Colisiones del paseo (portado de scan/collider.js): grilla 2D de ocupación con los vértices en
 * la franja de altura del cuerpo, inflada con el radio del jugador; la prueba es un punto.
 */
public final class Collider {
  final float cell;
  final boolean empty;
  float x0, z0;
  int w, h;
  boolean[] grid;

  public Collider(List<MeshPart> meshes, float floorY) { this(meshes, floorY, 0.05f, 0.2f, 0.3f, 1.8f); }

  public Collider(List<MeshPart> meshes, float floorY, float cell, float radius, float minH, float maxH) {
    this.cell = cell;
    float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
    float y0 = floorY + minH, y1 = floorY + maxH;
    for (MeshPart m : meshes) {
      float[] p = m.positions;
      for (int i = 0; i < p.length; i += 3) {
        if (p[i + 1] < y0 || p[i + 1] > y1) continue;
        minX = Math.min(minX, p[i]); maxX = Math.max(maxX, p[i]);
        minZ = Math.min(minZ, p[i + 2]); maxZ = Math.max(maxZ, p[i + 2]);
      }
    }
    empty = minX == Float.MAX_VALUE;
    if (empty) return;
    float pad = radius + cell;
    x0 = minX - pad; z0 = minZ - pad;
    w = (int) Math.ceil((maxX - minX + 2 * pad) / cell) + 1;
    h = (int) Math.ceil((maxZ - minZ + 2 * pad) / cell) + 1;
    boolean[] raw = new boolean[w * h];
    for (MeshPart m : meshes) {
      float[] p = m.positions;
      for (int i = 0; i < p.length; i += 3) {
        if (p[i + 1] < y0 || p[i + 1] > y1) continue;
        raw[(int) Math.floor((p[i + 2] - z0) / cell) * w + (int) Math.floor((p[i] - x0) / cell)] = true;
      }
    }
    int r = (int) Math.ceil(radius / cell);
    grid = new boolean[w * h];
    for (int z = 0; z < h; z++) for (int x = 0; x < w; x++) {
      if (!raw[z * w + x]) continue;
      for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
        if (dx * dx + dz * dz > r * r) continue;
        int nx = x + dx, nz = z + dz;
        if (nx >= 0 && nx < w && nz >= 0 && nz < h) grid[nz * w + nx] = true;
      }
    }
  }

  public boolean blocked(float x, float z) {
    if (empty) return false;
    int gx = (int) Math.floor((x - x0) / cell), gz = (int) Math.floor((z - z0) / cell);
    if (gx < 0 || gz < 0 || gx >= w || gz >= h) return false;
    return grid[gz * w + gx];
  }

  /** Mueve deslizando contra obstáculos; si ya está dentro de uno, deja salir. */
  public float[] move(float x, float z, float dx, float dz) {
    if (blocked(x, z) || !blocked(x + dx, z + dz)) return new float[] {x + dx, z + dz};
    if (!blocked(x + dx, z)) return new float[] {x + dx, z};
    if (!blocked(x, z + dz)) return new float[] {x, z + dz};
    return new float[] {x, z};
  }

  public float[] nearestFree(float x, float z, float maxDist) {
    if (!blocked(x, z)) return new float[] {x, z};
    int steps = (int) Math.ceil(maxDist / cell);
    for (int r = 1; r <= steps; r++) {
      for (int k = -r; k <= r; k++) {
        int[][] cand = {{k, -r}, {k, r}, {-r, k}, {r, k}};
        for (int[] d : cand) {
          float nx = x + d[0] * cell, nz = z + d[1] * cell;
          if (!blocked(nx, nz)) return new float[] {nx, nz};
        }
      }
    }
    return new float[] {x, z};
  }
}
