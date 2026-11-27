package org.frost.vision.store;

import org.frost.vision.core.Cloud;
import org.frost.vision.core.FrameLog;
import org.frost.vision.core.Fusion;
import org.frost.vision.core.Glb;
import org.frost.vision.core.MeshPart;
import org.frost.vision.core.Reloc;
import org.frost.vision.core.ScanMeta;
import org.frost.vision.core.Texturer;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Guarda una fusión como escaneo: malla final limpia (mesh.glb), volumen (volume.bin), metadatos,
 * pista de inicio y fotos de anclas. Lo usan la pantalla de escaneo y la prueba en el dispositivo,
 * así el camino de guardado es el mismo. Llamar desde el hilo que posee la fusión.
 *
 * Continuar = escaneo NUEVO: nunca se sobrescribe el original (si la alineación fue mala, se perdería).
 */
public final class ScanSaver {
  private ScanSaver() {}

  public static final class Input {
    public String name;
    /** Escaneo que se continuó, o null. */
    public ScanMeta parent;
    /** Pose (marco del escaneo) del primer frame de un escaneo nuevo. */
    public float[] startPose;
    public byte[] startJpeg;
    /** Suelo según ARCore (NaN si no se conoce). */
    public float arFloor = Float.NaN;
    public List<ScanMeta.Anchor> anchors;
    public List<byte[]> anchorJpegs;
    /** Fotos clave (pose en el marco del escaneo) y su JPEG, para las texturas. */
    public List<Texturer.Keyframe> keyframes;
    public List<byte[]> keyframeJpegs;
  }

  /** Lee las fotos clave guardadas de un escaneo (para texturizar una continuación). */
  public static void loadKeyframes(ScanStore store, String id, List<Texturer.Keyframe> kfs, List<byte[]> jpegs) throws IOException {
    File log = store.file(id, "keyframes.frames"), meta = store.file(id, "keyframes.json");
    if (!log.isFile() || !meta.isFile()) return;
    @SuppressWarnings("unchecked")
    List<Object> ks = (List<Object>) org.frost.vision.core.Json.parse(new String(ScanStore.read(meta), "UTF-8"));
    try (java.io.FileInputStream in = new java.io.FileInputStream(log)) {
      FrameLog.Reader r = new FrameLog.Reader(in);
      for (Object o : ks) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> m = (java.util.Map<String, Object>) o;
        org.frost.vision.core.DepthFrame d = r.next();
        File jpg = store.file(id, (String) m.get("photo"));
        if (d == null || !jpg.isFile()) break;
        Texturer.Keyframe k = new Texturer.Keyframe();
        k.pose = d.pose; k.depth = d;
        k.width = ((Double) m.get("w")).intValue(); k.height = ((Double) m.get("h")).intValue();
        k.fx = ((Double) m.get("fx")).floatValue(); k.fy = ((Double) m.get("fy")).floatValue();
        k.cx = ((Double) m.get("cx")).floatValue(); k.cy = ((Double) m.get("cy")).floatValue();
        kfs.add(k);
        jpegs.add(ScanStore.read(jpg));
      }
    }
  }

  private static void saveKeyframes(ScanStore store, String id, List<Texturer.Keyframe> kfs, List<byte[]> jpegs) throws IOException {
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream out = FrameLog.open(bos);
    StringBuilder js = new StringBuilder("[");
    for (int i = 0; i < kfs.size(); i++) {
      Texturer.Keyframe k = kfs.get(i);
      String photo = "kf" + i + ".jpg";
      store.writeFile(id, photo, jpegs.get(i));
      org.frost.vision.core.DepthFrame d = k.depth.withPose(k.pose);
      d.color = null;
      FrameLog.write(out, d);
      if (i > 0) js.append(',');
      js.append("{\"photo\":\"").append(photo).append("\",\"w\":").append(k.width).append(",\"h\":").append(k.height)
          .append(",\"fx\":").append(k.fx).append(",\"fy\":").append(k.fy).append(",\"cx\":").append(k.cx).append(",\"cy\":").append(k.cy).append('}');
    }
    out.flush();
    store.writeFile(id, "keyframes.frames", bos.toByteArray());
    store.writeFile(id, "keyframes.json", js.append(']').toString().getBytes("UTF-8"));
  }

  /** Devuelve los metadatos guardados, o null si no hay malla. */
  public static ScanMeta save(ScanStore store, Fusion f, Input in) throws IOException {
    Fusion.Final fin = f.finalMeshes(80, 0.004);
    if (fin.totalTris == 0) return null;
    ScanMeta m = new ScanMeta();
    m.id = ScanStore.newId();
    while (store.dir(m.id).exists()) m.id = m.id + "b";
    m.created = System.currentTimeMillis();
    m.name = in.name != null ? in.name : in.parent != null ? in.parent.name + " (ampliado)" : ScanStore.defaultName(m.created);
    m.voxel = f.params.voxel;
    m.hasVolume = true;
    m.parent = in.parent != null ? in.parent.id : null;
    for (MeshPart p : fin.meshes) { m.verts += p.vertexCount(); m.tris += p.triangleCount(); }
    if (in.parent != null) {
      m.startPose = in.parent.startPose;
      m.floorY = in.parent.floorY;
      m.anchors.addAll(in.parent.anchors);
    } else {
      m.startPose = in.startPose;
      Cloud cloud = Fusion.cloudFrom(fin.meshes);
      float fy = Reloc.estimateFloor(cloud, Float.isNaN(in.arFloor) ? null : in.arFloor);
      // Medido en el g54: sin ver el suelo, una cama a 0,67 m pasó por suelo. Se exige >= 0,8 m bajo la cámara.
      if (in.startPose != null && in.startPose[13] - fy < Reloc.MIN_CAM_ABOVE_FLOOR && !Float.isNaN(in.arFloor)) fy = in.arFloor;
      m.floorY = fy;
    }
    if (in.anchors != null) {
      for (int i = 0; i < in.anchors.size(); i++) {
        ScanMeta.Anchor a = in.anchors.get(i);
        a.photo = "anchor" + m.anchors.size() + ".jpg";
        byte[] jpg = in.anchorJpegs != null && i < in.anchorJpegs.size() ? in.anchorJpegs.get(i) : null;
        if (jpg != null) store.writeFile(m.id, a.photo, jpg);
        else a.photo = null;
        m.anchors.add(a);
      }
    }
    if (in.parent != null) {
      for (ScanMeta.Anchor a : in.parent.anchors) {
        if (a.photo == null) continue;
        File src = store.file(in.parent.id, a.photo);
        if (src.isFile()) store.writeFile(m.id, a.photo, ScanStore.read(src));
      }
      File st = store.file(in.parent.id, "start.jpg");
      if (st.isFile()) store.writeFile(m.id, "start.jpg", ScanStore.read(st));
    } else if (in.startJpeg != null) {
      store.writeFile(m.id, "start.jpg", in.startJpeg);
    }
    store.writeFile(m.id, "mesh.glb", Glb.write(fin.meshes, m.name));
    // Texturas: fotos clave de esta sesión + las del escaneo continuado.
    List<Texturer.Keyframe> kfs = new java.util.ArrayList<>();
    List<byte[]> jpegs = new java.util.ArrayList<>();
    if (in.parent != null) loadKeyframes(store, in.parent.id, kfs, jpegs);
    if (in.keyframes != null) { kfs.addAll(in.keyframes); jpegs.addAll(in.keyframeJpegs); }
    if (!kfs.isEmpty()) {
      List<MeshPart> tex = Texturer.texture(fin.meshes, kfs);
      store.writeFile(m.id, "textured.glb", Glb.write(tex, jpegs, m.name));
      saveKeyframes(store, m.id, kfs, jpegs);
      m.keyframes = kfs.size();
    }
    store.writeFile(m.id, "volume.bin", f.volume.export());
    store.writeMeta(m); // el último: un escaneo sin meta.json no aparece en la lista
    return m;
  }
}
