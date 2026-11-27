package org.frost.vision.core;

/** Matrices 4x4 column-major (como OpenGL y ARCore) y utilidades de transformación rígida. */
public final class Mat4 {
  private Mat4() {}

  public static float[] identity() {
    return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  }

  /** a * b */
  public static float[] mul(float[] a, float[] b) {
    float[] o = new float[16];
    for (int c = 0; c < 4; c++) {
      for (int r = 0; r < 4; r++) {
        o[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
      }
    }
    return o;
  }

  /** Inversa de una transformación rígida (rotación + traslación). */
  public static float[] invertRigid(float[] m) {
    float[] o = new float[16];
    o[0] = m[0]; o[1] = m[4]; o[2] = m[8];
    o[4] = m[1]; o[5] = m[5]; o[6] = m[9];
    o[8] = m[2]; o[9] = m[6]; o[10] = m[10];
    o[12] = -(o[0] * m[12] + o[4] * m[13] + o[8] * m[14]);
    o[13] = -(o[1] * m[12] + o[5] * m[13] + o[9] * m[14]);
    o[14] = -(o[2] * m[12] + o[6] * m[13] + o[10] * m[14]);
    o[15] = 1;
    return o;
  }

  /** Giro alrededor de Y + traslación. Convención: x' = c x + s z ; z' = -s x + c z. */
  public static float[] yaw(double yaw, double tx, double ty, double tz) {
    float c = (float) Math.cos(yaw), s = (float) Math.sin(yaw);
    return new float[] {c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, (float) tx, (float) ty, (float) tz, 1};
  }

  /** Aplica m a un punto. */
  public static void transform(float[] m, float x, float y, float z, float[] out) {
    out[0] = m[0] * x + m[4] * y + m[8] * z + m[12];
    out[1] = m[1] * x + m[5] * y + m[9] * z + m[13];
    out[2] = m[2] * x + m[6] * y + m[10] * z + m[14];
  }

  /** Pose de cámara (cámara -> mundo): traslación * Ry(yaw) * Rx(pitch). Útil para escenas de prueba. */
  public static float[] pose(double yaw, double pitch, double x, double y, double z) {
    float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
    float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
    return new float[] {cy, 0, -sy, 0, sy * sp, cp, cy * sp, 0, sy * cp, -sp, cy * cp, 0, (float) x, (float) y, (float) z, 1};
  }

  public static double angleDiff(double a, double b) {
    return Math.abs(Math.atan2(Math.sin(a - b), Math.cos(a - b)));
  }
}
