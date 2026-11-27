package org.frost.vision.core;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Volumen TSDF disperso en bloques de 8³ voxels. TSDF normalizado a [-1, 1] (positivo = espacio
 * libre), peso acumulado, color RGB por voxel con su contador y marca de congelado.
 *
 * Mismo formato por voxel que la GPU: color empaquetado en un int
 *   bits 0-7 r, 8-15 g, 16-23 b, 24-30 contador (0..127), bit 31 congelado.
 */
public final class Volume {
  public static final int B = 8, B3 = 512;
  private static final int OFF = 1 << 20;

  public static long key(int x, int y, int z) {
    return ((long) (x + OFF) << 42) | ((long) (y + OFF) << 21) | (long) (z + OFF);
  }
  public static int kx(long k) { return (int) (k >>> 42) - OFF; }
  public static int ky(long k) { return (int) ((k >>> 21) & 0x1FFFFF) - OFF; }
  public static int kz(long k) { return (int) (k & 0x1FFFFF) - OFF; }

  public static final int FROZEN = 0x80000000;

  public static final class Block {
    public final long key;
    public final int bx, by, bz;
    public final float[] t = new float[B3];
    public final float[] w = new float[B3];
    /** Color + contador + congelado (ver cabecera). */
    public final int[] c = new int[B3];
    /** Hueco en el pool de la GPU (-1 = sin asignar). */
    public int slot = -1;

    Block(long key) {
      this.key = key;
      this.bx = kx(key); this.by = ky(key); this.bz = kz(key);
    }

    public boolean frozen(int li) { return (c[li] & FROZEN) != 0; }
    public int count(int li) { return (c[li] >>> 24) & 0x7F; }
  }

  public final Params params;
  public final HashMap<Long, Block> blocks = new HashMap<>();
  /** Bloques modificados desde la última extracción de malla. */
  public final HashSet<Long> dirty = new HashSet<>();

  public Volume(Params params) { this.params = params; }

  public Block get(long key) { return blocks.get(key); }

  public Block getOrCreate(long key) {
    Block b = blocks.get(key);
    if (b == null) { b = new Block(key); blocks.put(key, b); }
    return b;
  }

  public void markDirty(long key) { dirty.add(key); }

  public int size() { return blocks.size(); }

  /** Congela todos los voxels conocidos (peso >= minWeight): al continuar solo se añaden zonas nuevas. */
  public void freezeKnown() {
    for (Block b : blocks.values()) {
      for (int i = 0; i < B3; i++) if (b.w[i] >= params.minWeight) b.c[i] |= FROZEN;
    }
  }

  public static int packColor(int r, int g, int b, int count, boolean frozen) {
    return (r & 255) | ((g & 255) << 8) | ((b & 255) << 16) | ((Math.min(count, 127) & 0x7F) << 24) | (frozen ? FROZEN : 0);
  }

  // ------------------------------------------------------------ serialización

  private static final int MAGIC = 0x56544F4C; // "VTOL"

  /** Volumen compacto: tsdf int16, peso uint8 (como la web), color int32. */
  public byte[] export() {
    try {
      ByteArrayOutputStream bos = new ByteArrayOutputStream(blocks.size() * (8 + B3 * 7) + 32);
      DataOutputStream out = new DataOutputStream(bos);
      out.writeInt(MAGIC);
      out.writeInt(1);
      out.writeFloat(params.voxel);
      out.writeInt(blocks.size());
      for (Block b : blocks.values()) {
        out.writeLong(b.key);
        for (int i = 0; i < B3; i++) out.writeShort(Math.round(b.t[i] * 32767f));
        for (int i = 0; i < B3; i++) out.writeByte(Math.min(255, Math.round(b.w[i])));
        for (int i = 0; i < B3; i++) out.writeInt(b.c[i] & ~FROZEN);
      }
      out.flush();
      return bos.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Carga un volumen exportado. Con freeze, los voxels conocidos quedan congelados: la
   * integración no los toca (la profundidad de ARCore no coincide entre sesiones).
   */
  public static Volume load(InputStream in, Params params, boolean freeze) throws IOException {
    DataInputStream din = new DataInputStream(in);
    if (din.readInt() != MAGIC) throw new IOException("volumen: formato desconocido");
    din.readInt();
    float voxel = din.readFloat();
    if (Math.abs(voxel - params.voxel) > 1e-6) throw new IOException("volumen: voxel distinto (" + voxel + ")");
    int n = din.readInt();
    Volume v = new Volume(params);
    for (int k = 0; k < n; k++) {
      Block b = v.getOrCreate(din.readLong());
      for (int i = 0; i < B3; i++) b.t[i] = din.readShort() / 32767f;
      for (int i = 0; i < B3; i++) b.w[i] = din.readUnsignedByte();
      for (int i = 0; i < B3; i++) b.c[i] = din.readInt();
      v.dirty.add(b.key);
    }
    if (freeze) v.freezeKnown();
    return v;
  }

  public static float voxelOf(byte[] exported) {
    return java.nio.ByteBuffer.wrap(exported, 8, 4).getFloat();
  }

  /** Claves de los 27 bloques vecinos (incluido el propio) que existen. */
  public List<Long> neighborhood(long key) {
    List<Long> out = new ArrayList<>(27);
    int x = kx(key), y = ky(key), z = kz(key);
    for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
      long k = key(x + dx, y + dy, z + dz);
      if (blocks.containsKey(k)) out.add(k);
    }
    return out;
  }
}
