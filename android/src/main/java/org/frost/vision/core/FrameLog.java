package org.frost.vision.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Registro propio de frames (profundidad + confianza + pose + intrínsecos + color), independiente
 * del MP4 de ARCore. Sirve para dos cosas: reproducir offline exactamente lo que vio la fusión
 * (prueba GPU contra CPU, reproducibilidad de la continuación) y cubrir el riesgo R7 (que la
 * reproducción de ARCore no traiga profundidad raw).
 *
 * Formato (big-endian, DataOutputStream): cabecera MAGIC, VERSION; por frame:
 *   long ts, int w, int h, float fx fy cx cy, float[16] pose,
 *   short[w*h] profundidad en mm, byte[w*h] confianza 0..255 (o 0 bytes si flag=0),
 *   int cw, int ch, float cfx cfy ccx ccy, byte[cw*ch*3] RGB (cw = 0 sin color).
 */
public final class FrameLog {
  public static final int MAGIC = 0x56464C47; // "VFLG"
  public static final int VERSION = 1;

  private FrameLog() {}

  public static void writeHeader(DataOutputStream out) throws IOException {
    out.writeInt(MAGIC);
    out.writeInt(VERSION);
  }

  public static void write(DataOutputStream out, DepthFrame f) throws IOException {
    int n = f.width * f.height;
    out.writeLong(f.timestampNs);
    out.writeInt(f.width); out.writeInt(f.height);
    out.writeFloat(f.fx); out.writeFloat(f.fy); out.writeFloat(f.cx); out.writeFloat(f.cy);
    for (int i = 0; i < 16; i++) out.writeFloat(f.pose[i]);
    byte[] d = new byte[n * 2];
    for (int i = 0; i < n; i++) {
      int mm = Math.max(0, Math.min(65535, Math.round(f.depth[i] * 1000f)));
      d[i * 2] = (byte) (mm >>> 8); d[i * 2 + 1] = (byte) mm;
    }
    out.write(d);
    out.writeBoolean(f.confidence != null);
    if (f.confidence != null) {
      byte[] c = new byte[n];
      for (int i = 0; i < n; i++) c[i] = (byte) Math.max(0, Math.min(255, Math.round(f.confidence[i] * 255f)));
      out.write(c);
    }
    if (f.color == null) {
      out.writeInt(0); out.writeInt(0);
    } else {
      out.writeInt(f.colorWidth); out.writeInt(f.colorHeight);
      float[] k = f.colorK();
      for (float v : k) out.writeFloat(v);
      out.write(f.color, 0, f.colorWidth * f.colorHeight * 3);
    }
  }

  public static final class Reader {
    private final DataInputStream in;

    public Reader(InputStream in) throws IOException {
      this.in = new DataInputStream(in);
      if (this.in.readInt() != MAGIC) throw new IOException("no es un registro de frames de Vision");
      int v = this.in.readInt();
      if (v != VERSION) throw new IOException("versión de registro no soportada: " + v);
    }

    /** Siguiente frame, o null al final. */
    public DepthFrame next() throws IOException {
      long ts;
      try { ts = in.readLong(); } catch (EOFException e) { return null; }
      int w = in.readInt(), h = in.readInt();
      float fx = in.readFloat(), fy = in.readFloat(), cx = in.readFloat(), cy = in.readFloat();
      float[] pose = new float[16];
      for (int i = 0; i < 16; i++) pose[i] = in.readFloat();
      int n = w * h;
      byte[] d = new byte[n * 2];
      in.readFully(d);
      float[] depth = new float[n];
      for (int i = 0; i < n; i++) depth[i] = (((d[i * 2] & 255) << 8) | (d[i * 2 + 1] & 255)) / 1000f;
      float[] conf = null;
      if (in.readBoolean()) {
        byte[] c = new byte[n];
        in.readFully(c);
        conf = new float[n];
        for (int i = 0; i < n; i++) conf[i] = (c[i] & 255) / 255f;
      }
      DepthFrame f = new DepthFrame(w, h, depth, conf, fx, fy, cx, cy, pose);
      f.timestampNs = ts;
      int cw = in.readInt(), ch = in.readInt();
      if (cw > 0) {
        float cfx = in.readFloat(), cfy = in.readFloat(), ccx = in.readFloat(), ccy = in.readFloat();
        byte[] rgb = new byte[cw * ch * 3];
        in.readFully(rgb);
        f.withColor(rgb, cw, ch).withColorIntrinsics(cfx, cfy, ccx, ccy);
      }
      return f;
    }

    public void close() throws IOException { in.close(); }
  }

  /** Utilidad: abre un flujo de salida con cabecera. */
  public static DataOutputStream open(OutputStream os) throws IOException {
    DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(os, 1 << 16));
    writeHeader(out);
    return out;
  }
}
