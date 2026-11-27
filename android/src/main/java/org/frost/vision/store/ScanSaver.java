package org.frost.vision.store;

import org.frost.vision.core.Cloud;
import org.frost.vision.core.Fusion;
import org.frost.vision.core.Glb;
import org.frost.vision.core.MeshPart;
import org.frost.vision.core.Reloc;
import org.frost.vision.core.ScanMeta;

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
    store.writeFile(m.id, "volume.bin", f.volume.export());
    store.writeMeta(m); // el último: un escaneo sin meta.json no aparece en la lista
    return m;
  }
}
