package org.frost.vision.core;

/**
 * Sugerencia de anclas: ¿es buen punto el de la mirilla? Un ancla sirve si se puede volver a marcar
 * con precisión: profundidad fiable y cercana, y estructura 3D (esquina, borde, objeto) en lugar de
 * una pared lisa, donde cualquier punto se parece al de al lado.
 *
 * Medida: residuo RMS de un plano ajustado (mínimos cuadrados) a los puntos de una ventana
 * alrededor del punto. Una pared da ~0; una esquina o un borde, centímetros.
 */
public final class AnchorHint {
  public static final int NONE = 0, FLAT = 1, GOOD = 2;
  /** Residuo a partir del cual hay estructura (m). */
  public static final float MIN_RESIDUAL = 0.008f;
  public static final float MAX_DIST = 2.5f;

  private AnchorHint() {}

  /** {nivel, residuo (m)} en el píxel (u, v) de la profundidad. */
  public static float[] score(DepthFrame f, float u, float v, int radius) {
    boolean[] valid = f.validMask(0.08f);
    int n = 0;
    float[] pts = new float[(2 * radius + 1) * (2 * radius + 1) * 3];
    float cd = 0;
    int ci = (int) Math.floor(u), cj = (int) Math.floor(v);
    for (int j = cj - radius; j <= cj + radius; j++) for (int i = ci - radius; i <= ci + radius; i++) {
      if (i < 0 || j < 0 || i >= f.width || j >= f.height) continue;
      int id = j * f.width + i;
      float d = f.depth[id];
      if (!(d > 0.1f) || d > MAX_DIST || f.conf(id) < 0.5f) continue; // los saltos de borde cuentan: son estructura
      float x = (float) ((i + 0.5 - f.cx) / f.fx) * d, y = (float) (-(j + 0.5 - f.cy) / f.fy) * d;
      pts[n * 3] = x; pts[n * 3 + 1] = y; pts[n * 3 + 2] = -d;
      n++;
      if (i == ci && j == cj) cd = d;
    }
    int need = (2 * radius + 1) * (2 * radius + 1) / 2;
    if (n < need || cd == 0) return new float[] {NONE, 0};
    // Plano z = a x + b y + c (cámara: la superficie vista de frente es una función de x, y).
    double sxx = 0, sxy = 0, syy = 0, sx1 = 0, sy1 = 0, sz = 0, sxz = 0, syz = 0;
    for (int k = 0; k < n; k++) {
      double x = pts[k * 3], y = pts[k * 3 + 1], z = pts[k * 3 + 2];
      sxx += x * x; sxy += x * y; syy += y * y; sx1 += x; sy1 += y; sz += z; sxz += x * z; syz += y * z;
    }
    double[] A = {sxx, sxy, sx1, sxy, syy, sy1, sx1, sy1, n};
    double[] b = {sxz, syz, sz};
    double[] abc = solve3(A, b);
    if (abc == null) return new float[] {FLAT, 0};
    double r = 0;
    for (int k = 0; k < n; k++) {
      double e = pts[k * 3 + 2] - (abc[0] * pts[k * 3] + abc[1] * pts[k * 3 + 1] + abc[2]);
      r += e * e;
    }
    // El error de un plano oblicuo crece con la pendiente; se mide en la dirección de la normal.
    float res = (float) (Math.sqrt(r / n) / Math.sqrt(1 + abc[0] * abc[0] + abc[1] * abc[1]));
    return new float[] {res >= MIN_RESIDUAL ? GOOD : FLAT, res};
  }

  static double[] solve3(double[] m, double[] b) {
    double det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6]);
    if (Math.abs(det) < 1e-12) return null;
    double[] x = new double[3];
    for (int c = 0; c < 3; c++) {
      double[] t = m.clone();
      t[c] = b[0]; t[3 + c] = b[1]; t[6 + c] = b[2];
      x[c] = (t[0] * (t[4] * t[8] - t[5] * t[7]) - t[1] * (t[3] * t[8] - t[5] * t[6]) + t[2] * (t[3] * t[7] - t[4] * t[6])) / det;
    }
    return x;
  }
}
