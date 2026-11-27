package org.frost.vision.core;

/**
 * Un mapa de profundidad con su pose y sus intrínsecos, independiente de ARCore.
 *
 * Convenciones (las de ARCore / OpenGL): la cámara mira hacia -Z, +Y arriba, +X derecha,
 * respecto de la imagen en su orden de lectura (izquierda->derecha, arriba->abajo).
 * Píxel (i, j) con profundidad plana d:
 *   x_c = (i + 0.5 - cx) / fx * d ;  y_c = -(j + 0.5 - cy) / fy * d ;  z_c = -d
 * La imagen de color (opcional) está alineada con la de profundidad (mismo campo de visión).
 */
public final class DepthFrame {
  public final int width, height;
  /** Profundidad en metros (0 = sin dato). */
  public final float[] depth;
  /** Confianza 0..1 por píxel (null = confianza 1). */
  public final float[] confidence;
  /** Intrínsecos de la imagen de profundidad (píxeles). */
  public final float fx, fy, cx, cy;
  /** Pose cámara -> mundo, column-major. */
  public final float[] pose;
  /** Color RGB (3 bytes por píxel), alineado con la profundidad; puede ser null. */
  public byte[] color;
  public int colorWidth, colorHeight;
  /**
   * Intrínsecos de la imagen de color. En ARCore la imagen de CPU puede tener otra resolución y
   * otro campo de visión (4:3) que la de profundidad: el color se mapea con sus propios
   * intrínsecos. 0 = misma geometría que la profundidad, escalada.
   */
  public float colorFx, colorFy, colorCx, colorCy;
  /** Marca de tiempo (ns) opcional. */
  public long timestampNs;

  private boolean[] valid;

  public DepthFrame(int width, int height, float[] depth, float[] confidence,
                    float fx, float fy, float cx, float cy, float[] pose) {
    this.width = width; this.height = height;
    this.depth = depth; this.confidence = confidence;
    this.fx = fx; this.fy = fy; this.cx = cx; this.cy = cy;
    this.pose = pose;
  }

  public DepthFrame withColor(byte[] rgb, int w, int h) {
    this.color = rgb; this.colorWidth = w; this.colorHeight = h;
    return this;
  }

  public DepthFrame withColorIntrinsics(float fx, float fy, float cx, float cy) {
    this.colorFx = fx; this.colorFy = fy; this.colorCx = cx; this.colorCy = cy;
    return this;
  }

  /**
   * Intrínsecos efectivos del color {fx, fy, cx, cy}: los propios o los de la profundidad escalados.
   * Píxel de color de un punto de cámara: ci = cfx * x/z + ccx ; cj = ccy - cfy * y/z.
   */
  public float[] colorK() {
    if (colorFx > 0) return new float[] {colorFx, colorFy, colorCx, colorCy};
    float sx = colorWidth / (float) width, sy = colorHeight / (float) height;
    return new float[] {fx * sx, fy * sy, cx * sx, cy * sy};
  }

  /** Copia con otra pose (alineación al continuar); comparte los buffers. */
  public DepthFrame withPose(float[] newPose) {
    DepthFrame g = new DepthFrame(width, height, depth, confidence, fx, fy, cx, cy, newPose);
    g.color = color; g.colorWidth = colorWidth; g.colorHeight = colorHeight;
    g.colorFx = colorFx; g.colorFy = colorFy; g.colorCx = colorCx; g.colorCy = colorCy;
    g.timestampNs = timestampNs; g.valid = valid;
    return g;
  }

  /**
   * Píxeles utilizables: con profundidad y sin salto brusco respecto de sus vecinos
   * ("flying pixels" en los bordes de los objetos). Se calcula una vez y se cachea.
   */
  public boolean[] validMask(float edgeJump) {
    if (valid != null) return valid;
    boolean[] v = new boolean[width * height];
    for (int j = 0; j < height; j++) {
      for (int i = 0; i < width; i++) {
        int id = j * width + i;
        float d = depth[id];
        if (!(d > 0)) continue;
        if (i + 1 < width && Math.abs(depth[id + 1] - d) > edgeJump * d) continue;
        if (j + 1 < height && Math.abs(depth[id + width] - d) > edgeJump * d) continue;
        if (i > 0 && Math.abs(depth[id - 1] - d) > edgeJump * d) continue;
        if (j > 0 && Math.abs(depth[id - width] - d) > edgeJump * d) continue;
        v[id] = true;
      }
    }
    valid = v;
    return v;
  }

  public float conf(int id) {
    return confidence == null ? 1f : confidence[id];
  }

  /** Punto 3D en el mundo del píxel (i, j) a profundidad d. */
  public void unproject(double i, double j, float d, float[] out) {
    float xc = (float) ((i - cx) / fx) * d;
    float yc = (float) (-(j - cy) / fy) * d;
    float zc = -d;
    Mat4.transform(pose, xc, yc, zc, out);
  }

  /** Posición de la cámara en el mundo. */
  public float[] cameraPosition() {
    return new float[] {pose[12], pose[13], pose[14]};
  }
}
