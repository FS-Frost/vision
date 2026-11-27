package org.frost.vision.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Simplificación de mallas grandes por agrupación de vértices (vertex clustering) en una rejilla
 * GLOBAL de lado "cell": todos los vértices de una celda se funden en uno (posición, normal y color
 * promediados). Al ser la rejilla común a todas las superceldas, los bordes entre ellas siguen
 * cosidos. Se descartan los triángulos degenerados y los duplicados.
 */
public final class Simplify {
  private Simplify() {}

  public static List<MeshPart> cluster(List<MeshPart> parts, float cell) {
    HashMap<Long, float[]> acc = new HashMap<>(); // x y z nx ny nz r g b n
    for (MeshPart m : parts) {
      for (int i = 0; i < m.vertexCount(); i++) {
        long k = key(m.positions, i, cell);
        float[] a = acc.get(k);
        if (a == null) { a = new float[10]; acc.put(k, a); }
        for (int c = 0; c < 3; c++) {
          a[c] += m.positions[i * 3 + c];
          a[3 + c] += m.normals[i * 3 + c];
          a[6 + c] += m.colors[i * 3 + c];
        }
        a[9]++;
      }
    }
    List<MeshPart> out = new ArrayList<>();
    for (MeshPart m : parts) {
      HashMap<Long, Integer> local = new HashMap<>();
      List<float[]> verts = new ArrayList<>();
      int[] remap = new int[m.vertexCount()];
      for (int i = 0; i < m.vertexCount(); i++) {
        long k = key(m.positions, i, cell);
        Integer idx = local.get(k);
        if (idx == null) {
          idx = verts.size();
          local.put(k, idx);
          verts.add(acc.get(k));
        }
        remap[i] = idx;
      }
      int[] tris = new int[m.indices.length];
      int nt = 0;
      HashSet<String> seen = new HashSet<>();
      for (int t = 0; t < m.indices.length; t += 3) {
        int a = remap[m.indices[t]], b = remap[m.indices[t + 1]], c = remap[m.indices[t + 2]];
        if (a == b || b == c || a == c) continue;
        int lo = Math.min(a, Math.min(b, c)), hi = Math.max(a, Math.max(b, c)), mid = a + b + c - lo - hi;
        if (!seen.add(lo + "," + mid + "," + hi)) continue;
        tris[nt++] = a; tris[nt++] = b; tris[nt++] = c;
      }
      int nv = verts.size();
      float[] p = new float[nv * 3], n = new float[nv * 3], col = new float[nv * 3];
      for (int i = 0; i < nv; i++) {
        float[] a = verts.get(i);
        float len = (float) Math.sqrt(a[3] * a[3] + a[4] * a[4] + a[5] * a[5]);
        for (int c = 0; c < 3; c++) {
          p[i * 3 + c] = a[c] / a[9];
          n[i * 3 + c] = len > 0 ? a[3 + c] / len : (c == 1 ? 1 : 0);
          col[i * 3 + c] = a[6 + c] / a[9];
        }
      }
      if (nt > 0) out.add(new MeshPart(m.key, p, n, col, java.util.Arrays.copyOf(tris, nt)));
    }
    return out;
  }

  static long key(float[] pos, int i, float cell) {
    return Volume.key((int) Math.floor(pos[i * 3] / cell), (int) Math.floor(pos[i * 3 + 1] / cell), (int) Math.floor(pos[i * 3 + 2] / cell));
  }

  public static int triangles(List<MeshPart> parts) {
    int n = 0;
    for (MeshPart m : parts) n += m.triangleCount();
    return n;
  }

  /** Lado de celda para quedar cerca de maxTris (búsqueda simple, nunca por debajo de 1,5 voxels). */
  public static List<MeshPart> toBudget(List<MeshPart> parts, float voxel, int maxTris) {
    if (triangles(parts) <= maxTris) return parts;
    float cell = voxel * 1.5f;
    List<MeshPart> r = cluster(parts, cell);
    while (triangles(r) > maxTris && cell < voxel * 16) {
      cell *= 1.4f;
      r = cluster(parts, cell);
    }
    return r;
  }
}
