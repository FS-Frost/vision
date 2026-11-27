package org.frost.vision.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.frost.vision.core.ScanMeta;
import org.frost.vision.core.ShareHtml;
import org.frost.vision.store.ScanStore;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Inicio: escaneos guardados, nuevo escaneo, importar, grabaciones, pruebas y diagnóstico. */
public final class HomeActivity extends Activity {
  static final int REQ_IMPORT = 1;
  private ScanStore store;
  private LinearLayout list;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    store = new ScanStore(this);
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(Ui.BG);
    LinearLayout root = Ui.column(this);
    int p = Ui.dp(this, 16);
    root.setPadding(p, p, p, p);
    scroll.addView(root);

    root.addView(Ui.label(this, "Vision", 26, Ui.TEXT, true));
    root.addView(Ui.label(this, "Escáner 3D de habitaciones (ARCore, profundidad raw, fusión en GPU)", 13, Ui.MUTED, false),
        Ui.margins(this, 0, 2, 0, 14));

    root.addView(Ui.button(this, "Nuevo escaneo", Ui.ACCENT, v -> startActivity(new Intent(this, ScanActivity.class))),
        Ui.margins(this, 0, 0, 0, 8));
    root.addView(Ui.buttonRow(this,
        Ui.button(this, "Importar .glb / .html", Ui.BTN, v -> pickImport()),
        Ui.button(this, "Grabaciones", Ui.BTN, v -> recordings())), Ui.margins(this, 0, 0, 0, 8));
    root.addView(Ui.buttonRow(this,
        Ui.button(this, "Diagnóstico", Ui.BTN, v -> startActivity(new Intent(this, DiagActivity.class))),
        Ui.button(this, "Pruebas GPU/CPU", Ui.BTN, v -> startActivity(new Intent(this, TestsActivity.class)))),
        Ui.margins(this, 0, 0, 0, 16));

    root.addView(Ui.label(this, "Escaneos guardados", 16, Ui.TEXT, true), Ui.margins(this, 0, 0, 0, 8));
    list = Ui.column(this);
    root.addView(list);
    setContentView(scroll);
    handleIncoming(getIntent());
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    handleIncoming(intent);
  }

  @Override
  protected void onResume() {
    super.onResume();
    refresh();
  }

  private void refresh() {
    list.removeAllViews();
    List<ScanMeta> scans = store.list();
    if (scans.isEmpty()) {
      list.addView(Ui.label(this, "Todavía no hay escaneos. Pulsa «Nuevo escaneo» y recorre la habitación despacio.", 14, Ui.MUTED, false));
      return;
    }
    SimpleDateFormat fmt = new SimpleDateFormat("d MMM yyyy, HH:mm", new Locale("es"));
    for (ScanMeta m : scans) {
      LinearLayout card = Ui.panel(this);
      card.addView(Ui.label(this, m.name, 16, Ui.TEXT, true));
      StringBuilder sub = new StringBuilder(fmt.format(new Date(m.created)));
      sub.append(" · ").append(String.format(Locale.US, "%,d", m.tris).replace(',', '.')).append(" triángulos");
      if (m.imported) sub.append(" · importado");
      if (m.parent != null) sub.append(" · ampliación");
      if (!m.anchors.isEmpty()) sub.append(" · ").append(m.anchors.size()).append(m.anchors.size() == 1 ? " ancla" : " anclas");
      card.addView(Ui.label(this, sub.toString(), 12, Ui.MUTED, false));
      card.setOnClickListener(v -> startActivity(new Intent(this, ViewerActivity.class).putExtra("id", m.id)));
      list.addView(card, Ui.margins(this, 0, 0, 0, 8));
    }
  }

  private void pickImport() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("*/*");
    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"model/gltf-binary", "application/octet-stream", "text/html"});
    startActivityForResult(i, REQ_IMPORT);
  }

  @Override
  protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req == REQ_IMPORT && res == RESULT_OK && data != null && data.getData() != null) importUri(data.getData());
  }

  private void handleIncoming(Intent intent) {
    if (intent == null) return;
    Uri uri = null;
    if (Intent.ACTION_VIEW.equals(intent.getAction())) uri = intent.getData();
    else if (Intent.ACTION_SEND.equals(intent.getAction())) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
    if (uri != null) {
      setIntent(new Intent(this, HomeActivity.class));
      importUri(uri);
    }
  }

  private String displayName(Uri uri) {
    try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst()) return c.getString(0);
    } catch (Exception ignored) {
      // sin nombre
    }
    String s = uri.getLastPathSegment();
    return s != null ? s : "importado";
  }

  private void importUri(Uri uri) {
    String name = displayName(uri);
    Toast.makeText(this, "Importando " + name + "…", Toast.LENGTH_SHORT).show();
    new Thread(() -> {
      String msg;
      try (InputStream in = getContentResolver().openInputStream(uri)) {
        byte[] data = ScanStore.readAll(in);
        boolean glb = data.length >= 4 && data[0] == 'g' && data[1] == 'l' && data[2] == 'T' && data[3] == 'F';
        if (!glb) {
          data = ShareHtml.extractGlb(new String(data, "UTF-8"));
          if (data == null) throw new IllegalArgumentException("El archivo no es un .glb ni un .html de Vision");
        }
        ScanMeta m = store.importGlb(data, name.replaceAll("(?i)\\.(glb|html?)$", ""));
        msg = "Importado: " + m.name;
        runOnUiThread(() -> startActivity(new Intent(this, ViewerActivity.class).putExtra("id", m.id)));
      } catch (Exception e) {
        msg = "No se pudo importar: " + e.getMessage();
      }
      String fm = msg;
      runOnUiThread(() -> { Toast.makeText(this, fm, Toast.LENGTH_LONG).show(); refresh(); });
    }).start();
  }

  private void recordings() {
    File[] mp4 = store.recordingFiles(".mp4");
    if (mp4.length == 0) {
      new AlertDialog.Builder(this).setTitle("Grabaciones")
          .setMessage("No hay grabaciones. En «Nuevo escaneo», pulsa «Grabar» para guardar la sesión (MP4 de ARCore y registro de frames) y reproducirla después.")
          .setPositiveButton("Cerrar", null).show();
      return;
    }
    String[] names = new String[mp4.length];
    for (int i = 0; i < mp4.length; i++) {
      File fr = new File(mp4[i].getPath().replaceAll("\\.mp4$", ".frames"));
      names[i] = mp4[i].getName() + String.format(Locale.US, "  (%.1f MB%s)", mp4[i].length() / 1048576.0,
          fr.isFile() ? ", con frames" : "");
    }
    new AlertDialog.Builder(this).setTitle("Reproducir grabación")
        .setItems(names, (d, which) -> startActivity(new Intent(this, ScanActivity.class)
            .putExtra("playback", mp4[which].getAbsolutePath())))
        .setNegativeButton("Cerrar", null).show();
  }

  static TextView centered(Activity a, String text) {
    TextView t = Ui.label(a, text, 14, Ui.MUTED, false);
    t.setGravity(Gravity.CENTER);
    t.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
    return t;
  }
}
