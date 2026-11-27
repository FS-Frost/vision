package org.frost.vision.core;

/**
 * Geometría indexada: posiciones, normales y colores (RGB lineal) por vértice. Opcional: textura
 * (uvs + índice de la foto en la lista de imágenes del .glb; image = -1 sin textura).
 */
public final class MeshPart {
  public final long key;
  public final float[] positions, normals, colors;
  public final int[] indices;
  /** Coordenadas de textura (u, v; v hacia abajo como en glTF) o null. */
  public float[] uvs;
  /** Índice de la imagen (foto clave) o -1. */
  public int image = -1;

  public MeshPart(long key, float[] positions, float[] normals, float[] colors, int[] indices) {
    this.key = key;
    this.positions = positions; this.normals = normals; this.colors = colors; this.indices = indices;
  }

  public int vertexCount() { return positions.length / 3; }
  public int triangleCount() { return indices.length / 3; }
  public boolean isEmpty() { return indices.length == 0; }
  public boolean textured() { return uvs != null && image >= 0; }

  public static MeshPart empty(long key) {
    return new MeshPart(key, new float[0], new float[0], new float[0], new int[0]);
  }
}
