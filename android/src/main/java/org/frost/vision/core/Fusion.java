package org.frost.vision.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Orquesta el volumen: integración (CPU de referencia; la GPU escribe en el mismo Volume),
 * mallado por bloques sucios, superceldas de 4³ bloques, limpieza final de fragmentos, nube de
 * superficie, puntuación de poses e ICP. Portado de scan/fusion-core.js.
 *
 * No es thread-safe: usarlo desde un único hilo (el hilo de fusión).
 */
public final class Fusion {
  public static final int SUPER = 4;

  public final Params params;
  public Volume volume;
  private final SurfaceNets nets = new SurfaceNets();
  private final LinkedHashSet<Long> pending = new LinkedHashSet<>();
  public final HashMap<Long, MeshPart> blockMeshes = new HashMap<>();
  private Cloud target;
  /** Transformación sesión -> escaneo guardado (continuar); null = identidad. */
  public float[] align;

  public Fusion(Params params) {
    this.params = params;
    this.volume = new Volume(params);
  }

  public void setVolume(Volume v) {
    volume = v;
    pending.clear();
    blockMeshes.clear();
    target = null;
  }

  /** Aplica la alineación (si hay) a la pose de un frame de la sesión. */
  public DepthFrame aligned(DepthFrame f) {
    if (align == null) return f;
    return f.withPose(Mat4.mul(align, f.pose));
  }

  /** Integración en CPU (referencia / respaldo). */
  public int integrateCpu(DepthFrame frame) {
    DepthFrame f = aligned(frame);
    return Integrator.integrate(volume, f, Integrator.touchedBlocks(f, params, 2));
  }

  private void collectDirty() {
    if (volume.dirty.isEmpty()) return;
    for (long k : volume.dirty) pending.addAll(volume.neighborhood(k));
    volume.dirty.clear();
  }

  public static long superOf(long blockKey) {
    return Volume.key(Volume.kx(blockKey) >> 2, Volume.ky(blockKey) >> 2, Volume.kz(blockKey) >> 2);
  }

  /** Re-malla bloques pendientes (con presupuesto) y devuelve las superceldas cambiadas. */
  public List<MeshPart> extract(double budgetMs) {
    collectDirty();
    long t0 = System.nanoTime();
    HashSet<Long> supers = new HashSet<>();
    Iterator<Long> it = pending.iterator();
    while (it.hasNext()) {
      long key = it.next();
      it.remove();
      MeshPart m = nets.mesh(volume, key);
      if (m != null) blockMeshes.put(key, m); else blockMeshes.remove(key);
      supers.add(superOf(key));
      if ((System.nanoTime() - t0) / 1e6 > budgetMs) break;
    }
    List<MeshPart> out = new ArrayList<>();
    for (long sk : supers) out.add(mergeSuper(sk, blockMeshes));
    target = null;
    return out;
  }

  public int pendingCount() { return pending.size() + volume.dirty.size(); }

  /** Todas las superceldas (sin limpieza), por ejemplo para mostrar un escaneo recién cargado. */
  public List<MeshPart> allMeshes() {
    while (pendingCount() > 0) extract(Double.MAX_VALUE);
    HashSet<Long> supers = new HashSet<>();
    for (long k : blockMeshes.keySet()) supers.add(superOf(k));
    List<MeshPart> out = new ArrayList<>();
    for (long sk : supers) {
      MeshPart m = mergeSuper(sk, blockMeshes);
      if (!m.isEmpty()) out.add(m);
    }
    return out;
  }

  /** Resultado de la malla final limpia. */
  public static final class Final {
    public final List<MeshPart> meshes;
    public final int removedTris, totalTris;
    Final(List<MeshPart> meshes, int removed, int total) { this.meshes = meshes; removedTris = removed; totalTris = total; }
  }

  /**
   * Malla final: termina lo pendiente y elimina componentes conexas pequeñas (fragmentos
   * sueltos), igual que la web (umbral max(minTris, total * minFraction)).
   */
  public Final finalMeshes(int minTris, double minFraction) {
    while (pendingCount() > 0) extract(Double.MAX_VALUE);
    List<Map.Entry<Long, MeshPart>> entries = new ArrayList<>(blockMeshes.entrySet());
    HashMap<Long, Integer> ids = new HashMap<>();
    List<int[]> vids = new ArrayList<>();
    for (Map.Entry<Long, MeshPart> e : entries) {
      float[] p = e.getValue().positions;
      int nv = p.length / 3;
      int[] arr = new int[nv];
      for (int v = 0; v < nv; v++) {
        long qx = Math.round(p[v * 3] * 1000) + 50000, qy = Math.round(p[v * 3 + 1] * 1000) + 50000, qz = Math.round(p[v * 3 + 2] * 1000) + 50000;
        long key = (qx * 100001L + qy) * 100001L + qz;
        Integer id = ids.get(key);
        if (id == null) { id = ids.size(); ids.put(key, id); }
        arr[v] = id;
      }
      vids.add(arr);
    }
    int[] parent = new int[ids.size()];
    for (int i = 0; i < parent.length; i++) parent[i] = i;
    int total = 0;
    for (int e = 0; e < entries.size(); e++) {
      int[] ix = entries.get(e).getValue().indices, vid = vids.get(e);
      for (int i = 0; i < ix.length; i += 3) {
        union(parent, vid[ix[i]], vid[ix[i + 1]]);
        union(parent, vid[ix[i]], vid[ix[i + 2]]);
      }
      total += ix.length / 3;
    }
    int[] compTris = new int[parent.length];
    for (int e = 0; e < entries.size(); e++) {
      int[] ix = entries.get(e).getValue().indices, vid = vids.get(e);
      for (int i = 0; i < ix.length; i += 3) compTris[find(parent, vid[ix[i]])]++;
    }
    double threshold = Math.max(minTris, total * minFraction);
    HashMap<Long, MeshPart> filtered = new HashMap<>();
    int removed = 0;
    for (int e = 0; e < entries.size(); e++) {
      MeshPart m = entries.get(e).getValue();
      int[] ix = m.indices, vid = vids.get(e);
      int[] keep = new int[ix.length];
      int n = 0;
      for (int i = 0; i < ix.length; i += 3) {
        if (compTris[find(parent, vid[ix[i]])] >= threshold) {
          keep[n++] = ix[i]; keep[n++] = ix[i + 1]; keep[n++] = ix[i + 2];
        } else removed++;
      }
      if (n > 0) filtered.put(entries.get(e).getKey(), new MeshPart(m.key, m.positions, m.normals, m.colors, java.util.Arrays.copyOf(keep, n)));
    }
    HashSet<Long> supers = new HashSet<>();
    for (long k : blockMeshes.keySet()) supers.add(superOf(k));
    List<MeshPart> out = new ArrayList<>();
    for (long sk : supers) {
      MeshPart m = mergeSuper(sk, filtered);
      if (!m.isEmpty()) out.add(m);
    }
    return new Final(out, removed, total);
  }

  private static int find(int[] p, int x) {
    while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; }
    return x;
  }

  private static void union(int[] p, int a, int b) {
    a = find(p, a); b = find(p, b);
    if (a != b) p[a] = b;
  }

  static MeshPart mergeSuper(long sk, Map<Long, MeshPart> source) {
    int sx = Volume.kx(sk), sy = Volume.ky(sk), sz = Volume.kz(sk);
    List<MeshPart> parts = new ArrayList<>();
    int nv = 0, ni = 0;
    for (int x = 0; x < SUPER; x++) for (int y = 0; y < SUPER; y++) for (int z = 0; z < SUPER; z++) {
      MeshPart m = source.get(Volume.key(sx * SUPER + x, sy * SUPER + y, sz * SUPER + z));
      if (m != null) { parts.add(m); nv += m.vertexCount(); ni += m.indices.length; }
    }
    float[] pos = new float[nv * 3], nor = new float[nv * 3], col = new float[nv * 3];
    int[] idx = new int[ni];
    int vo = 0, io = 0;
    for (MeshPart m : parts) {
      System.arraycopy(m.positions, 0, pos, vo * 3, m.positions.length);
      System.arraycopy(m.normals, 0, nor, vo * 3, m.normals.length);
      System.arraycopy(m.colors, 0, col, vo * 3, m.colors.length);
      for (int i = 0; i < m.indices.length; i++) idx[io + i] = m.indices[i] + vo;
      vo += m.vertexCount();
      io += m.indices.length;
    }
    return new MeshPart(sk, pos, nor, col, idx);
  }

  // ------------------------------------------------------------ nube, puntuación, ICP

  /** Nube de superficie: vértices de la malla submuestreados a 5 cm (normales promediadas). */
  public Cloud surfaceCloud(boolean fresh) {
    if (target != null && !fresh) return target;
    while (pendingCount() > 0) extract(Double.MAX_VALUE);
    target = cloudFrom(blockMeshes.values());
    return target;
  }

  public static Cloud cloudFrom(Iterable<MeshPart> meshes) {
    float g = 0.05f;
    HashMap<Long, float[]> acc = new HashMap<>();
    for (MeshPart m : meshes) {
      float[] p = m.positions, n = m.normals;
      for (int i = 0; i < p.length; i += 3) {
        long k = Cloud.gridKey((int) Math.floor(p[i] / g), (int) Math.floor(p[i + 1] / g), (int) Math.floor(p[i + 2] / g));
        float[] a = acc.get(k);
        if (a == null) { a = new float[7]; acc.put(k, a); }
        a[0] += p[i]; a[1] += p[i + 1]; a[2] += p[i + 2];
        a[3] += n[i]; a[4] += n[i + 1]; a[5] += n[i + 2]; a[6]++;
      }
    }
    float[] pos = new float[acc.size() * 3], nor = new float[acc.size() * 3];
    int k = 0;
    // Orden por celda: el resultado (ICP, puntuaciones) no depende del orden de la tabla hash.
    long[] keys = new long[acc.size()];
    int nk = 0;
    for (long key : acc.keySet()) keys[nk++] = key;
    java.util.Arrays.sort(keys);
    for (long key : keys) {
      float[] a = acc.get(key);
      float len = (float) Math.sqrt(a[3] * a[3] + a[4] * a[4] + a[5] * a[5]);
      if (len < 0.5f * a[6]) continue;
      pos[k * 3] = a[0] / a[6]; pos[k * 3 + 1] = a[1] / a[6]; pos[k * 3 + 2] = a[2] / a[6];
      nor[k * 3] = a[3] / len; nor[k * 3 + 1] = a[4] / len; nor[k * 3 + 2] = a[5] / len;
      k++;
    }
    return new Cloud(java.util.Arrays.copyOf(pos, k * 3), java.util.Arrays.copyOf(nor, k * 3), k);
  }

  /**
   * Calidad de una pose (yaw, t) de la nube src contra la superficie de esta Fusion: fracción de
   * puntos a < resTol de la superficie con normal compatible (coseno > cosTol).
   */
  public double scorePose(Cloud src, double yaw, float[] t, float resTol, float cosTol) {
    Cloud T = surfaceCloud(false);
    float g = Math.max(0.05f, (float) Math.ceil(resTol * 150) / 100f);
    float c = (float) Math.cos(yaw), s = (float) Math.sin(yaw);
    int good = 0;
    for (int i = 0; i < src.n; i++) {
      float px = src.pos[i * 3], py = src.pos[i * 3 + 1], pz = src.pos[i * 3 + 2];
      float qx = c * px + s * pz + t[0], qy = py + t[1], qz = -s * px + c * pz + t[2];
      float nx = c * src.nor[i * 3] + s * src.nor[i * 3 + 2], ny = src.nor[i * 3 + 1], nz = -s * src.nor[i * 3] + c * src.nor[i * 3 + 2];
      int j = T.nearest(g, qx, qy, qz);
      if (j < 0) continue;
      float r = T.nor[j * 3] * (qx - T.pos[j * 3]) + T.nor[j * 3 + 1] * (qy - T.pos[j * 3 + 1]) + T.nor[j * 3 + 2] * (qz - T.pos[j * 3 + 2]);
      if (Math.abs(r) < resTol && nx * T.nor[j * 3] + ny * T.nor[j * 3 + 1] + nz * T.nor[j * 3 + 2] > cosTol) good++;
    }
    return src.n > 0 ? (double) good / src.n : 0;
  }

  /** Resultado de alineación: yaw + traslación (gravedad común). */
  public static final class Pose4 {
    public final double yaw;
    public final float[] t;
    public final double inlierRatio, rms;
    public Pose4(double yaw, float[] t, double inlierRatio, double rms) { this.yaw = yaw; this.t = t; this.inlierRatio = inlierRatio; this.rms = rms; }
    public float[] matrix() { return Mat4.yaw(yaw, t[0], t[1], t[2]); }
  }

  /** ICP punto-plano de 4 grados de libertad desde una pose inicial, con radios decrecientes. */
  public Pose4 alignPoints(float[] src, double[] initial, float[] levels, int itersPer) {
    Cloud T = surfaceCloud(false);
    double[] p = initial.clone();
    int nSrc = src.length / 3;
    if (T.n < 50 || nSrc < 50) return new Pose4(p[0], new float[] {(float) p[1], (float) p[2], (float) p[3]}, 0, Double.MAX_VALUE);
    int[] coarse = pick(nSrc, 400), fine = pick(nSrc, 1200);
    for (float G : levels) {
      int[] set = G >= 0.2f ? coarse : fine;
      for (int it = 0; it < itersPer; it++) icpStep(T, src, p, set, G);
    }
    // calidad: inliers a < 5 cm
    float c = (float) Math.cos(p[0]), s = (float) Math.sin(p[0]);
    int inl = 0;
    double se = 0;
    for (int i : fine) {
      float px = src[i * 3], py = src[i * 3 + 1], pz = src[i * 3 + 2];
      float qx = (float) (c * px + s * pz + p[1]), qy = (float) (py + p[2]), qz = (float) (-s * px + c * pz + p[3]);
      int j = T.nearest(0.05f, qx, qy, qz);
      if (j < 0) continue;
      double r = T.nor[j * 3] * (qx - T.pos[j * 3]) + T.nor[j * 3 + 1] * (qy - T.pos[j * 3 + 1]) + T.nor[j * 3 + 2] * (qz - T.pos[j * 3 + 2]);
      inl++; se += r * r;
    }
    return new Pose4(p[0], new float[] {(float) p[1], (float) p[2], (float) p[3]},
        (double) inl / fine.length, inl > 0 ? Math.sqrt(se / inl) : Double.MAX_VALUE);
  }

  private static int[] pick(int n, int max) {
    int step = Math.max(1, n / max);
    int[] out = new int[(n + step - 1) / step];
    for (int i = 0, k = 0; i < n; i += step, k++) out[k] = i;
    return out;
  }

  private static void icpStep(Cloud T, float[] src, double[] p, int[] set, float G) {
    double[] H = new double[16], b = new double[4];
    double c = Math.cos(p[0]), s = Math.sin(p[0]);
    int n = 0;
    for (int i : set) {
      double px = src[i * 3], py = src[i * 3 + 1], pz = src[i * 3 + 2];
      double qx = c * px + s * pz + p[1], qy = py + p[2], qz = -s * px + c * pz + p[3];
      int j = T.nearest(G, (float) qx, (float) qy, (float) qz);
      if (j < 0) continue;
      double nx = T.nor[j * 3], ny = T.nor[j * 3 + 1], nz = T.nor[j * 3 + 2];
      double r = nx * (qx - T.pos[j * 3]) + ny * (qy - T.pos[j * 3 + 1]) + nz * (qz - T.pos[j * 3 + 2]);
      double w = Math.abs(r) < 0.02 ? 1 : 0.02 / Math.abs(r);
      double[] J = {nx * (-s * px + c * pz) + nz * (-c * px - s * pz), nx, ny, nz};
      for (int a = 0; a < 4; a++) {
        b[a] += w * J[a] * r;
        for (int k = 0; k < 4; k++) H[a * 4 + k] += w * J[a] * J[k];
      }
      n++;
    }
    if (n < 30) return;
    for (int a = 0; a < 4; a++) H[a * 4 + a] += 1e-3 + H[a * 4 + a] * 1e-2;
    double[] d = solve4(H, new double[] {-b[0], -b[1], -b[2], -b[3]});
    if (d == null) return;
    p[0] += Math.max(-0.2, Math.min(0.2, d[0]));
    for (int a = 1; a < 4; a++) p[a] += Math.max(-0.25, Math.min(0.25, d[a]));
  }

  static double[] solve4(double[] A, double[] b) {
    double[][] M = new double[4][5];
    for (int r = 0; r < 4; r++) { for (int k = 0; k < 4; k++) M[r][k] = A[r * 4 + k]; M[r][4] = b[r]; }
    for (int col = 0; col < 4; col++) {
      int piv = col;
      for (int r = col + 1; r < 4; r++) if (Math.abs(M[r][col]) > Math.abs(M[piv][col])) piv = r;
      if (Math.abs(M[piv][col]) < 1e-12) return null;
      double[] tmp = M[col]; M[col] = M[piv]; M[piv] = tmp;
      for (int r = 0; r < 4; r++) {
        if (r == col) continue;
        double f = M[r][col] / M[col][col];
        for (int k = col; k < 5; k++) M[r][k] -= f * M[col][k];
      }
    }
    double[] out = new double[4];
    for (int i = 0; i < 4; i++) out[i] = M[i][4] / M[i][i];
    return out;
  }

  /** Puntos 3D (marco de la sesión, sin align) de un frame, submuestreados. */
  public static float[] samplePoints(DepthFrame f, Params p, int stride) {
    boolean[] valid = f.validMask(p.edgeJump);
    float[] pt = new float[3];
    float[] out = new float[(f.width / stride + 1) * (f.height / stride + 1) * 3];
    int n = 0;
    for (int j = 0; j < f.height; j += stride) for (int i = 0; i < f.width; i += stride) {
      int id = j * f.width + i;
      if (!valid[id] || f.conf(id) < 0.5f) continue;
      float d = f.depth[id];
      if (d < p.minDepth || d > 3.5f) continue;
      f.unproject(i + 0.5, j + 0.5, d, pt);
      out[n++] = pt[0]; out[n++] = pt[1]; out[n++] = pt[2];
    }
    return java.util.Arrays.copyOf(out, n);
  }

  public int[] stats() {
    int verts = 0, tris = 0;
    for (MeshPart m : blockMeshes.values()) { verts += m.vertexCount(); tris += m.triangleCount(); }
    return new int[] {volume.size(), blockMeshes.size(), pendingCount(), verts, tris};
  }
}
