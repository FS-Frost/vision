package org.frost.vision.core;

/** Geometría indexada: posiciones, normales y colores (RGB lineal) por vértice. */
public final class MeshPart {
  public final long key;
  public final float[] positions, normals, colors;
  public final int[] indices;

  public MeshPart(long key, float[] positions, float[] normals, float[] colors, int[] indices) {
    this.key = key;
    this.positions = positions; this.normals = normals; this.colors = colors; this.indices = indices;
  }

  public int vertexCount() { return positions.length / 3; }
  public int triangleCount() { return indices.length / 3; }
  public boolean isEmpty() { return indices.length == 0; }

  public static MeshPart empty(long key) {
    return new MeshPart(key, new float[0], new float[0], new float[0], new int[0]);
  }
}
