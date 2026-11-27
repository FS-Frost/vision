package org.frost.vision.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.frost.vision.core.Cloud;
import org.frost.vision.core.Collider;
import org.frost.vision.core.Fusion;
import org.frost.vision.core.MeshPart;
import org.frost.vision.core.Reloc;
import org.frost.vision.core.ScanMeta;
import org.frost.vision.core.ShareHtml;
import org.frost.vision.gpu.Gl;
import org.frost.vision.gpu.MeshRenderer;
import org.frost.vision.store.ScanStore;
import org.frost.vision.store.ShareProvider;

import java.io.OutputStream;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Visor de un escaneo guardado: orbitar o caminar (con colisiones), color o sombreado, exportar y compartir. */
public final class ViewerActivity extends Activity implements GLSurfaceView.Renderer {
  static final int REQ_EXPORT = 2;
  private ScanStore store;
  private ScanMeta meta;
  private GLSurfaceView gl;
  private MeshRenderer renderer;
  private volatile List<MeshPart> meshes;
  private TextView info;
  private Button walkBtn, colorBtn;
  private JoystickView joy;

  // cámara (protegida por "this")
  private float[] center = {0, 1, 0};
  private float yaw = 0.6f, pitch = -0.5f, dist = 5f, radius = 3f;
  private boolean walk, color = true;
  private float px, pz, eyeY = 1.6f, floorY = 0;
  private Collider collider;
  private long lastNs;
  private float aspect = 1;
  private float lastX, lastY, lastSpan, lastMidX, lastMidY;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    store = new ScanStore(this);
    String id = getIntent().getStringExtra("id");
    try {
      meta = store.meta(id);
    } catch (Exception e) {
      Toast.makeText(this, "No se pudo abrir el escaneo", Toast.LENGTH_LONG).show();
      finish();
      return;
    }
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Ui.BG);
    gl = new GLSurfaceView(this);
    gl.setEGLContextClientVersion(3);
    gl.setEGLConfigChooser(8, 8, 8, 8, 24, 0);
    gl.setRenderer(this);
    gl.setOnTouchListener((v, e) -> { touch(e); return true; });
    root.addView(gl);

    LinearLayout top = Ui.panel(this);
    top.addView(Ui.label(this, meta.name, 16, Ui.TEXT, true));
    info = Ui.label(this, "Cargando…", 12, Ui.MUTED, false);
    top.addView(info);
    FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
    int m = Ui.dp(this, 12);
    tlp.setMargins(m, m + Ui.dp(this, 20), m, 0);
    root.addView(top, tlp);

    LinearLayout bottom = Ui.panel(this);
    walkBtn = Ui.button(this, "Caminar", Ui.BTN, v -> toggleWalk());
    colorBtn = Ui.button(this, "Sombreado", Ui.BTN, v -> { synchronized (this) { color = !color; } colorBtn.setText(color ? "Sombreado" : "Color"); });
    Button cont = Ui.button(this, "Continuar", Ui.ACCENT, v -> startActivity(new Intent(this, ScanActivity.class).putExtra("continue", meta.id)));
    cont.setEnabled(meta.hasVolume);
    if (!meta.hasVolume) cont.setAlpha(0.4f);
    bottom.addView(Ui.buttonRow(this, walkBtn, colorBtn, cont));
    LinearLayout r2 = Ui.buttonRow(this,
        Ui.button(this, "Exportar .glb", Ui.BTN, v -> exportGlb()),
        Ui.button(this, "Compartir .html", Ui.BTN, v -> shareHtml()));
    bottom.addView(r2, Ui.margins(this, 0, 8, 0, 0));
    bottom.addView(Ui.buttonRow(this,
        Ui.button(this, "Renombrar", Ui.BTN, v -> rename()),
        Ui.button(this, "Borrar", Ui.DANGER, v -> delete())), Ui.margins(this, 0, 8, 0, 0));
    FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
    blp.setMargins(m, 0, m, m + Ui.dp(this, 16));
    root.addView(bottom, blp);

    joy = new JoystickView(this);
    int js = Ui.dp(this, 130);
    FrameLayout.LayoutParams jlp = new FrameLayout.LayoutParams(js, js, Gravity.BOTTOM | Gravity.START);
    jlp.setMargins(m, 0, 0, Ui.dp(this, 250));
    joy.setVisibility(android.view.View.GONE);
    root.addView(joy, jlp);
    setContentView(root);
    load();
  }

  private void load() {
    new Thread(() -> {
      try {
        List<MeshPart> parts = store.meshes(meta.id);
        float[] min = {1e9f, 1e9f, 1e9f}, max = {-1e9f, -1e9f, -1e9f};
        int tris = 0;
        for (MeshPart p : parts) {
          tris += p.triangleCount();
          for (int i = 0; i < p.positions.length; i++) {
            min[i % 3] = Math.min(min[i % 3], p.positions[i]);
            max[i % 3] = Math.max(max[i % 3], p.positions[i]);
          }
        }
        Cloud cloud = Fusion.cloudFrom(parts);
        float fy = Reloc.estimateFloor(cloud, Float.isNaN(meta.floorY) ? min[1] : meta.floorY);
        Collider col = new Collider(parts, fy);
        synchronized (this) {
          center = new float[] {(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2};
          radius = Math.max(0.5f, (float) Math.sqrt((max[0] - min[0]) * (max[0] - min[0]) + (max[1] - min[1]) * (max[1] - min[1]) + (max[2] - min[2]) * (max[2] - min[2])) / 2);
          dist = radius * 1.8f;
          floorY = fy;
          eyeY = fy + 1.6f;
          collider = col;
          float[] free = col.nearestFree(center[0], center[2], 3f);
          px = free != null ? free[0] : center[0];
          pz = free != null ? free[1] : center[2];
        }
        meshes = parts;
        if (renderer != null) renderer.enqueue(parts);
        int ft = tris;
        runOnUiThread(() -> info.setText(String.format(java.util.Locale.US, "%,d triángulos · suelo %s", ft, Ui.meters(fy)).replace(',', '.')
            + (meta.imported ? " · importado (solo ver)" : "")));
      } catch (Exception e) {
        runOnUiThread(() -> info.setText("No se pudo cargar la malla: " + e.getMessage()));
      }
    }).start();
  }

  private void toggleWalk() {
    synchronized (this) {
      walk = !walk;
      if (walk) { pitch = 0; yaw = 0; }
      else { pitch = -0.5f; }
    }
    walkBtn.setText(walk ? "Orbitar" : "Caminar");
    joy.setVisibility(walk ? android.view.View.VISIBLE : android.view.View.GONE);
  }

  private void touch(MotionEvent e) {
    int n = e.getPointerCount();
    float x = e.getX(0), y = e.getY(0);
    float midX = x, midY = y, span = 0;
    if (n >= 2) {
      midX = (e.getX(0) + e.getX(1)) / 2; midY = (e.getY(0) + e.getY(1)) / 2;
      span = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }
    int a = e.getActionMasked();
    if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN || a == MotionEvent.ACTION_POINTER_UP) {
      lastX = x; lastY = y; lastSpan = span; lastMidX = midX; lastMidY = midY;
      return;
    }
    if (a != MotionEvent.ACTION_MOVE) return;
    float k = 3.5f / Math.max(1, gl.getWidth());
    synchronized (this) {
      if (n == 1) {
        float dx = x - lastX, dy = y - lastY;
        yaw -= dx * k;
        pitch = Math.max(-1.45f, Math.min(1.45f, pitch - dy * k));
      } else if (!walk && n >= 2 && lastSpan > 0) {
        dist = Math.max(0.3f, Math.min(radius * 6, dist * lastSpan / Math.max(1, span)));
        float pan = dist * 1.2f / Math.max(1, gl.getHeight());
        float dx = midX - lastMidX, dy = midY - lastMidY;
        float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
        center[0] -= (dx * cy) * pan;
        center[2] -= (-dx * sy) * pan;
        center[1] += dy * pan;
      }
    }
    lastX = x; lastY = y; lastSpan = span; lastMidX = midX; lastMidY = midY;
  }

  @Override
  public void onSurfaceCreated(GL10 unused, EGLConfig config) {
    renderer = new MeshRenderer(getAssets());
    if (meshes != null) renderer.enqueue(meshes);
    lastNs = System.nanoTime();
  }

  @Override
  public void onSurfaceChanged(GL10 unused, int w, int h) {
    GLES20.glViewport(0, 0, w, h);
    aspect = w / (float) Math.max(1, h);
  }

  @Override
  public void onDrawFrame(GL10 unused) {
    long now = System.nanoTime();
    float dt = Math.min(0.1f, (now - lastNs) / 1e9f);
    lastNs = now;
    GLES20.glClearColor(0.06f, 0.067f, 0.083f, 1);
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
    float[] view = new float[16], proj = new float[16], vp = new float[16];
    boolean col;
    synchronized (this) {
      col = color;
      float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
      float fx = -(float) Math.sin(yaw) * cp, fz = -(float) Math.cos(yaw) * cp;
      if (walk) {
        float speed = 1.2f * dt;
        float jx = joy.jx, jy = joy.jy;
        float fwx = -(float) Math.sin(yaw), fwz = -(float) Math.cos(yaw);
        float dx = (fwx * jy + (-fwz) * jx) * speed, dz = (fwz * jy + fwx * jx) * speed;
        if (collider != null && (dx != 0 || dz != 0)) {
          float[] np = collider.move(px, pz, dx, dz);
          px = np[0]; pz = np[1];
        } else { px += dx; pz += dz; }
        Matrix.setLookAtM(view, 0, px, eyeY, pz, px + fx, eyeY + sp, pz + fz, 0, 1, 0);
        Matrix.perspectiveM(proj, 0, 70, aspect, 0.05f, 100f);
      } else {
        float ex = center[0] - fx * dist, ey = center[1] - sp * dist, ez = center[2] - fz * dist;
        Matrix.setLookAtM(view, 0, ex, ey, ez, center[0], center[1], center[2], 0, 1, 0);
        Matrix.perspectiveM(proj, 0, 60, aspect, Math.max(0.01f, dist / 200), dist + radius * 4);
      }
    }
    Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
    if (renderer != null) renderer.draw(vp, null, col ? MeshRenderer.MODE_COLOR : MeshRenderer.MODE_SHADED, 1f, 0);
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (gl != null) gl.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (gl != null) gl.onResume();
  }

  private String fileBase() {
    String s = meta.name.replaceAll("[^\\p{L}\\p{N} _-]", "").trim().replace(' ', '-');
    return s.isEmpty() ? "escaneo" : s;
  }

  private void exportGlb() {
    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("model/gltf-binary");
    i.putExtra(Intent.EXTRA_TITLE, fileBase() + ".glb");
    startActivityForResult(i, REQ_EXPORT);
  }

  @Override
  protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req != REQ_EXPORT || res != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    new Thread(() -> {
      String msg;
      try (OutputStream out = getContentResolver().openOutputStream(uri)) {
        out.write(ScanStore.read(store.file(meta.id, "mesh.glb")));
        msg = "Exportado .glb";
      } catch (Exception e) {
        msg = "No se pudo exportar: " + e.getMessage();
      }
      String fm = msg;
      runOnUiThread(() -> Toast.makeText(this, fm, Toast.LENGTH_LONG).show());
    }).start();
  }

  private void shareHtml() {
    new Thread(() -> {
      try {
        byte[] glb = ScanStore.read(store.file(meta.id, "mesh.glb"));
        String html = ShareHtml.build(Gl.asset(getAssets(), "share/viewer.html"), glb, meta.name);
        Uri uri = ShareProvider.publish(this, fileBase() + ".html", html.getBytes("UTF-8"));
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/html");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, meta.name);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        runOnUiThread(() -> startActivity(Intent.createChooser(send, "Compartir escaneo (.html)")));
      } catch (Exception e) {
        runOnUiThread(() -> Toast.makeText(this, "No se pudo compartir: " + e.getMessage(), Toast.LENGTH_LONG).show());
      }
    }).start();
  }

  private void rename() {
    EditText input = new EditText(this);
    input.setText(meta.name);
    input.setSelectAllOnFocus(true);
    new AlertDialog.Builder(this).setTitle("Renombrar").setView(input)
        .setPositiveButton("Guardar", (d, w) -> {
          String n = input.getText().toString().trim();
          if (n.isEmpty()) return;
          try {
            store.rename(meta.id, n);
            meta.name = n;
            recreate();
          } catch (Exception e) {
            Toast.makeText(this, "No se pudo renombrar: " + e.getMessage(), Toast.LENGTH_LONG).show();
          }
        })
        .setNegativeButton("Cancelar", null).show();
  }

  private void delete() {
    new AlertDialog.Builder(this).setTitle("Borrar escaneo")
        .setMessage("Se borrará «" + meta.name + "» (malla, volumen y fotos). No se puede deshacer.")
        .setPositiveButton("Borrar", (d, w) -> { store.delete(meta.id); finish(); })
        .setNegativeButton("Cancelar", null).show();
  }
}
