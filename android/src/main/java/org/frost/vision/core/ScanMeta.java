package org.frost.vision.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Metadatos de un escaneo guardado (meta.json). Archivos del escaneo en su carpeta:
 * mesh.glb (malla limpia), volume.bin (volumen TSDF, si se puede continuar), start.jpg (pista de
 * inicio), anchor0.jpg... (fotos de las anclas).
 */
public final class ScanMeta {
  public static final class Anchor {
    /** Punto 3D en el marco del escaneo. */
    public float[] p;
    /** Distancia desde la que se marcó y altura de la cámara sobre el suelo. */
    public float dist, camHeight;
    /** Posición de la cámara al marcar (marco del escaneo). */
    public float[] cam;
    public String photo;
  }

  public String id, name, parent;
  public long created;
  public float voxel = 0.03f;
  public boolean imported, hasVolume;
  /** Pose (cámara -> escaneo) del primer frame con tracking: pista de inicio. */
  public float[] startPose;
  /** Altura del suelo en el marco del escaneo (planos de ARCore o malla), NaN si no se conoce. */
  public float floorY = Float.NaN;
  public int verts, tris;
  public final List<Anchor> anchors = new ArrayList<>();

  public String toJson() {
    StringBuilder s = new StringBuilder("{");
    s.append("\"id\":").append(Json.quote(id));
    s.append(",\"name\":").append(Json.quote(name));
    s.append(",\"parent\":").append(parent == null ? "null" : Json.quote(parent));
    s.append(",\"created\":").append(created);
    s.append(",\"voxel\":").append(voxel);
    s.append(",\"imported\":").append(imported);
    s.append(",\"hasVolume\":").append(hasVolume);
    s.append(",\"startPose\":").append(arr(startPose));
    s.append(",\"floorY\":").append(Float.isNaN(floorY) ? "null" : Float.toString(floorY));
    s.append(",\"verts\":").append(verts).append(",\"tris\":").append(tris);
    s.append(",\"anchors\":[");
    for (int i = 0; i < anchors.size(); i++) {
      Anchor a = anchors.get(i);
      if (i > 0) s.append(',');
      s.append("{\"p\":").append(arr(a.p)).append(",\"dist\":").append(a.dist)
          .append(",\"camHeight\":").append(a.camHeight).append(",\"cam\":").append(arr(a.cam))
          .append(",\"photo\":").append(a.photo == null ? "null" : Json.quote(a.photo)).append('}');
    }
    s.append("]}");
    return s.toString();
  }

  static String arr(float[] a) {
    if (a == null) return "null";
    StringBuilder s = new StringBuilder("[");
    for (int i = 0; i < a.length; i++) { if (i > 0) s.append(','); s.append(a[i]); }
    return s.append(']').toString();
  }

  static float[] farr(Object o) {
    if (!(o instanceof List)) return null;
    List<?> l = (List<?>) o;
    float[] a = new float[l.size()];
    for (int i = 0; i < a.length; i++) a[i] = ((Number) l.get(i)).floatValue();
    return a;
  }

  static float num(Object o, float def) { return o instanceof Number ? ((Number) o).floatValue() : def; }

  @SuppressWarnings("unchecked")
  public static ScanMeta fromJson(String text) {
    Map<String, Object> m = (Map<String, Object>) Json.parse(text);
    ScanMeta s = new ScanMeta();
    s.id = (String) m.get("id");
    s.name = (String) m.get("name");
    s.parent = (String) m.get("parent");
    if (m.get("created") instanceof Number) s.created = ((Number) m.get("created")).longValue();
    s.voxel = num(m.get("voxel"), 0.03f);
    s.imported = Boolean.TRUE.equals(m.get("imported"));
    s.hasVolume = Boolean.TRUE.equals(m.get("hasVolume"));
    s.startPose = farr(m.get("startPose"));
    s.floorY = num(m.get("floorY"), Float.NaN);
    s.verts = (int) num(m.get("verts"), 0);
    s.tris = (int) num(m.get("tris"), 0);
    Object an = m.get("anchors");
    if (an instanceof List) {
      for (Object o : (List<Object>) an) {
        Map<String, Object> am = (Map<String, Object>) o;
        Anchor a = new Anchor();
        a.p = farr(am.get("p"));
        a.dist = num(am.get("dist"), 0);
        a.camHeight = num(am.get("camHeight"), Float.NaN);
        a.cam = farr(am.get("cam"));
        a.photo = (String) am.get("photo");
        s.anchors.add(a);
      }
    }
    return s;
  }
}
