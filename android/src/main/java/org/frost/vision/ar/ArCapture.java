package org.frost.vision.ar;

import android.media.Image;

import com.google.ar.core.Camera;
import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.exceptions.NotYetAvailableException;

import org.frost.vision.core.DepthFrame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * Convierte un frame de ARCore en un {@link DepthFrame}: profundidad raw (mm, 16 bits) + confianza
 * (Y8), intrínsecos de la textura escalados a la profundidad, pose del sensor (Camera.getPose, en la
 * orientación de la imagen, no de la pantalla) y color de la imagen de CPU (YUV) submuestreado.
 *
 * Convención de píxeles de core/: el centro del píxel i está en i + 0.5. ARCore usa centros en
 * enteros, así que cx_core = (cx_arcore + 0.5) * escala.
 */
public final class ArCapture {
  private long lastDepthTs = -1;
  /** Ancho máximo del color que se pasa a la fusión. */
  public int maxColorWidth = 480;

  /** Frame nuevo (profundidad fresca de este mismo frame de cámara) o null. */
  public DepthFrame capture(Frame frame, Camera cam, boolean withColor) {
    Image depth = null, conf = null;
    try {
      depth = frame.acquireRawDepthImage16Bits();
      long ts = depth.getTimestamp();
      // Solo se usa si la profundidad corresponde a este frame: así la pose es exacta.
      if (ts == lastDepthTs || ts != frame.getTimestamp()) return null;
      lastDepthTs = ts;
      int w = depth.getWidth(), h = depth.getHeight();
      float[] d = new float[w * h];
      Image.Plane p = depth.getPlanes()[0];
      ShortBuffer sb = p.getBuffer().order(ByteOrder.nativeOrder()).asShortBuffer();
      int rowShorts = p.getRowStride() / 2;
      for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) d[j * w + i] = (sb.get(j * rowShorts + i) & 0xFFFF) / 1000f;
      float[] c = null;
      try {
        conf = frame.acquireRawDepthConfidenceImage();
        Image.Plane cp = conf.getPlanes()[0];
        ByteBuffer cb = cp.getBuffer();
        int rs = cp.getRowStride(), ps = cp.getPixelStride();
        c = new float[w * h];
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) c[j * w + i] = (cb.get(j * rs + i * ps) & 255) / 255f;
      } catch (NotYetAvailableException ignored) {
        // sin confianza: se usa 1 (DepthFrame.conf)
      }
      CameraIntrinsics ti = cam.getTextureIntrinsics();
      int[] dim = ti.getImageDimensions();
      float[] fl = ti.getFocalLength(), pp = ti.getPrincipalPoint();
      float sx = w / (float) dim[0], sy = h / (float) dim[1];
      float[] pose = new float[16];
      cam.getPose().toMatrix(pose, 0);
      DepthFrame f = new DepthFrame(w, h, d, c, fl[0] * sx, fl[1] * sy, (pp[0] + 0.5f) * sx, (pp[1] + 0.5f) * sy, pose);
      f.timestampNs = ts;
      if (withColor) addColor(frame, cam, f);
      return f;
    } catch (NotYetAvailableException e) {
      return null;
    } finally {
      if (depth != null) depth.close();
      if (conf != null) conf.close();
    }
  }

  private void addColor(Frame frame, Camera cam, DepthFrame f) {
    Image img = null;
    try {
      img = frame.acquireCameraImage();
      int W = img.getWidth(), H = img.getHeight();
      int s = Math.max(1, (W + maxColorWidth - 1) / maxColorWidth);
      int w = W / s, h = H / s;
      byte[] rgb = yuvToRgb(img, s, w, h);
      CameraIntrinsics ii = cam.getImageIntrinsics();
      int[] dim = ii.getImageDimensions();
      float[] fl = ii.getFocalLength(), pp = ii.getPrincipalPoint();
      float kx = W / (float) dim[0] / s, ky = H / (float) dim[1] / s;
      f.withColor(rgb, w, h).withColorIntrinsics(fl[0] * kx, fl[1] * ky, (pp[0] + 0.5f) * kx, (pp[1] + 0.5f) * ky);
    } catch (NotYetAvailableException ignored) {
      // sin color en este frame
    } finally {
      if (img != null) img.close();
    }
  }

  /** YUV_420_888 -> RGB (BT.601 rango completo) muestreando un píxel por celda s x s. */
  static byte[] yuvToRgb(Image img, int s, int w, int h) {
    Image.Plane[] pl = img.getPlanes();
    ByteBuffer Y = pl[0].getBuffer(), U = pl[1].getBuffer(), V = pl[2].getBuffer();
    int yr = pl[0].getRowStride(), yp = pl[0].getPixelStride();
    int ur = pl[1].getRowStride(), up = pl[1].getPixelStride();
    int vr = pl[2].getRowStride(), vp = pl[2].getPixelStride();
    byte[] out = new byte[w * h * 3];
    int half = s / 2;
    for (int j = 0; j < h; j++) {
      int sy = j * s + half;
      for (int i = 0; i < w; i++) {
        int sx = i * s + half;
        int yy = Y.get(sy * yr + sx * yp) & 255;
        int u = (U.get((sy >> 1) * ur + (sx >> 1) * up) & 255) - 128;
        int v = (V.get((sy >> 1) * vr + (sx >> 1) * vp) & 255) - 128;
        int r = yy + Math.round(1.402f * v);
        int g = yy - Math.round(0.344136f * u + 0.714136f * v);
        int b = yy + Math.round(1.772f * u);
        int o = (j * w + i) * 3;
        out[o] = (byte) Math.max(0, Math.min(255, r));
        out[o + 1] = (byte) Math.max(0, Math.min(255, g));
        out[o + 2] = (byte) Math.max(0, Math.min(255, b));
      }
    }
    return out;
  }

  /** Copia de una imagen YUV_420_888 (el Image de ARCore debe cerrarse enseguida). */
  public static final class Yuv {
    public int width, height;
    byte[] y, u, v;
    int yr, yp, ur, up, vr, vp;
    /** Intrínsecos de la imagen completa (convención de core/: centro del píxel en i + 0,5). */
    public float fx, fy, cx, cy;

    /** ARGB submuestreado por s (para Bitmap). */
    public int[] argb(int s) {
      int w = width / s, h = height / s, half = s / 2;
      int[] out = new int[w * h];
      for (int j = 0; j < h; j++) {
        int sy = j * s + half;
        for (int i = 0; i < w; i++) {
          int sx = i * s + half;
          int yy = y[sy * yr + sx * yp] & 255;
          int uu = (u[(sy >> 1) * ur + (sx >> 1) * up] & 255) - 128;
          int vv = (v[(sy >> 1) * vr + (sx >> 1) * vp] & 255) - 128;
          int r = Math.max(0, Math.min(255, yy + Math.round(1.402f * vv)));
          int g = Math.max(0, Math.min(255, yy - Math.round(0.344136f * uu + 0.714136f * vv)));
          int b = Math.max(0, Math.min(255, yy + Math.round(1.772f * uu)));
          out[j * w + i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
      }
      return out;
    }
  }

  private static byte[] copy(ByteBuffer b) {
    ByteBuffer d = b.duplicate();
    d.rewind();
    byte[] a = new byte[d.remaining()];
    d.get(a);
    return a;
  }

  /** Copia la imagen de CPU a resolución completa (para fotos clave), o null. */
  public static Yuv copyCameraImage(Frame frame, Camera cam) {
    Image img = null;
    try {
      img = frame.acquireCameraImage();
      Yuv c = new Yuv();
      c.width = img.getWidth(); c.height = img.getHeight();
      Image.Plane[] pl = img.getPlanes();
      c.y = copy(pl[0].getBuffer()); c.u = copy(pl[1].getBuffer()); c.v = copy(pl[2].getBuffer());
      c.yr = pl[0].getRowStride(); c.yp = pl[0].getPixelStride();
      c.ur = pl[1].getRowStride(); c.up = pl[1].getPixelStride();
      c.vr = pl[2].getRowStride(); c.vp = pl[2].getPixelStride();
      CameraIntrinsics ii = cam.getImageIntrinsics();
      int[] dim = ii.getImageDimensions();
      float[] fl = ii.getFocalLength(), pp = ii.getPrincipalPoint();
      float kx = c.width / (float) dim[0], ky = c.height / (float) dim[1];
      c.fx = fl[0] * kx; c.fy = fl[1] * ky; c.cx = (pp[0] + 0.5f) * kx; c.cy = (pp[1] + 0.5f) * ky;
      return c;
    } catch (NotYetAvailableException e) {
      return null;
    } finally {
      if (img != null) img.close();
    }
  }

  /** Coordenadas de la profundidad (píxeles, convención de core/) de un punto de la vista normalizada. */
  public static float[] viewToDepth(Frame frame, float vx, float vy, int w, int h) {
    float[] out = new float[2];
    frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, new float[] {vx, vy}, Coordinates2d.TEXTURE_NORMALIZED, out);
    return new float[] {out[0] * w, out[1] * h};
  }

  /** Píxel de la imagen de color (ya submuestreada) de un punto de la vista normalizada. */
  public static float[] viewToColor(Frame frame, float vx, float vy, DepthFrame f) {
    float[] tn = new float[2];
    frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, new float[] {vx, vy}, Coordinates2d.IMAGE_NORMALIZED, tn);
    return new float[] {tn[0] * f.colorWidth, tn[1] * f.colorHeight};
  }

  /**
   * Punto de la mirilla con la profundidad propia: mediana de hasta 9 muestras de alta confianza
   * (≥ 0.5) alrededor de (u, v), ≤ 3.5 m. Devuelve {x, y, z, distancia} en el marco de la sesión,
   * o null si no hay profundidad fiable.
   */
  public static float[] crosshair(DepthFrame f, float u, float v) {
    float[] vals = new float[9];
    int n = 0;
    for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
      int i = (int) Math.floor(u) + di * 2, j = (int) Math.floor(v) + dj * 2;
      if (i < 0 || j < 0 || i >= f.width || j >= f.height) continue;
      int id = j * f.width + i;
      float d = f.depth[id];
      if (!(d > 0.1f) || d > 3.5f || f.conf(id) < 0.5f) continue;
      vals[n++] = d;
    }
    if (n < 3) return null;
    float[] s = Arrays.copyOf(vals, n);
    Arrays.sort(s);
    float d = s[n / 2];
    float[] pt = new float[3];
    f.unproject(u, v, d, pt);
    float[] cp = f.cameraPosition();
    float dist = (float) Math.sqrt((pt[0] - cp[0]) * (pt[0] - cp[0]) + (pt[1] - cp[1]) * (pt[1] - cp[1]) + (pt[2] - cp[2]) * (pt[2] - cp[2]));
    return new float[] {pt[0], pt[1], pt[2], dist};
  }
}
