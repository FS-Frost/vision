package org.frost.vision.core;

import java.util.HashMap;

/** Nube de puntos con normales (superficie submuestreada) y grillas de búsqueda cacheadas. */
public final class Cloud {
  public final float[] pos, nor;
  public final int n;
  final HashMap<Float, HashMap<Long, int[]>> grids = new HashMap<>();

  public Cloud(float[] pos, float[] nor, int n) {
    this.pos = pos; this.nor = nor; this.n = n;
  }

  static long gridKey(int x, int y, int z) {
    return ((long) (x + 50000) * 100000L + (y + 50000)) * 100000L + (z + 50000);
  }

  /** Grilla de celdas de tamaño g -> índices de puntos (cacheada). */
  HashMap<Long, int[]> grid(float g) {
    HashMap<Long, int[]> cached = grids.get(g);
    if (cached != null) return cached;
    HashMap<Long, IntList> tmp = new HashMap<>();
    for (int i = 0; i < n; i++) {
      long k = gridKey((int) Math.floor(pos[i * 3] / g), (int) Math.floor(pos[i * 3 + 1] / g), (int) Math.floor(pos[i * 3 + 2] / g));
      IntList l = tmp.get(k);
      if (l == null) { l = new IntList(); tmp.put(k, l); }
      l.add(i);
    }
    HashMap<Long, int[]> out = new HashMap<>(tmp.size() * 2);
    for (java.util.Map.Entry<Long, IntList> e : tmp.entrySet()) out.put(e.getKey(), e.getValue().toArray());
    grids.put(g, out);
    return out;
  }

  /** Índice del punto más cercano dentro de la celda g (vecindario 3x3x3) o -1. */
  int nearest(float g, float x, float y, float z) {
    HashMap<Long, int[]> cells = grid(g);
    int cx = (int) Math.floor(x / g), cy = (int) Math.floor(y / g), cz = (int) Math.floor(z / g);
    int best = -1;
    float bd = g * g;
    for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
      int[] l = cells.get(gridKey(cx + dx, cy + dy, cz + dz));
      if (l == null) continue;
      for (int i : l) {
        float ex = pos[i * 3] - x, ey = pos[i * 3 + 1] - y, ez = pos[i * 3 + 2] - z;
        float d2 = ex * ex + ey * ey + ez * ez;
        if (d2 < bd) { bd = d2; best = i; }
      }
    }
    return best;
  }

  static final class IntList {
    int[] a = new int[8];
    int n;
    void add(int v) { if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2); a[n++] = v; }
    int[] toArray() { return java.util.Arrays.copyOf(a, n); }
  }
}
