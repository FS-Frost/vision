package org.frost.vision.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Integración TSDF PROYECTIVA (estilo KinectFusion): cada voxel de los bloques afectados se
 * proyecta en la imagen de profundidad y actualiza su TSDF. Es la referencia en CPU del compute
 * shader de la GPU (assets/shaders/integrate.comp): cualquier cambio va en ambos, con la
 * misma aritmética (floor, redondeo a +0.5, misma ponderación).
 *
 * Peso por muestra = confianza (raw depth) x peso por distancia.
 */
public final class Integrator {
  private Integrator() {}

  /**
   * Bloques que toca un frame: rayos submuestreados recorren la banda ±trunc. La usan CPU y GPU
   * (la GPU no asigna bloques: recibe la lista).
   */
  public static List<Long> touchedBlocks(DepthFrame f, Params p, int stride) {
    boolean[] valid = f.validMask(p.edgeJump);
    LongSet keys = new LongSet(1024);
    float inv = 1f / p.voxel;
    int k = (int) Math.ceil(p.trunc / p.voxel);
    float[] m = f.pose;
    float ox = m[12], oy = m[13], oz = m[14];
    for (int j = stride / 2; j < f.height; j += stride) {
      float yc = (float) (-(j + 0.5 - f.cy) / f.fy);
      for (int i = stride / 2; i < f.width; i += stride) {
        int id = j * f.width + i;
        if (!valid[id]) continue;
        float d = f.depth[id];
        if (d < p.minDepth || d >= p.maxDepth || f.conf(id) < p.minConfidence) continue;
        // dirección del rayo en el mundo para profundidad 1 (cámara: x, y, -1)
        float xc = (float) ((i + 0.5 - f.cx) / f.fx);
        float dx = m[0] * xc + m[4] * yc - m[8], dy = m[1] * xc + m[5] * yc - m[9], dz = m[2] * xc + m[6] * yc - m[10];
        long last = Long.MIN_VALUE;
        for (int n = -k; n <= k; n++) {
          float dd = d + n * p.voxel;
          if (dd <= 0) continue;
          int ix = (int) Math.floor((ox + dx * dd) * inv), iy = (int) Math.floor((oy + dy * dd) * inv), iz = (int) Math.floor((oz + dz * dd) * inv);
          long key = Volume.key(ix >> 3, iy >> 3, iz >> 3);
          if (key != last) { keys.add(key); last = key; }
        }
      }
    }
    long[] arr = keys.toArray();
    java.util.Arrays.sort(arr); // orden determinista (no depende de la tabla hash)
    List<Long> out = new ArrayList<>(arr.length);
    for (long v : arr) out.add(v);
    return out;
  }

  /** Integra un frame en los bloques indicados (los crea si no existen). Marca sucios los modificados. */
  public static int integrate(Volume vol, DepthFrame f, List<Long> blockKeys) {
    Params p = vol.params;
    boolean[] valid = f.validMask(p.edgeJump);
    float[] inv = Mat4.invertRigid(f.pose);
    float voxel = p.voxel, trunc = p.trunc;
    int W = f.width, H = f.height;
    float[] ck = f.color != null ? f.colorK() : null;
    int updates = 0;
    for (long key : blockKeys) {
      Volume.Block b = vol.getOrCreate(key);
      boolean changed = false;
      for (int lz = 0; lz < 8; lz++) {
        for (int ly = 0; ly < 8; ly++) {
          for (int lx = 0; lx < 8; lx++) {
            int li = lx | (ly << 3) | (lz << 6);
            int cpack = b.c[li];
            if ((cpack & Volume.FROZEN) != 0) continue;
            float wx = (b.bx * 8 + lx + 0.5f) * voxel;
            float wy = (b.by * 8 + ly + 0.5f) * voxel;
            float wz = (b.bz * 8 + lz + 0.5f) * voxel;
            float cx = inv[0] * wx + inv[4] * wy + inv[8] * wz + inv[12];
            float cy = inv[1] * wx + inv[5] * wy + inv[9] * wz + inv[13];
            float cz = inv[2] * wx + inv[6] * wy + inv[10] * wz + inv[14];
            float z = -cz;
            if (z < p.minDepth) continue;
            float u = f.fx * cx / z + f.cx;
            float v = f.cy - f.fy * cy / z;
            int iu = (int) Math.floor(u), iv = (int) Math.floor(v);
            if (iu < 0 || iv < 0 || iu >= W || iv >= H) continue;
            int id = iv * W + iu;
            if (!valid[id]) continue;
            float d = f.depth[id];
            float conf = f.conf(id);
            if (conf < p.minConfidence || d >= p.maxDepth) continue;
            float wd = conf * p.depthWeight(d);
            if (wd <= 0) continue;
            float sdf = d - z;
            if (sdf < -trunc) continue;
            float tsdf = Math.min(1f, sdf / trunc);
            float w0 = b.w[li], w1 = w0 + wd;
            b.t[li] = (b.t[li] * w0 + tsdf * wd) / w1;
            b.w[li] = Math.min(w1, p.maxWeight);
            if (ck != null && Math.abs(sdf) < 1.5f * voxel) {
              int ci = (int) Math.floor(ck[0] * cx / z + ck[2]);
              int cj = (int) Math.floor(ck[3] - ck[1] * cy / z);
              if (ci >= 0 && cj >= 0 && ci < f.colorWidth && cj < f.colorHeight) {
                int o = (cj * f.colorWidth + ci) * 3;
                int nr = f.color[o] & 255, ng = f.color[o + 1] & 255, nb = f.color[o + 2] & 255;
                b.c[li] = blendColor(cpack, nr, ng, nb);
              }
            }
            changed = true;
            updates++;
          }
        }
      }
      if (changed) vol.markDirty(key);
    }
    return updates;
  }

  /** Promedio incremental del color (contador topado a 15 para el promedio, 127 guardado). */
  public static int blendColor(int cpack, int nr, int ng, int nb) {
    int n = (cpack >>> 24) & 0x7F;
    float a = 1f / (Math.min(n, 15) + 1);
    int r = cpack & 255, g = (cpack >>> 8) & 255, bl = (cpack >>> 16) & 255;
    r += (int) Math.floor((nr - r) * a + 0.5f);
    g += (int) Math.floor((ng - g) * a + 0.5f);
    bl += (int) Math.floor((nb - bl) * a + 0.5f);
    return Volume.packColor(r, g, bl, n + 1, false);
  }
}
