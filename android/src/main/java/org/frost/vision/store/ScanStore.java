package org.frost.vision.store;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.frost.vision.core.Glb;
import org.frost.vision.core.MeshPart;
import org.frost.vision.core.Params;
import org.frost.vision.core.ScanMeta;
import org.frost.vision.core.Volume;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Escaneos en archivos: filesDir/scans/&lt;id&gt;/{meta.json, mesh.glb, volume.bin, start.jpg, anchorN.jpg}.
 * Grabaciones: filesDir/recordings/ (MP4 de ARCore + registro de frames propio .frames).
 * Se escribe a un .tmp y se renombra, para no dejar archivos a medias.
 */
public final class ScanStore {
  public final File root, recordings;

  public ScanStore(Context ctx) {
    root = new File(ctx.getFilesDir(), "scans");
    recordings = new File(ctx.getFilesDir(), "recordings");
    root.mkdirs();
    recordings.mkdirs();
  }

  public static String newId() {
    return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
  }

  public static String defaultName(long ms) {
    return "Escaneo " + new SimpleDateFormat("d MMM HH:mm", new Locale("es")).format(new Date(ms));
  }

  public File dir(String id) { return new File(root, id); }

  public List<ScanMeta> list() {
    List<ScanMeta> out = new ArrayList<>();
    File[] dirs = root.listFiles();
    if (dirs == null) return out;
    for (File d : dirs) {
      File m = new File(d, "meta.json");
      if (!m.isFile()) continue;
      try {
        out.add(ScanMeta.fromJson(new String(read(m), "UTF-8")));
      } catch (Exception ignored) {
        // carpeta dañada: se ignora
      }
    }
    out.sort((a, b) -> Long.compare(b.created, a.created));
    return out;
  }

  public ScanMeta meta(String id) throws IOException {
    return ScanMeta.fromJson(new String(read(new File(dir(id), "meta.json")), "UTF-8"));
  }

  public void writeMeta(ScanMeta m) throws IOException {
    File d = dir(m.id);
    d.mkdirs();
    write(new File(d, "meta.json"), m.toJson().getBytes("UTF-8"));
  }

  public void writeFile(String id, String name, byte[] data) throws IOException {
    File d = dir(id);
    d.mkdirs();
    write(new File(d, name), data);
  }

  public File file(String id, String name) { return new File(dir(id), name); }

  public List<MeshPart> meshes(String id) throws IOException {
    return Glb.read(read(file(id, "mesh.glb")));
  }

  public Volume volume(String id, Params params, boolean freeze) throws IOException {
    try (InputStream in = new FileInputStream(file(id, "volume.bin"))) {
      return Volume.load(in, params, freeze);
    }
  }

  public Bitmap photo(String id, String name) {
    if (name == null) return null;
    File f = file(id, name);
    return f.isFile() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
  }

  public void rename(String id, String name) throws IOException {
    ScanMeta m = meta(id);
    m.name = name;
    writeMeta(m);
  }

  public void delete(String id) {
    deleteTree(dir(id));
  }

  static void deleteTree(File f) {
    File[] kids = f.listFiles();
    if (kids != null) for (File k : kids) deleteTree(k);
    f.delete();
  }

  public static byte[] read(File f) throws IOException {
    try (InputStream in = new FileInputStream(f)) {
      return readAll(in);
    }
  }

  public static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[1 << 16];
    int n;
    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
    return bos.toByteArray();
  }

  public static void write(File f, byte[] data) throws IOException {
    File tmp = new File(f.getPath() + ".tmp");
    try (FileOutputStream out = new FileOutputStream(tmp)) {
      out.write(data);
      out.getFD().sync();
    }
    if (f.exists() && !f.delete()) throw new IOException("no se pudo reemplazar " + f);
    if (!tmp.renameTo(f)) throw new IOException("no se pudo renombrar " + tmp);
  }

  public static byte[] jpeg(Bitmap b, int quality) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    b.compress(Bitmap.CompressFormat.JPEG, quality, bos);
    return bos.toByteArray();
  }

  /** Importa un .glb (escaneo de la web o exportado) como escaneo nuevo, solo para ver. */
  public ScanMeta importGlb(byte[] glb, String name) throws IOException {
    List<MeshPart> parts = Glb.read(glb);
    if (parts.isEmpty()) throw new IOException("El archivo no contiene mallas");
    ScanMeta m = new ScanMeta();
    m.id = newId();
    while (dir(m.id).exists()) m.id = m.id + "b";
    m.name = name;
    m.created = System.currentTimeMillis();
    m.imported = true;
    for (MeshPart p : parts) { m.verts += p.vertexCount(); m.tris += p.triangleCount(); }
    writeFile(m.id, "mesh.glb", glb);
    writeMeta(m);
    return m;
  }

  public File[] recordingFiles(String suffix) {
    File[] f = recordings.listFiles((d, n) -> n.endsWith(suffix));
    if (f == null) return new File[0];
    java.util.Arrays.sort(f, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
    return f;
  }
}
