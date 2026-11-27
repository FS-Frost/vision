package org.frost.vision.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraConfig;
import com.google.ar.core.CameraConfigFilter;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.Plane;
import com.google.ar.core.PlaybackStatus;
import com.google.ar.core.RecordingConfig;
import com.google.ar.core.RecordingStatus;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import org.frost.vision.ar.ArCapture;
import org.frost.vision.ar.FusionWorker;
import org.frost.vision.core.Cloud;
import org.frost.vision.core.DepthFrame;
import org.frost.vision.core.FrameLog;
import org.frost.vision.core.Fusion;
import org.frost.vision.core.Glb;
import org.frost.vision.core.Mat4;
import org.frost.vision.core.MeshPart;
import org.frost.vision.core.Params;
import org.frost.vision.core.Reloc;
import org.frost.vision.core.ScanMeta;
import org.frost.vision.core.Volume;
import org.frost.vision.gpu.BackgroundRenderer;
import org.frost.vision.gpu.Gl;
import org.frost.vision.gpu.MeshRenderer;
import org.frost.vision.store.ScanStore;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Escanear (nuevo, desde una grabación o continuando uno guardado).
 *
 * Continuar (reglas heredadas de la web, medidas en el g54):
 * - Método principal: volver a marcar las anclas -> solveAnchors -> ICP local -> confirmación.
 * - Respaldo: barrido guiado (6-15 s, ≥ 50 % de direcciones) -> búsqueda global (Reloc.relocalize).
 * - La confirmación es SIEMPRE del usuario, con semáforo (±15 cm con la profundidad en vivo).
 * - El volumen guardado queda congelado: solo se añaden zonas nuevas. Se guarda como escaneo nuevo.
 */
public final class ScanActivity extends Activity implements GLSurfaceView.Renderer {
  static final String TAG = "VISION";
  enum Phase { SCANNING, ANCHORS, SWEEP, SEARCHING, CONFIRM, SAVING }

  // ---------------------------------------------------------------- estado
  private ScanStore store;
  private GLSurfaceView gl;
  private Session session;
  private boolean installRequested;
  private BackgroundRenderer bg;
  private MeshRenderer meshes;
  private FusionWorker worker;
  private final ArCapture capture = new ArCapture();
  private volatile Phase phase = Phase.SCANNING;
  private String playback, continueId;
  private ScanMeta saved;
  private Cloud savedCloud;
  private volatile boolean savedReady;
  private int viewW = 1, viewH = 1;
  private volatile boolean colorMode;

  // datos de la sesión (hilo GL salvo indicación)
  private volatile DepthFrame lastFrame;
  private volatile float[] crosshair;       // {x,y,z,dist} marco de la sesión
  private volatile float camY, sessionFloor = Float.NaN;
  private float[] sessionStartPose;
  private Bitmap startPhoto;
  private final List<ScanMeta.Anchor> newAnchors = new ArrayList<>();
  private final List<Bitmap> newAnchorPhotos = new ArrayList<>();
  private volatile boolean anchorRequested;
  private long lastCaptureMs, lastHudMs, lastCrossMs, lastSweepMs, lastLiveMs;
  private int frameCount;
  private long fpsT0;
  private float fps;

  // continuar
  private final List<DepthFrame> sweepFrames = new ArrayList<>();
  private final List<float[]> sessionAnchorPts = new ArrayList<>();
  private int anchorIndex;
  private boolean anchorWarned;
  private long sweepStartMs;
  private volatile List<Reloc.Candidate> candidates = new ArrayList<>();
  private volatile int candIndex;
  private volatile double[] liveScores = new double[0];
  private volatile boolean blink;
  private String anchorsNote = "";

  // grabación
  private boolean recording;
  private File recFile;

  // ---------------------------------------------------------------- vistas
  private TextView hud, title, hint;
  private LinearLayout bar, anchorPanel;
  private ImageView anchorPhoto;
  private TextView anchorText;
  private Overlays.Crosshair cross;
  private Overlays.Coverage coverage;
  private Button btnAnchor, btnRecord, btnColor, btnFinish;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    store = new ScanStore(this);
    playback = getIntent().getStringExtra("playback");
    continueId = getIntent().getStringExtra("continue");

    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(0xFF000000);
    gl = new GLSurfaceView(this);
    gl.setPreserveEGLContextOnPause(true);
    gl.setEGLContextClientVersion(3);
    gl.setEGLConfigChooser(8, 8, 8, 8, 24, 0);
    gl.setRenderer(this);
    gl.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    root.addView(gl);
    FrameLayout overlay = new FrameLayout(this);
    root.addView(overlay);
    Ui.insets(overlay, 0);

    cross = new Overlays.Crosshair(this);
    root.addView(cross, 1, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

    int m = Ui.dp(this, 10);
    LinearLayout top = Ui.panel(this);
    title = Ui.label(this, continueId != null ? "Continuar escaneo" : playback != null ? "Reproducción" : "Nuevo escaneo", 15, Ui.TEXT, true);
    top.addView(title);
    hint = Ui.label(this, "Mueve el teléfono despacio para que ARCore empiece a seguir la posición.", 13, Ui.TEXT, false);
    top.addView(hint);
    hud = Ui.label(this, "", 11, Ui.MUTED, false);
    hud.setTypeface(android.graphics.Typeface.MONOSPACE);
    top.addView(hud);
    FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
    tlp.setMargins(m, m, m, 0);
    overlay.addView(top, tlp);

    coverage = new Overlays.Coverage(this);
    int cs = Ui.dp(this, 84);
    FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(cs, cs, Gravity.END | Gravity.CENTER_VERTICAL);
    clp.setMargins(0, 0, m, 0);
    overlay.addView(coverage, clp);

    anchorPanel = Ui.panel(this);
    anchorPhoto = new ImageView(this);
    anchorPhoto.setAdjustViewBounds(true);
    anchorPanel.addView(anchorPhoto, new LinearLayout.LayoutParams(Ui.dp(this, 120), LinearLayout.LayoutParams.WRAP_CONTENT));
    anchorText = Ui.label(this, "", 12, Ui.TEXT, false);
    anchorPanel.addView(anchorText, new LinearLayout.LayoutParams(Ui.dp(this, 120), LinearLayout.LayoutParams.WRAP_CONTENT));
    anchorPanel.setVisibility(View.GONE);
    FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
    alp.setMargins(m, Ui.dp(this, 40), 0, 0);
    overlay.addView(anchorPanel, alp);

    bar = Ui.column(this);
    FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
    blp.setMargins(m, 0, m, m);
    overlay.addView(bar, blp);
    setContentView(root);

    Params params = new Params();
    worker = new FusionWorker(getAssets(), params, true, parts -> {
      if (meshes != null) meshes.enqueue(parts);
    });
    if (continueId != null) loadSaved();
    showScanningBar();
    if (continueId != null) enterAnchorsOrSweep();
  }

  // ---------------------------------------------------------------- barras de botones

  private void setBar(Button... rows) {
    bar.removeAllViews();
    LinearLayout row = null;
    int n = 0;
    for (Button b : rows) {
      if (b == null) { row = null; n = 0; continue; }
      if (row == null) {
        row = Ui.row(this);
        bar.addView(row, Ui.margins(this, 0, bar.getChildCount() > 0 ? 8 : 0, 0, 0));
      }
      LinearLayout.LayoutParams lp = Ui.weight(1);
      if (n++ > 0) lp.leftMargin = Ui.dp(this, 8);
      row.addView(b, lp);
    }
  }

  private void showScanningBar() {
    btnAnchor = Ui.button(this, "Marcar ancla", Ui.BTN, v -> anchorRequested = true);
    btnColor = Ui.button(this, colorMode ? "Malla azul" : "Color", Ui.BTN, v -> {
      colorMode = !colorMode;
      btnColor.setText(colorMode ? "Malla azul" : "Color");
    });
    btnRecord = Ui.button(this, recording ? "Parar" : "Grabar", recording ? Ui.DANGER : Ui.BTN, v -> toggleRecording());
    btnFinish = Ui.button(this, "Terminar", Ui.ACCENT, v -> finishScan());
    if (playback != null) btnRecord.setEnabled(false);
    setBar(btnAnchor, btnColor, null, btnRecord, btnFinish);
    cross.setVisibility(View.VISIBLE);
  }

  // ---------------------------------------------------------------- ARCore

  @Override
  protected void onResume() {
    super.onResume();
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.CAMERA}, 1);
      return;
    }
    if (session == null && !createSession()) return;
    try {
      session.resume();
    } catch (Exception e) {
      fail("No se pudo abrir la cámara: " + e.getMessage());
      return;
    }
    gl.onResume();
  }

  @Override
  public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
    super.onRequestPermissionsResult(req, perms, res);
    if (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED) fail("Sin permiso de cámara no se puede escanear.");
  }

  private boolean createSession() {
    try {
      ArCoreApk.InstallStatus st = ArCoreApk.getInstance().requestInstall(this, !installRequested);
      if (st == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
        installRequested = true;
        return false;
      }
      session = new Session(this);
      CameraConfigFilter filter = new CameraConfigFilter(session)
          .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
          .setFacingDirection(CameraConfig.FacingDirection.BACK);
      List<CameraConfig> cfgs = session.getSupportedCameraConfigs(filter);
      CameraConfig best = null;
      for (CameraConfig c : cfgs) {
        if (best == null || area(c.getImageSize()) > area(best.getImageSize())) best = c;
      }
      if (best != null) {
        session.setCameraConfig(best);
        Log.i(TAG, "cámara: imagen CPU " + best.getImageSize() + ", textura " + best.getTextureSize() + ", fps " + best.getFpsRange());
      }
      if (!session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) {
        fail("Este dispositivo no da profundidad raw de ARCore.");
        session.close();
        session = null;
        return false;
      }
      Config cfg = new Config(session);
      cfg.setDepthMode(Config.DepthMode.RAW_DEPTH_ONLY);
      cfg.setFocusMode(Config.FocusMode.AUTO);
      cfg.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL);
      cfg.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
      cfg.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
      session.configure(cfg);
      if (playback != null) session.setPlaybackDatasetUri(Uri.fromFile(new File(playback)));
      return true;
    } catch (UnavailableUserDeclinedInstallationException e) {
      fail("Hace falta instalar «Servicios de Google Play para RA».");
    } catch (Exception e) {
      fail("ARCore no disponible: " + e.getMessage());
    }
    session = null;
    return false;
  }

  private static long area(android.util.Size s) { return (long) s.getWidth() * s.getHeight(); }

  private void fail(String msg) {
    runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Vision").setMessage(msg)
        .setPositiveButton("Cerrar", (d, w) -> finish()).setCancelable(false).show());
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (recording) toggleRecording();
    gl.onPause();
    if (session != null) session.pause();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (worker != null) worker.release();
    if (session != null) session.close();
    session = null;
  }

  // ---------------------------------------------------------------- render (hilo GL)

  @Override
  public void onSurfaceCreated(GL10 unused, EGLConfig config) {
    GLES20.glClearColor(0, 0, 0, 1);
    bg = new BackgroundRenderer(getAssets());
    meshes = new MeshRenderer(getAssets());
    // Contexto nuevo: se vuelve a enviar la malla del espejo (el escaneo cargado, si se continúa).
    if (worker != null) worker.run(() -> meshes.enqueue(worker.fusion.allMeshes()));
  }

  @Override
  public void onSurfaceChanged(GL10 unused, int w, int h) {
    GLES20.glViewport(0, 0, w, h);
    viewW = w; viewH = h;
    if (session != null) session.setDisplayGeometry(getWindowManager().getDefaultDisplay().getRotation(), w, h);
  }

  @Override
  public void onDrawFrame(GL10 unused) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
    Session s = session;
    if (s == null) return;
    Frame frame;
    try {
      s.setCameraTextureName(bg.texture);
      s.setDisplayGeometry(getWindowManager().getDefaultDisplay().getRotation(), viewW, viewH);
      frame = s.update();
    } catch (Exception e) {
      Log.e(TAG, "update", e);
      return;
    }
    frameCount++;
    long now = System.currentTimeMillis();
    if (fpsT0 == 0) fpsT0 = now;
    if (now - fpsT0 >= 1000) { fps = frameCount * 1000f / (now - fpsT0); frameCount = 0; fpsT0 = now; }
    Camera cam = frame.getCamera();
    bg.draw(frame, phase == Phase.CONFIRM ? 0.85f : 1f);
    if (playback != null && s.getPlaybackStatus() == PlaybackStatus.FINISHED && phase == Phase.SCANNING) {
      runOnUiThread(() -> hint.setText("Reproducción terminada. Pulsa «Terminar» para guardar."));
    }
    boolean tracking = cam.getTrackingState() == TrackingState.TRACKING;
    if (tracking) {
      float[] pose = new float[16];
      cam.getPose().toMatrix(pose, 0);
      camY = pose[13];
      if (now - lastCaptureMs >= 50 && phase != Phase.SAVING) {
        DepthFrame df = capture.capture(frame, cam, true);
        if (df != null) {
          lastCaptureMs = now;
          onDepthFrame(frame, df, now);
        }
      }
      if (now - lastCrossMs >= 100) {
        lastCrossMs = now;
        updateCrosshair(frame);
      }
      if (anchorRequested) {
        anchorRequested = false;
        markAnchor(frame);
      }
      float[] view = new float[16], proj = new float[16], vp = new float[16];
      cam.getViewMatrix(view, 0);
      cam.getProjectionMatrix(proj, 0, 0.05f, 50f);
      Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
      drawMeshes(vp, now);
    }
    if (now - lastHudMs >= 250) {
      lastHudMs = now;
      updateHud(cam, tracking);
    }
  }

  private void drawMeshes(float[] vp, long now) {
    if (meshes == null) return;
    Phase ph = phase;
    if (ph == Phase.CONFIRM) {
      List<Reloc.Candidate> cs = candidates;
      if (cs.isEmpty()) return;
      float[] model = Mat4.invertRigid(cs.get(candIndex % cs.size()).matrix());
      if (blink) {
        if ((now / 600) % 2 == 0) meshes.draw(vp, model, MeshRenderer.MODE_COLOR, 1f, 0);
        return;
      }
      meshes.draw(vp, model, MeshRenderer.MODE_SEMAFORO, 0.85f, Reloc.LIVE_TOL);
    } else if (ph == Phase.SCANNING || ph == Phase.SAVING) {
      float[] align = worker.fusion.align;
      float[] model = align != null ? Mat4.invertRigid(align) : null;
      if (colorMode) meshes.draw(vp, model, MeshRenderer.MODE_COLOR, 1f, 0);
      else meshes.draw(vp, model, MeshRenderer.MODE_AR, 0.5f, 0);
    }
  }

  /** Un frame de profundidad nuevo (hilo GL). */
  private void onDepthFrame(Frame frame, DepthFrame df, long now) {
    lastFrame = df;
    if (meshes != null) meshes.setLive(df);
    if (sessionStartPose == null && df.color != null) {
      sessionStartPose = df.pose.clone();
      startPhoto = photo(frame, df, false);
      sessionFloor = arFloor();
    }
    double yaw = Math.atan2(df.pose[8], df.pose[10]); // eje -Z de la cámara: yaw 0 = mirando a -Z
    Phase ph = phase;
    if (ph == Phase.SCANNING) {
      if (worker.submit(df)) runOnUiThread(() -> coverage.mark(yaw));
    } else if (ph == Phase.ANCHORS || ph == Phase.SWEEP || ph == Phase.CONFIRM) {
      worker.latest = df;
      if (now - lastSweepMs >= 300) {
        lastSweepMs = now;
        synchronized (sweepFrames) {
          sweepFrames.add(df);
          if (sweepFrames.size() > 50) sweepFrames.remove(0);
        }
        if (ph == Phase.SWEEP) runOnUiThread(() -> { coverage.mark(yaw); checkSweep(); });
      }
      if (ph == Phase.CONFIRM && now - lastLiveMs >= 500) {
        lastLiveMs = now;
        updateLive(df);
      }
    }
    if (now % 5000 < 60) {
      float mean = 0;
      int valid = 0;
      for (int i = 0; i < df.depth.length; i++) if (df.depth[i] > 0) { valid++; mean += df.conf(i); }
      Log.i(TAG, String.format(Locale.US, "profundidad raw %dx%d, válidos %.0f%%, confianza media %.2f, color %dx%d",
          df.width, df.height, 100f * valid / df.depth.length, valid > 0 ? mean / valid : 0, df.colorWidth, df.colorHeight));
    }
  }

  /** Suelo según los planos horizontales de ARCore (el más bajo), o NaN. */
  private float arFloor() {
    Session s = session;
    if (s == null) return Float.NaN;
    float y = Float.NaN;
    for (Plane p : s.getAllTrackables(Plane.class)) {
      if (p.getType() != Plane.Type.HORIZONTAL_UPWARD_FACING || p.getTrackingState() != TrackingState.TRACKING || p.getSubsumedBy() != null) continue;
      float py = p.getCenterPose().ty();
      if (camY - py < Reloc.MIN_CAM_ABOVE_FLOOR) continue; // una mesa o una cama no es el suelo
      if (Float.isNaN(y) || py < y) y = py;
    }
    return y;
  }

  private void updateCrosshair(Frame frame) {
    DepthFrame df = lastFrame;
    if (df == null) { crosshair = null; cross.ok = false; return; }
    float[] uv = ArCapture.viewToDepth(frame, 0.5f, 0.5f, df.width, df.height);
    float[] p = ArCapture.crosshair(df, uv[0], uv[1]);
    crosshair = p;
    boolean ok = p != null;
    if (ok != cross.ok) { cross.ok = ok; cross.postInvalidate(); }
    float f = arFloor();
    if (!Float.isNaN(f)) sessionFloor = f;
    if (phase == Phase.ANCHORS) runOnUiThread(this::refreshAnchorText);
  }

  /** Foto de la vista actual (orientación de la pantalla) desde el color del frame, con cruz opcional. */
  private Bitmap photo(Frame frame, DepthFrame df, boolean withCross) {
    if (df.color == null) return null;
    float[] in = {0, 0, 1, 0, 0, 1};
    float[] out = new float[6];
    frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, in, Coordinates2d.IMAGE_NORMALIZED, out);
    int W = 240, H = Math.max(1, Math.round(240f * viewH / Math.max(1, viewW)));
    int[] px = new int[W * H];
    for (int y = 0; y < H; y++) {
      float vy = (y + 0.5f) / H;
      for (int x = 0; x < W; x++) {
        float vx = (x + 0.5f) / W;
        float u = out[0] + (out[2] - out[0]) * vx + (out[4] - out[0]) * vy;
        float v = out[1] + (out[3] - out[1]) * vx + (out[5] - out[1]) * vy;
        int ci = Math.min(df.colorWidth - 1, Math.max(0, (int) (u * df.colorWidth)));
        int cj = Math.min(df.colorHeight - 1, Math.max(0, (int) (v * df.colorHeight)));
        int o = (cj * df.colorWidth + ci) * 3;
        px[y * W + x] = 0xFF000000 | ((df.color[o] & 255) << 16) | ((df.color[o + 1] & 255) << 8) | (df.color[o + 2] & 255);
      }
    }
    Bitmap b = Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true);
    if (withCross) {
      Canvas c = new Canvas(b);
      Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
      p.setStyle(Paint.Style.STROKE);
      float cx = W / 2f, cy = H / 2f;
      for (int pass = 0; pass < 2; pass++) {
        p.setColor(pass == 0 ? 0xCC000000 : 0xFFFF3B30);
        p.setStrokeWidth(pass == 0 ? 5 : 2.5f);
        c.drawCircle(cx, cy, 12, p);
        c.drawLine(cx - 22, cy, cx - 5, cy, p);
        c.drawLine(cx + 5, cy, cx + 22, cy, p);
        c.drawLine(cx, cy - 22, cx, cy - 5, p);
        c.drawLine(cx, cy + 5, cx, cy + 22, p);
      }
    }
    return b;
  }

  // ---------------------------------------------------------------- anclas

  private float[] toScan(float[] p) {
    float[] a = worker.fusion.align;
    if (a == null) return new float[] {p[0], p[1], p[2]};
    float[] o = new float[3];
    Mat4.transform(a, p[0], p[1], p[2], o);
    return o;
  }

  /** Marca un ancla en la mirilla (hilo GL). */
  private void markAnchor(Frame frame) {
    float[] p = crosshair;
    DepthFrame df = lastFrame;
    if (p == null || df == null) {
      toast("Sin profundidad fiable en la mirilla: acércate (≤ 3,5 m) a un punto con textura.");
      return;
    }
    if (phase == Phase.ANCHORS) {
      runOnUiThread(() -> remarkAnchor(p));
      return;
    }
    if (phase != Phase.SCANNING) return;
    ScanMeta.Anchor a = new ScanMeta.Anchor();
    a.p = toScan(p);
    a.dist = p[3];
    float[] cp = df.cameraPosition();
    a.cam = toScan(cp);
    a.camHeight = Float.isNaN(sessionFloor) ? Float.NaN : cp[1] - sessionFloor;
    Bitmap ph = photo(frame, df, true);
    synchronized (newAnchors) {
      newAnchors.add(a);
      newAnchorPhotos.add(ph);
    }
    int n = newAnchors.size();
    runOnUiThread(() -> {
      hint.setText("Ancla " + n + " marcada a " + Ui.meters(a.dist) + (Float.isNaN(a.camHeight) ? "" : " · altura " + Ui.meters(a.camHeight))
          + ". Marca 2-3 puntos nítidos (esquinas, bordes, enchufes) separados entre sí.");
      anchorPhoto.setImageBitmap(ph);
      anchorText.setText("Ancla " + n);
      anchorPanel.setVisibility(View.VISIBLE);
    });
  }

  // ---------------------------------------------------------------- continuar

  private void loadSaved() {
    try {
      saved = store.meta(continueId);
    } catch (Exception e) {
      fail("No se pudo abrir el escaneo: " + e.getMessage());
      return;
    }
    worker.setPaused(true);
    worker.run(() -> {
      try {
        Params p = Params.withVoxel(saved.voxel);
        Volume v = store.volume(continueId, p, true);
        worker.fusion.setVolume(v);
        worker.volumeReplaced();
        List<MeshPart> all = worker.fusion.allMeshes();
        if (meshes != null) meshes.enqueue(all);
        savedCloud = worker.fusion.surfaceCloud(true);
        savedReady = true;
        Log.i(TAG, "escaneo guardado: " + v.size() + " bloques, nube " + savedCloud.n + " puntos");
      } catch (Exception e) {
        fail("No se pudo cargar el volumen: " + e.getMessage());
      }
    });
  }

  @Override
  public void onBackPressed() {
    if (phase == Phase.SAVING) return;
    new AlertDialog.Builder(this).setTitle("Salir")
        .setMessage("¿Salir sin guardar? Se perderá lo escaneado en esta sesión.")
        .setPositiveButton("Salir", (d, w) -> finish())
        .setNegativeButton("Seguir", null).show();
  }

  private void enterAnchorsOrSweep() {
    if (saved != null && saved.anchors.size() >= 2) enterAnchors();
    else enterSweep();
  }

  private void enterAnchors() {
    phase = Phase.ANCHORS;
    anchorIndex = 0;
    sessionAnchorPts.clear();
    anchorWarned = false;
    coverage.setVisibility(View.GONE);
    cross.setVisibility(View.VISIBLE);
    title.setText("Continuar: vuelve a marcar las anclas");
    hint.setText("Busca el punto de la foto y apunta la mirilla a la cruz roja, desde una distancia y altura parecidas.");
    Button mark = Ui.button(this, "Marcar", Ui.ACCENT, v -> anchorRequested = true);
    Button skip = Ui.button(this, "Saltar", Ui.BTN, v -> { sessionAnchorPts.add(null); nextAnchor(); });
    Button search = Ui.button(this, "Buscar sin anclas", Ui.BTN, v -> enterSweep());
    setBar(mark, skip, null, search);
    anchorPanel.setVisibility(View.VISIBLE);
    showAnchor();
  }

  private void showAnchor() {
    ScanMeta.Anchor a = saved.anchors.get(anchorIndex);
    anchorPhoto.setImageBitmap(store.photo(continueId, a.photo));
    refreshAnchorText();
  }

  private void refreshAnchorText() {
    if (saved == null || anchorIndex >= saved.anchors.size()) return;
    ScanMeta.Anchor a = saved.anchors.get(anchorIndex);
    StringBuilder s = new StringBuilder("Ancla ").append(anchorIndex + 1).append(" de ").append(saved.anchors.size());
    s.append("\nTomada a ").append(Ui.meters(a.dist));
    if (!Float.isNaN(a.camHeight)) s.append(" · altura ").append(Ui.meters(a.camHeight));
    float[] p = crosshair;
    DepthFrame df = lastFrame;
    s.append("\nAhora: ");
    if (p == null) s.append("sin profundidad");
    else {
      s.append(Ui.meters(p[3]));
      if (!Float.isNaN(sessionFloor) && df != null) s.append(" · altura ").append(Ui.meters(df.pose[13] - sessionFloor));
    }
    anchorText.setText(s.toString());
  }

  /** Volver a marcar el ancla actual (hilo de UI). Con avisos; un segundo toque acepta igual. */
  private void remarkAnchor(float[] p) {
    ScanMeta.Anchor a = saved.anchors.get(anchorIndex);
    DepthFrame df = lastFrame;
    String warn = null;
    if (!Float.isNaN(a.camHeight) && !Float.isNaN(sessionFloor) && df != null) {
      float h = df.pose[13] - sessionFloor;
      if (Math.abs(h - a.camHeight) > 0.25f) warn = "La altura del teléfono difiere " + Ui.meters(Math.abs(h - a.camHeight)) + " de la original.";
    }
    for (int i = 0; i < sessionAnchorPts.size() && warn == null; i++) {
      float[] q = sessionAnchorPts.get(i);
      if (q == null) continue;
      float ds = dist(saved.anchors.get(i).p, saved.anchors.get(anchorIndex).p), dn = dist(q, p);
      if (Math.abs(ds - dn) > 0.20f) warn = String.format(Locale.US, "La distancia al ancla %d es %s y debería ser %s.", i + 1, Ui.meters(dn), Ui.meters(ds));
    }
    if (warn != null && !anchorWarned) {
      anchorWarned = true;
      hint.setText(warn + " Corrige la mirilla o pulsa «Marcar» otra vez para aceptar igual.");
      return;
    }
    sessionAnchorPts.add(new float[] {p[0], p[1], p[2]});
    nextAnchor();
  }

  private static float dist(float[] a, float[] b) {
    return (float) Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
  }

  private void nextAnchor() {
    anchorWarned = false;
    anchorIndex++;
    if (anchorIndex < saved.anchors.size()) {
      showAnchor();
      hint.setText("Ancla " + anchorIndex + " lista. Ahora la " + (anchorIndex + 1) + ".");
      return;
    }
    List<float[]> sv = new ArrayList<>(), ss = new ArrayList<>();
    for (int i = 0; i < sessionAnchorPts.size(); i++) {
      if (sessionAnchorPts.get(i) == null) continue;
      sv.add(saved.anchors.get(i).p);
      ss.add(sessionAnchorPts.get(i));
    }
    if (ss.size() < 2) {
      toast("Hacen falta al menos 2 anclas. Se busca sin anclas.");
      enterSweep();
      return;
    }
    solveWithAnchors(sv, ss);
  }

  private void solveWithAnchors(List<float[]> sv, List<float[]> ss) {
    phase = Phase.SEARCHING;
    hint.setText("Calculando la alineación con las anclas…");
    setBar();
    anchorPanel.setVisibility(View.GONE);
    worker.run(() -> {
      waitSaved();
      Reloc.AnchorSolution sol = Reloc.solveAnchors(sv, ss);
      List<Reloc.Candidate> out = new ArrayList<>();
      float[] pts = sessionPoints();
      Reloc.Candidate raw = new Reloc.Candidate();
      raw.yaw = sol.yaw; raw.t = sol.t; raw.tag = "anclas";
      if (pts.length >= 300) {
        Fusion.Pose4 r = worker.fusion.alignPoints(pts, new double[] {sol.yaw, sol.t[0], sol.t[1], sol.t[2]},
            new float[] {0.15f, 0.08f, 0.05f}, 4);
        Reloc.Candidate icp = new Reloc.Candidate();
        icp.yaw = r.yaw; icp.t = r.t; icp.tag = "anclas + ICP";
        boolean far = Math.hypot(r.t[0] - sol.t[0], r.t[2] - sol.t[2]) > 0.5 || Mat4.angleDiff(r.yaw, sol.yaw) > 0.35;
        if (!far) out.add(icp);
      }
      out.add(raw);
      StringBuilder res = new StringBuilder("Residuos de las anclas:");
      for (float rr : sol.residuals) res.append(' ').append(Math.round(rr * 100)).append(" cm");
      anchorsNote = res.toString() + (sol.maxResidual > 0.25f ? "\nLas anclas no cuadran (residuo > 25 cm): revisa la alineación con cuidado o busca sin anclas." : "");
      Log.i(TAG, "anclas: yaw " + sol.yaw + " t " + sol.t[0] + "," + sol.t[1] + "," + sol.t[2] + " " + res);
      runOnUiThread(() -> enterConfirm(out));
    });
  }

  /** Puntos de los frames recientes de la sesión (marco de la sesión) para ICP. */
  private float[] sessionPoints() {
    List<DepthFrame> fr;
    synchronized (sweepFrames) { fr = new ArrayList<>(sweepFrames); }
    int step = Math.max(1, fr.size() / 12);
    List<float[]> parts = new ArrayList<>();
    int total = 0;
    for (int i = fr.size() - 1; i >= 0; i -= step) {
      float[] p = Fusion.samplePoints(fr.get(i), worker.fusion.params, 4);
      parts.add(p);
      total += p.length;
    }
    float[] out = new float[total];
    int o = 0;
    for (float[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
    return out;
  }

  private void waitSaved() {
    while (!savedReady) {
      try { Thread.sleep(50); } catch (InterruptedException e) { return; }
    }
  }

  private void enterSweep() {
    phase = Phase.SWEEP;
    anchorPanel.setVisibility(View.GONE);
    coverage.reset();
    coverage.setVisibility(View.VISIBLE);
    sweepStartMs = System.currentTimeMillis();
    title.setText("Continuar: buscar en toda la habitación");
    hint.setText("Gira despacio sobre ti mismo mirando paredes y muebles (6-15 s).");
    Button now = Ui.button(this, "Buscar ya", Ui.BTN, v -> runSearch());
    Button anchors = Ui.button(this, "Usar anclas", Ui.BTN, v -> enterAnchors());
    anchors.setEnabled(saved != null && saved.anchors.size() >= 2);
    setBar(now, anchors);
  }

  private void checkSweep() {
    if (phase != Phase.SWEEP) return;
    long el = System.currentTimeMillis() - sweepStartMs;
    float cov = coverage.fraction();
    hint.setText(String.format(Locale.US, "Gira despacio mirando la habitación: %d s · %d %% de direcciones", el / 1000, Math.round(cov * 100)));
    if ((el >= 6000 && cov >= 0.5f) || el >= 15000) runSearch();
  }

  private void runSearch() {
    if (phase != Phase.SWEEP) return;
    phase = Phase.SEARCHING;
    hint.setText("Buscando dónde estás en el escaneo guardado…");
    setBar();
    final float camYNow = camY, floorNow = sessionFloor;
    worker.run(() -> {
      waitSaved();
      List<DepthFrame> fr;
      synchronized (sweepFrames) { fr = new ArrayList<>(sweepFrames); }
      Fusion sess = new Fusion(worker.fusion.params);
      for (DepthFrame f : fr) sess.integrateCpu(f);
      Cloud sc = sess.surfaceCloud(true);
      List<Reloc.Candidate> seeds = new ArrayList<>();
      if (saved.startPose != null && sessionStartPose != null) seeds.add(Reloc.startGuess(saved.startPose, sessionStartPose));
      Float sessFallback = !Float.isNaN(floorNow) ? floorNow : camYNow - 1.3f;
      Float savedFallback = Float.isNaN(saved.floorY) ? null : saved.floorY;
      Float savedCamY = saved.startPose != null ? saved.startPose[13] : null;
      Fusion target = worker.fusion;
      Reloc.Result r = Reloc.relocalize(savedCloud, sc,
          init -> target.alignPoints(sc.pos, init, new float[] {0.25f, 0.15f, 0.08f, 0.05f}, 4),
          (yaw, t) -> target.scorePose(sc, yaw, t, Reloc.RES_TOL, Reloc.COS_TOL),
          fr, sessFallback, savedFallback, camYNow, savedCamY, seeds);
      Log.i(TAG, "búsqueda: ok " + r.ok + " ambigua " + r.ambiguous + " candidatos " + r.candidates.size() + " " + r.reason);
      List<Reloc.Candidate> out = new ArrayList<>();
      for (Reloc.Candidate c : r.candidates) {
        if (out.size() >= Reloc.MAX_SHOWN) break;
        if (c.quality >= Reloc.MIN_QUALITY || "punto inicial".equals(c.tag)) out.add(c);
      }
      anchorsNote = r.ok ? "" : "Coincidencia baja (" + (r.reason == null ? "" : r.reason) + "): compara con cuidado.";
      runOnUiThread(() -> {
        if (out.isEmpty()) {
          toast("No se encontró ninguna alineación. Vuelve a intentarlo mirando más superficie.");
          enterSweep();
        } else {
          enterConfirm(out);
        }
      });
    });
  }

  private void enterConfirm(List<Reloc.Candidate> cs) {
    candidates = cs;
    candIndex = 0;
    liveScores = new double[cs.size()];
    blink = false;
    phase = Phase.CONFIRM;
    cross.setVisibility(View.GONE);
    coverage.setVisibility(View.GONE);
    title.setText("¿Coincide?");
    Button yes = Ui.button(this, "Coincide", Ui.OK, v -> accept());
    Button next = Ui.button(this, "Probar otra", Ui.BTN, v -> { candIndex = (candIndex + 1) % candidates.size(); refreshConfirm(); });
    next.setEnabled(cs.size() > 1);
    Button bl = Ui.button(this, "Parpadeo", Ui.BTN, null);
    bl.setOnClickListener(v -> { blink = !blink; bl.setText(blink ? "Semáforo" : "Parpadeo"); });
    Button back = Ui.button(this, saved.anchors.size() >= 2 ? "Anclas" : "Buscar otra vez", Ui.BTN, v -> {
      candidates = new ArrayList<>();
      enterAnchorsOrSweep();
    });
    Button search = Ui.button(this, "Buscar sin anclas", Ui.BTN, v -> { candidates = new ArrayList<>(); enterSweep(); });
    setBar(yes, next, null, bl, back, search);
    refreshConfirm();
  }

  private void refreshConfirm() {
    List<Reloc.Candidate> cs = candidates;
    if (cs.isEmpty()) return;
    int i = candIndex % cs.size();
    Reloc.Candidate c = cs.get(i);
    double[] ls = liveScores;
    int best = 0;
    for (int k = 1; k < ls.length; k++) if (ls[k] > ls[best]) best = k;
    StringBuilder s = new StringBuilder();
    s.append("Opción ").append(i + 1).append(" de ").append(cs.size());
    if (c.tag != null) s.append(" (").append(c.tag).append(')');
    if (cs.size() > 1 && i == best && ls[best] > 0) s.append(" · recomendada");
    s.append(String.format(Locale.US, "\nCoincide en vivo: %d %%", Math.round(ls.length > i ? ls[i] * 100 : 0)));
    s.append("\nVerde = coincide (±15 cm) · rojo = la cámara ve a través · gris = sin dato. Mira varias zonas antes de aceptar.");
    if (!anchorsNote.isEmpty()) s.append('\n').append(anchorsNote);
    hint.setText(s.toString());
  }

  /** Coincidencia en vivo de todos los candidatos con el frame actual (en el hilo de fusión). */
  private void updateLive(DepthFrame df) {
    List<Reloc.Candidate> cs = candidates;
    if (cs.isEmpty() || savedCloud == null) return;
    worker.run(() -> {
      double[] sc = new double[cs.size()];
      double[] prev = liveScores;
      for (int k = 0; k < cs.size(); k++) {
        int[] r = Reloc.liveAgreement(savedCloud, df, cs.get(k).matrix(), Reloc.LIVE_TOL);
        int n = r[0] + r[1];
        double v = n >= 30 ? r[0] / (double) n : 0;
        sc[k] = prev.length == sc.length && prev[k] > 0 ? prev[k] * 0.6 + v * 0.4 : v;
      }
      if (candidates == cs) {
        liveScores = sc;
        runOnUiThread(this::refreshConfirm);
      }
    });
  }

  private void accept() {
    List<Reloc.Candidate> cs = candidates;
    if (cs.isEmpty()) return;
    Reloc.Candidate c = cs.get(candIndex % cs.size());
    final float[] align = c.matrix();
    phase = Phase.SEARCHING;
    hint.setText("Integrando el barrido…");
    double live = liveScores.length > candIndex ? liveScores[candIndex] : 0;
    Log.i(TAG, String.format(Locale.US, "confirmado: %s yaw %.3f t %.3f %.3f %.3f en vivo %.0f%%", c.tag, c.yaw, c.t[0], c.t[1], c.t[2], live * 100));
    worker.run(() -> {
      worker.fusion.align = align;
      List<DepthFrame> fr;
      synchronized (sweepFrames) { fr = new ArrayList<>(sweepFrames); sweepFrames.clear(); }
      for (DepthFrame f : fr) worker.integrateNow(f); // los frames del barrido no se descartan
      worker.setPaused(false);
      runOnUiThread(() -> {
        phase = Phase.SCANNING;
        title.setText("Continuar: escanea las zonas nuevas");
        hint.setText("Lo ya escaneado no cambia: solo se añaden zonas nuevas. Pulsa «Terminar» para guardarlo como escaneo nuevo.");
        coverage.reset();
        coverage.setVisibility(View.VISIBLE);
        showScanningBar();
      });
    });
  }

  // ---------------------------------------------------------------- grabación

  private void toggleRecording() {
    Session s = session;
    if (s == null) return;
    try {
      if (!recording) {
        String name = "rec-" + ScanStore.newId();
        recFile = new File(store.recordings, name + ".mp4");
        RecordingConfig rc = new RecordingConfig(s).setMp4DatasetUri(Uri.fromFile(recFile)).setAutoStopOnPause(true);
        s.startRecording(rc);
        worker.setLog(FrameLog.open(new FileOutputStream(new File(store.recordings, name + ".frames"))));
        recording = true;
        toast("Grabando en " + recFile.getName());
      } else {
        recording = false;
        worker.setLog(null);
        if (s.getRecordingStatus() == RecordingStatus.OK) s.stopRecording();
        toast("Grabación guardada: " + recFile.getName());
      }
    } catch (Exception e) {
      recording = false;
      toast("Error de grabación: " + e.getMessage());
    }
    if (btnRecord != null) {
      btnRecord.setText(recording ? "Parar" : "Grabar");
      btnRecord.setBackground(Ui.rounded(recording ? Ui.DANGER : Ui.BTN, 10, this));
    }
  }

  // ---------------------------------------------------------------- guardar

  private void finishScan() {
    if (phase != Phase.SCANNING) return;
    if (recording) toggleRecording();
    phase = Phase.SAVING;
    worker.setPaused(true);
    setBar();
    hint.setText("Guardando: mallado final y limpieza de fragmentos…");
    final float floorNow = sessionFloor;
    worker.run(() -> {
      try {
        worker.flushAll();
        Fusion f = worker.fusion;
        Fusion.Final fin = f.finalMeshes(80, 0.004);
        if (fin.totalTris == 0) {
          runOnUiThread(() -> { toast("No hay nada escaneado todavía."); phase = Phase.SCANNING; worker.setPaused(false); showScanningBar(); });
          return;
        }
        ScanMeta m = new ScanMeta();
        m.id = ScanStore.newId();
        m.created = System.currentTimeMillis();
        m.name = (saved != null ? saved.name + " (ampliado)" : ScanStore.defaultName(m.created));
        m.voxel = f.params.voxel;
        m.hasVolume = true;
        m.parent = saved != null ? saved.id : null;
        for (MeshPart p : fin.meshes) { m.verts += p.vertexCount(); m.tris += p.triangleCount(); }
        // Suelo y pista de inicio en el marco del escaneo.
        Cloud cloud = Fusion.cloudFrom(fin.meshes);
        if (saved != null) {
          m.startPose = saved.startPose;
          m.floorY = saved.floorY;
        } else {
          m.startPose = sessionStartPose;
          float fy = Reloc.estimateFloor(cloud, Float.isNaN(floorNow) ? null : floorNow);
          if (sessionStartPose != null && sessionStartPose[13] - fy < Reloc.MIN_CAM_ABOVE_FLOOR && !Float.isNaN(floorNow)) fy = floorNow;
          m.floorY = fy;
        }
        if (saved != null) m.anchors.addAll(saved.anchors);
        List<ScanMeta.Anchor> mine;
        List<Bitmap> photos;
        synchronized (newAnchors) { mine = new ArrayList<>(newAnchors); photos = new ArrayList<>(newAnchorPhotos); }
        for (int i = 0; i < mine.size(); i++) {
          ScanMeta.Anchor a = mine.get(i);
          a.photo = "anchor" + (m.anchors.size()) + ".jpg";
          if (photos.get(i) != null) store.writeFile(m.id, a.photo, ScanStore.jpeg(photos.get(i), 85));
          m.anchors.add(a);
        }
        if (saved != null) {
          for (ScanMeta.Anchor a : saved.anchors) {
            File src = store.file(saved.id, a.photo);
            if (a.photo != null && src.isFile()) store.writeFile(m.id, a.photo, ScanStore.read(src));
          }
          File st = store.file(saved.id, "start.jpg");
          if (st.isFile()) store.writeFile(m.id, "start.jpg", ScanStore.read(st));
        } else if (startPhoto != null) {
          store.writeFile(m.id, "start.jpg", ScanStore.jpeg(startPhoto, 80));
        }
        store.writeFile(m.id, "mesh.glb", Glb.write(fin.meshes, m.name));
        store.writeFile(m.id, "volume.bin", f.volume.export());
        store.writeMeta(m);
        Log.i(TAG, "guardado " + m.id + ": " + m.tris + " triángulos, " + f.volume.size() + " bloques, quitados " + fin.removedTris);
        runOnUiThread(() -> {
          startActivity(new Intent(this, ViewerActivity.class).putExtra("id", m.id));
          finish();
        });
      } catch (Exception e) {
        Log.e(TAG, "guardar", e);
        runOnUiThread(() -> { toast("No se pudo guardar: " + e.getMessage()); phase = Phase.SCANNING; worker.setPaused(false); showScanningBar(); });
      }
    });
  }

  // ---------------------------------------------------------------- HUD

  private void updateHud(Camera cam, boolean tracking) {
    FusionWorker.Stats st = worker.stats;
    DepthFrame df = lastFrame;
    StringBuilder s = new StringBuilder();
    s.append(String.format(Locale.US, "%.0f fps · ", fps));
    if (tracking) s.append("tracking OK");
    else s.append("sin tracking (").append(cam.getTrackingFailureReason()).append(')');
    if (df != null) {
      int valid = 0;
      float conf = 0;
      for (int i = 0; i < df.depth.length; i += 4) if (df.depth[i] > 0) { valid++; conf += df.conf(i); }
      s.append(String.format(Locale.US, "\nprofundidad %dx%d · conf %.2f", df.width, df.height, valid > 0 ? conf / valid : 0));
    }
    s.append(String.format(Locale.US, "\n%s %.1f ms · bloques %d · pend %d · tris %d",
        st.gpu ? "GPU" : "CPU", st.integrateMs, st.blocks, st.pending, st.tris));
    if (st.gpu) s.append(String.format(Locale.US, " · pool %d/%d", st.gpuBlocks, st.gpuCapacity));
    if (recording) s.append("\n● grabando");
    String text = s.toString();
    Phase ph = phase;
    float[] p = crosshair;
    String distHint = null;
    if (ph == Phase.SCANNING && tracking && p != null && p[3] > 2.2f) distHint = "Acércate: la profundidad es más precisa a menos de 1,5 m.";
    final String dh = distHint;
    runOnUiThread(() -> {
      hud.setText(text);
      if (dh != null && newAnchors.isEmpty() && continueId == null && playback == null) hint.setText(dh);
    });
  }

  private void toast(String msg) {
    runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
  }
}
