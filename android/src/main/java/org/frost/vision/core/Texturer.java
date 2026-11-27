package org.frost.vision.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Texturas con la imagen de la cámara en alta resolución por PROYECCIÓN desde fotos clave (sin
 * atlas): cada triángulo toma la mejor foto en la que se ve (visible según la profundidad de esa
 * foto, de frente y cerca) y sus vértices reciben las coordenadas de textura de su proyección. La
 * malla se parte en primitivas por (supercelda, foto). Los triángulos que ninguna foto ve conservan
 * el color por vértice.
 *
 * Convención de píxeles de core/: centro del píxel i en i + 0.5; u = (fx·x/z + cx) / ancho,
 * v = (cy − fy·y/z) / alto (v hacia abajo, como glTF).
 */
public final class Texturer {
  private Texturer() {}

  public static final class Keyframe {
    /** Pose cámara -> escaneo (column-major). */
    public float[] pose;
    /** Tamaño e intrínsecos de la foto. */
    public int width, height;
    public float fx, fy, cx, cy;
    /** Profundidad de ese mismo instante (misma pose) para saber qué se ve; puede ser null. */
    public DepthFrame depth;
    float[] inv;
  }

  /** Tolerancia de visibilidad: |profundidad medida − distancia del triángulo| (m, mínimo y relativo). */
  public static final float VIS_ABS = 0.06f, VIS_REL = 0.04f;
  /** Coseno mínimo entre la normal y la dirección a la cámara. */
  public static final float MIN_COS = 0.25f;
  /** Margen (píxeles) para que los tres vértices caigan dentro de la foto. */
  public static final float MARGIN = 2f;

  public static List<MeshPart> texture(List<MeshPart> meshes, List<Keyframe> kfs) {
    for (Keyframe k : kfs) k.inv = Mat4.invertRigid(k.pose);
    List<MeshPart> out = new ArrayList<>();
    float[] c = new float[3];
    for (MeshPart m : meshes) {
      int nt = m.triangleCount();
      int[] best = new int[nt];
      for (int t = 0; t < nt; t++) {
        int a = m.indices[t * 3], b = m.indices[t * 3 + 1], d = m.indices[t * 3 + 2];
        for (int q = 0; q < 3; q++) c[q] = (m.positions[a * 3 + q] + m.positions[b * 3 + q] + m.positions[d * 3 + q]) / 3f;
        float[] n = faceNormal(m.positions, a, b, d);
        double bestScore = 0;
        best[t] = -1;
        for (int ki = 0; ki < kfs.size(); ki++) {
          double s = score(kfs.get(ki), m.positions, a, b, d, c, n);
          if (s > bestScore) { bestScore = s; best[t] = ki; }
        }
      }
      // Una primitiva por foto (y otra para lo no visto, con color por vértice).
      HashMap<Integer, List<Integer>> tris = new HashMap<>();
      for (int t = 0; t < nt; t++) tris.computeIfAbsent(best[t], k -> new ArrayList<>()).add(t);
      for (java.util.Map.Entry<Integer, List<Integer>> e : tris.entrySet()) {
        int ki = e.getKey();
        int[] remap = new int[m.vertexCount()];
        java.util.Arrays.fill(remap, -1);
        List<Integer> verts = new ArrayList<>();
        int[] idx = new int[e.getValue().size() * 3];
        int o = 0;
        for (int t : e.getValue()) {
          for (int q = 0; q < 3; q++) {
            int v = m.indices[t * 3 + q];
            if (remap[v] < 0) { remap[v] = verts.size(); verts.add(v); }
            idx[o++] = remap[v];
          }
        }
        int nv = verts.size();
        float[] p = new float[nv * 3], nn = new float[nv * 3], col = new float[nv * 3];
        for (int i = 0; i < nv; i++) {
          int v = verts.get(i);
          System.arraycopy(m.positions, v * 3, p, i * 3, 3);
          System.arraycopy(m.normals, v * 3, nn, i * 3, 3);
          System.arraycopy(m.colors, v * 3, col, i * 3, 3);
        }
        MeshPart part = new MeshPart(m.key, p, nn, col, idx);
        if (ki >= 0) {
          Keyframe k = kfs.get(ki);
          float[] uv = new float[nv * 2];
          for (int i = 0; i < nv; i++) {
            float[] pc = toCam(k, p[i * 3], p[i * 3 + 1], p[i * 3 + 2]);
            float z = -pc[2];
            uv[i * 2] = (k.fx * pc[0] / z + k.cx) / k.width;
            uv[i * 2 + 1] = (k.cy - k.fy * pc[1] / z) / k.height;
          }
          part.uvs = uv;
          part.image = ki;
        }
        out.add(part);
      }
    }
    return out;
  }

  static float[] faceNormal(float[] p, int a, int b, int c) {
    float ux = p[b * 3] - p[a * 3], uy = p[b * 3 + 1] - p[a * 3 + 1], uz = p[b * 3 + 2] - p[a * 3 + 2];
    float vx = p[c * 3] - p[a * 3], vy = p[c * 3 + 1] - p[a * 3 + 1], vz = p[c * 3 + 2] - p[a * 3 + 2];
    float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
    float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
    return l > 0 ? new float[] {nx / l, ny / l, nz / l} : new float[] {0, 0, 0};
  }

  static float[] toCam(Keyframe k, float x, float y, float z) {
    float[] m = k.inv;
    return new float[] {m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14]};
  }

  /** Puntuación de una foto para un triángulo (0 = no sirve): coseno / distancia. */
  static double score(Keyframe k, float[] p, int a, int b, int d, float[] c, float[] n) {
    float[] cc = toCam(k, c[0], c[1], c[2]);
    float z = -cc[2];
    if (z < 0.15f) return 0;
    for (int v : new int[] {a, b, d}) {
      float[] pc = toCam(k, p[v * 3], p[v * 3 + 1], p[v * 3 + 2]);
      float zv = -pc[2];
      if (zv < 0.1f) return 0;
      float u = k.fx * pc[0] / zv + k.cx, w = k.cy - k.fy * pc[1] / zv;
      if (u < MARGIN || w < MARGIN || u > k.width - MARGIN || w > k.height - MARGIN) return 0;
    }
    // Dirección del triángulo a la cámara.
    float ex = k.pose[12] - c[0], ey = k.pose[13] - c[1], ez = k.pose[14] - c[2];
    float el = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
    float cos = (n[0] * ex + n[1] * ey + n[2] * ez) / el;
    if (cos < MIN_COS) return 0;
    if (k.depth != null) {
      DepthFrame f = k.depth;
      int iu = (int) Math.floor(f.fx * cc[0] / z + f.cx), iv = (int) Math.floor(f.cy - f.fy * cc[1] / z);
      if (iu < 0 || iv < 0 || iu >= f.width || iv >= f.height) return 0;
      float dm = f.depth[iv * f.width + iu];
      if (!(dm > 0) || Math.abs(dm - z) > Math.max(VIS_ABS, VIS_REL * z)) return 0; // tapado o sin dato
    }
    return cos / z;
  }
}
