package org.frost.vision.core;

/** Parámetros de fusión (los mismos para CPU y GPU). Valores medidos/calibrados en el moto g54. */
public final class Params {
  public float voxel = 0.03f;
  /** Truncamiento del TSDF (m). */
  public float trunc = 0.09f;
  public float minDepth = 0.2f;
  /** Más allá no se integra (la precisión de ARCore cae con la distancia). */
  public float maxDepth = 3.0f;
  /** Peso completo hasta aquí; baja linealmente hasta maxDepth. */
  public float nearFull = 1.5f;
  public float maxWeight = 32f;
  /** Peso mínimo para considerar conocido un voxel (los pesos son confianza x distancia, <= 1 por frame). */
  public float minWeight = 1.5f;
  /** Confianza mínima de un píxel de profundidad raw (0..1). */
  public float minConfidence = 0.15f;
  /** Salto relativo de profundidad que marca un borde. */
  public float edgeJump = 0.08f;
  /** Cierra huecos de 1 voxel al mallar. */
  public boolean dilate = true;

  public static Params withVoxel(float voxel) {
    Params p = new Params();
    p.voxel = voxel;
    p.trunc = voxel * 3;
    return p;
  }

  /** Peso de una muestra de profundidad según su distancia. */
  public float depthWeight(float d) {
    if (d <= nearFull) return 1f;
    if (d >= maxDepth) return 0f;
    return (maxDepth - d) / (maxDepth - nearFull);
  }

  public Params copy() {
    Params p = new Params();
    p.voxel = voxel; p.trunc = trunc; p.minDepth = minDepth; p.maxDepth = maxDepth; p.nearFull = nearFull;
    p.maxWeight = maxWeight; p.minWeight = minWeight; p.minConfidence = minConfidence; p.edgeJump = edgeJump; p.dilate = dilate;
    return p;
  }
}
