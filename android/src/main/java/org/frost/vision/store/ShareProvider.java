package org.frost.vision.store;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Comparte archivos de cacheDir/share/ con otras apps (sin AndroidX FileProvider):
 * content://org.frost.vision.share/&lt;nombre&gt;. Solo lectura; los permisos se conceden por Intent.
 */
public final class ShareProvider extends ContentProvider {
  public static final String AUTHORITY = "org.frost.vision.share";

  public static File shareDir(Context ctx) {
    File d = new File(ctx.getCacheDir(), "share");
    d.mkdirs();
    return d;
  }

  /** Escribe los datos en cacheDir/share/name y devuelve su Uri. */
  public static Uri publish(Context ctx, String name, byte[] data) throws IOException {
    File d = shareDir(ctx);
    File[] old = d.listFiles();
    if (old != null) for (File f : old) f.delete(); // solo el último compartido
    ScanStore.write(new File(d, name), data);
    return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name).build();
  }

  private File fileFor(Uri uri) throws FileNotFoundException {
    String name = uri.getLastPathSegment();
    if (name == null || name.contains("/") || name.startsWith(".")) throw new FileNotFoundException(String.valueOf(uri));
    File f = new File(shareDir(getContext()), name);
    if (!f.isFile()) throw new FileNotFoundException(String.valueOf(uri));
    return f;
  }

  @Override public boolean onCreate() { return true; }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new SecurityException("solo lectura");
    return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override
  public String getType(Uri uri) {
    String n = String.valueOf(uri.getLastPathSegment()).toLowerCase();
    if (n.endsWith(".glb")) return "model/gltf-binary";
    if (n.endsWith(".html")) return "text/html";
    return "application/octet-stream";
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    File f;
    try { f = fileFor(uri); } catch (FileNotFoundException e) { return null; }
    MatrixCursor c = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
    c.addRow(new Object[] {f.getName(), f.length()});
    return c;
  }

  @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
  @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
  @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
