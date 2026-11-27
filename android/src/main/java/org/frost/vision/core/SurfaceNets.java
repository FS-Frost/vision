package org.frost.vision.core;

/**
 * Surface Nets por bloque (portado de scan/fusion-core.js de la web).
 *
 * Lee la ventana local -2..9 (12³: el bloque y una capa de cada vecino), aplica una dilatación
 * de 1 voxel (una muestra desconocida con >= 3 vecinas conocidas toma su promedio: solo depende
 * de datos globales, así es idéntica en todo bloque y no deja costuras), genera un vértice por
 * cubo con cambio de signo y un quad por arista de la grilla. Cada bloque es dueño de las aristas
 * que nacen en sus muestras locales 0..7: sin costuras entre bloques.
 *
 * No es thread-safe (buffers internos): una instancia por hilo.
 */
public final class SurfaceNets {
  private static final int SW = 12, S = 10, C = 9;
  private static final int[] EDGE_A = new int[12], EDGE_B = new int[12];
  private static final int[] W_NEIGHBORS = {1, -1, SW, -SW, SW * SW, -SW * SW};
  static final float[] SRGB_TO_LINEAR = new float[256];

  static {
    int e = 0;
    for (int c = 0; c < 8; c++) {
      for (int bit : new int[] {1, 2, 4}) {
        if ((c & bit) == 0) { EDGE_A[e] = c; EDGE_B[e] = c | bit; e++; }
      }
    }
    for (int i = 0; i < 256; i++) {
      double c = i / 255.0;
      SRGB_TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
    }
  }

  private final float[] WT = new float[SW * SW * SW], WW = new float[SW * SW * SW];
  private final int[] WC = new int[SW * SW * SW];
  private final float[] T = new float[S * S * S], R = new float[S * S * S], G = new float[S * S * S], Bc = new float[S * S * S];
  private final boolean[] K = new boolean[S * S * S], CK = new boolean[S * S * S];
  private final int[] V = new int[C * C * C];
  private final float[] val = new float[8];
  private final float[] pos = new float[C * C * C * 3], nor = new float[C * C * C * 3], col = new float[C * C * C * 3];
  private final int[] idx = new int[3 * 512 * 6];
  private int ni;

  private static int wIdx(int x, int y, int z) { return x + SW * (y + SW * z); }
  private static int sIdx(int x, int y, int z) { return x + S * (y + S * z); }
  private static int cIdx(int x, int y, int z) { return x + C * (y + C * z); }

  /** Malla del bloque `key`, o null si no tiene superficie. */
  public MeshPart mesh(Volume vol, long key) {
    Params p = vol.params;
    int bx = Volume.kx(key), by = Volume.ky(key), bz = Volume.kz(key);
    float minW = p.minWeight, vs = p.voxel;

    // 1. ventana local -2..9
    int wx0 = bx * 8 - 2, wy0 = by * 8 - 2, wz0 = bz * 8 - 2;
    long cacheKey = Long.MIN_VALUE;
    Volume.Block cb = null;
    for (int z = 0; z < SW; z++) {
      int gz = wz0 + z;
      for (int y = 0; y < SW; y++) {
        int gy = wy0 + y;
        for (int x = 0; x < SW; x++) {
          int gx = wx0 + x;
          long k = Volume.key(gx >> 3, gy >> 3, gz >> 3);
          if (k != cacheKey) { cacheKey = k; cb = vol.get(k); }
          int s = wIdx(x, y, z);
          if (cb != null) {
            int li = (gx & 7) | ((gy & 7) << 3) | ((gz & 7) << 6);
            WT[s] = cb.t[li]; WW[s] = cb.w[li]; WC[s] = cb.c[li];
          } else {
            WT[s] = 0; WW[s] = 0; WC[s] = 0;
          }
        }
      }
    }

    // 2. muestras -1..8 con dilatación
    for (int z = 0; z < S; z++) for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
      int w = wIdx(x + 1, y + 1, z + 1);
      int s = sIdx(x, y, z);
      if (WW[w] >= minW) {
        T[s] = WT[w]; K[s] = true;
        int c = WC[w];
        if (((c >>> 24) & 0x7F) > 0) { R[s] = c & 255; G[s] = (c >>> 8) & 255; Bc[s] = (c >>> 16) & 255; CK[s] = true; } else CK[s] = false;
        continue;
      }
      K[s] = false; CK[s] = false; T[s] = 0;
      if (!p.dilate) continue;
      int n = 0, cn = 0;
      float sum = 0, r = 0, g = 0, b = 0;
      for (int q = 0; q < 6; q++) {
        int nb = w + W_NEIGHBORS[q];
        if (WW[nb] >= minW) {
          n++; sum += WT[nb];
          int c = WC[nb];
          if (((c >>> 24) & 0x7F) > 0) { cn++; r += c & 255; g += (c >>> 8) & 255; b += (c >>> 16) & 255; }
        }
      }
      if (n >= 3) {
        T[s] = sum / n; K[s] = true;
        if (cn > 0) { R[s] = r / cn; G[s] = g / cn; Bc[s] = b / cn; CK[s] = true; }
      }
    }

    // 3. un vértice por cubo con cambio de signo
    java.util.Arrays.fill(V, -1);
    int nv = 0;
    int gx0 = bx * 8 - 1, gy0 = by * 8 - 1, gz0 = bz * 8 - 1;
    for (int cz = 0; cz < C; cz++) for (int cy = 0; cy < C; cy++) for (int cx = 0; cx < C; cx++) {
      int mask = 0;
      boolean ok = true;
      for (int c = 0; c < 8; c++) {
        int s = sIdx(cx + (c & 1), cy + ((c >> 1) & 1), cz + (c >> 2));
        if (!K[s]) { ok = false; break; }
        float t = T[s];
        val[c] = t;
        if (t < 0) mask |= 1 << c;
      }
      if (!ok || mask == 0 || mask == 255) continue;
      float sx = 0, sy = 0, sz = 0;
      int n = 0;
      for (int e = 0; e < 12; e++) {
        int a = EDGE_A[e], bb = EDGE_B[e];
        float va = val[a], vb = val[bb];
        if ((va < 0) == (vb < 0)) continue;
        float f = va / (va - vb);
        int ax = a & 1, ay = (a >> 1) & 1, az = a >> 2;
        sx += ax + f * ((bb & 1) - ax);
        sy += ay + f * (((bb >> 1) & 1) - ay);
        sz += az + f * ((bb >> 2) - az);
        n++;
      }
      int o = nv * 3;
      pos[o] = (gx0 + cx + sx / n + 0.5f) * vs;
      pos[o + 1] = (gy0 + cy + sy / n + 0.5f) * vs;
      pos[o + 2] = (gz0 + cz + sz / n + 0.5f) * vs;
      float gx = (val[1] + val[3] + val[5] + val[7]) - (val[0] + val[2] + val[4] + val[6]);
      float gy = (val[2] + val[3] + val[6] + val[7]) - (val[0] + val[1] + val[4] + val[5]);
      float gz = (val[4] + val[5] + val[6] + val[7]) - (val[0] + val[1] + val[2] + val[3]);
      float len = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
      if (len == 0) len = 1;
      nor[o] = gx / len; nor[o + 1] = gy / len; nor[o + 2] = gz / len;
      float r = 0, g = 0, b = 0;
      int cn = 0;
      for (int c = 0; c < 8; c++) {
        int s = sIdx(cx + (c & 1), cy + ((c >> 1) & 1), cz + (c >> 2));
        if (CK[s]) { r += R[s]; g += G[s]; b += Bc[s]; cn++; }
      }
      if (cn > 0) {
        col[o] = SRGB_TO_LINEAR[Math.round(r / cn)];
        col[o + 1] = SRGB_TO_LINEAR[Math.round(g / cn)];
        col[o + 2] = SRGB_TO_LINEAR[Math.round(b / cn)];
      } else {
        col[o] = col[o + 1] = col[o + 2] = 0.2f;
      }
      V[cIdx(cx, cy, cz)] = nv++;
    }
    if (nv == 0) return null;

    // 4. un quad por arista con cambio de signo (aristas que nacen en las muestras 1..8)
    ni = 0;
    for (int z = 1; z <= 8; z++) for (int y = 1; y <= 8; y++) for (int x = 1; x <= 8; x++) {
      int s = sIdx(x, y, z);
      if (!K[s]) continue;
      boolean neg = T[s] < 0;
      int s1 = s + 1;
      if (K[s1] && (T[s1] < 0) != neg) quad(V[cIdx(x, y - 1, z - 1)], V[cIdx(x, y, z - 1)], V[cIdx(x, y, z)], V[cIdx(x, y - 1, z)]);
      s1 = s + S;
      if (K[s1] && (T[s1] < 0) != neg) quad(V[cIdx(x - 1, y, z - 1)], V[cIdx(x, y, z - 1)], V[cIdx(x, y, z)], V[cIdx(x - 1, y, z)]);
      s1 = s + S * S;
      if (K[s1] && (T[s1] < 0) != neg) quad(V[cIdx(x - 1, y - 1, z)], V[cIdx(x, y - 1, z)], V[cIdx(x, y, z)], V[cIdx(x - 1, y, z)]);
    }
    if (ni == 0) return null;
    return new MeshPart(key,
        java.util.Arrays.copyOf(pos, nv * 3), java.util.Arrays.copyOf(nor, nv * 3),
        java.util.Arrays.copyOf(col, nv * 3), java.util.Arrays.copyOf(idx, ni));
  }

  private void quad(int a, int b, int c, int d) {
    if (a < 0 || b < 0 || c < 0 || d < 0) return;
    int a3 = a * 3, b3 = b * 3, c3 = c * 3, d3 = d * 3;
    float e1x = pos[b3] - pos[a3], e1y = pos[b3 + 1] - pos[a3 + 1], e1z = pos[b3 + 2] - pos[a3 + 2];
    float e2x = pos[c3] - pos[a3], e2y = pos[c3 + 1] - pos[a3 + 1], e2z = pos[c3 + 2] - pos[a3 + 2];
    float fx = e1y * e2z - e1z * e2y, fy = e1z * e2x - e1x * e2z, fz = e1x * e2y - e1y * e2x;
    float nx = nor[a3] + nor[b3] + nor[c3] + nor[d3];
    float ny = nor[a3 + 1] + nor[b3 + 1] + nor[c3 + 1] + nor[d3 + 1];
    float nz = nor[a3 + 2] + nor[b3 + 2] + nor[c3 + 2] + nor[d3 + 2];
    if (fx * nx + fy * ny + fz * nz >= 0) {
      idx[ni++] = a; idx[ni++] = b; idx[ni++] = c;
      idx[ni++] = a; idx[ni++] = c; idx[ni++] = d;
    } else {
      idx[ni++] = a; idx[ni++] = c; idx[ni++] = b;
      idx[ni++] = a; idx[ni++] = d; idx[ni++] = c;
    }
  }
}
