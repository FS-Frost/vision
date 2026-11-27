package org.frost.vision.core;

/**
 * Escena sintética: habitación de 3,5 x 4 m (suelo y = 0, techo 2,5) con una caja, renderizada
 * por raycasting a mapas de profundidad con intrínsecos de cámara. La usan los tests JVM y la
 * prueba GPU contra CPU en el teléfono (sin moverse ni grabar nada).
 */
public final class TestScene {
  public static final int W = 160, H = 90;
  // misma cámara que la escena de la web: fov vertical 60° (fy = 45 / tan 30°)
  public static final float FX = 77.94f, FY = 77.94f, CX = W / 2f, CY = H / 2f;
  static final float[] BOX_MIN = {0.2f, 0f, -1.4f}, BOX_MAX = {0.7f, 0.6f, -0.9f};

  private TestScene() {}

  /** Profundidad + color (cada superficie con su color) vista desde la pose dada (cámara -> mundo). */
  public static DepthFrame room(float[] pose) {
    float[] depth = new float[W * H];
    byte[] color = new byte[W * H * 3];
    float ox = pose[12], oy = pose[13], oz = pose[14];
    for (int j = 0; j < H; j++) {
      for (int i = 0; i < W; i++) {
        float xc = (i + 0.5f - CX) / FX, yc = -(j + 0.5f - CY) / FY;
        float dx = pose[0] * xc + pose[4] * yc - pose[8];
        float dy = pose[1] * xc + pose[5] * yc - pose[9];
        float dz = pose[2] * xc + pose[6] * yc - pose[10];
        float t = Float.MAX_VALUE;
        int surf = -1;
        float[][] planes = {{1, 0}, {1, 2.5f}, {0, -1.5f}, {0, 2f}, {2, -2.5f}, {2, 1.5f}};
        float[] o = {ox, oy, oz}, d = {dx, dy, dz};
        for (int p = 0; p < planes.length; p++) {
          int axis = (int) planes[p][0];
          if (Math.abs(d[axis]) < 1e-9) continue;
          float tt = (planes[p][1] - o[axis]) / d[axis];
          if (tt > 0.01f && tt < t) { t = tt; surf = p; }
        }
        float t0 = 0.01f, t1 = Float.MAX_VALUE;
        for (int a = 0; a < 3; a++) {
          float inv = 1f / d[a];
          float ta = (BOX_MIN[a] - o[a]) * inv, tb = (BOX_MAX[a] - o[a]) * inv;
          if (ta > tb) { float x = ta; ta = tb; tb = x; }
          t0 = Math.max(t0, ta); t1 = Math.min(t1, tb);
        }
        if (t0 <= t1 && t0 < t) { t = t0; surf = 6; }
        int id = j * W + i;
        depth[id] = t < 60 ? t : 0;
        int[][] palette = {{120, 90, 60}, {230, 230, 230}, {200, 60, 60}, {60, 160, 60}, {60, 60, 200}, {200, 200, 60}, {150, 60, 160}};
        int[] c = surf >= 0 ? palette[surf] : new int[] {0, 0, 0};
        color[id * 3] = (byte) c[0]; color[id * 3 + 1] = (byte) c[1]; color[id * 3 + 2] = (byte) c[2];
      }
    }
    DepthFrame f = new DepthFrame(W, H, depth, null, FX, FY, CX, CY, pose);
    return f.withColor(color, W, H);
  }

  /** Pared plana frontal a profundidad d (para tests unitarios). */
  public static DepthFrame plane(float d, float[] pose) {
    float[] depth = new float[W * H];
    java.util.Arrays.fill(depth, d);
    return new DepthFrame(W, H, depth, null, FX, FY, CX, CY, pose);
  }

  /** Escanea la habitación desde el origen girando (como un usuario). */
  public static void scanRoom(Fusion fusion) {
    for (double yaw = -1.4; yaw <= 1.41; yaw += 0.2) {
      for (double pitch : new double[] {-0.5, -0.15}) {
        float[] M = Mat4.pose(yaw, pitch, 0, 1.3, 0);
        DepthFrame f = room(M);
        fusion.integrateCpu(f);
        fusion.integrateCpu(f);
      }
    }
  }

  /** Parámetros de "sensor ideal": sin corte por distancia (miden el algoritmo, no la captura). */
  public static Params ideal(float voxel) {
    Params p = Params.withVoxel(voxel);
    p.maxDepth = 4.5f;
    p.nearFull = 4.5f;
    return p;
  }
}
