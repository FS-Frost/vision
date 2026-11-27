package org.frost.vision.ui;

import android.app.Activity;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.frost.vision.ar.FusionWorker;
import org.frost.vision.core.DepthFrame;
import org.frost.vision.core.Mat4;
import org.frost.vision.core.Params;
import org.frost.vision.core.TestScene;
import org.frost.vision.gpu.MeshRenderer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Simulación del pipeline EN VIVO sin cámara: el FusionWorker real (hilo propio, GPU, sync y
 * mallado incremental) recibe la escena sintética a 15 Hz como si fuera ARCore, y la malla se dibuja
 * con el MeshRenderer de la pantalla de escaneo (azul AR, color y semáforo contra la profundidad
 * "en vivo"). Mide el semáforo leyendo píxeles: con la pose correcta debe dominar el verde.
 * Resultado en logcat (VISION_TEST). Lanzar: adb shell am start -n org.frost.vision/.ui.SimActivity
 */
public final class SimActivity extends Activity implements GLSurfaceView.Renderer {
  static final String TAG = "VISION_TEST";
  private GLSurfaceView gl;
  private TextView info;
  private MeshRenderer meshes;
  private FusionWorker worker;
  private Thread feeder;
  private volatile boolean feeding = true;
  private volatile DepthFrame current;
  private volatile int submitted, accepted;
  private long t0, sweepEndMs;
  private int w = 1, h = 1, stage;
  private double greenOk = -1, redOk, greenBad = -1, redBad;
  private int pendingAfter = -1;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    FrameLayout root = new FrameLayout(this);
    gl = new GLSurfaceView(this);
    gl.setEGLContextClientVersion(3);
    gl.setEGLConfigChooser(8, 8, 8, 8, 24, 0);
    gl.setRenderer(this);
    root.addView(gl);
    FrameLayout overlay = new FrameLayout(this);
    root.addView(overlay);
    Ui.insets(overlay, 10);
    info = Ui.label(this, "Simulación del escaneo en vivo…", 13, Ui.TEXT, false);
    info.setBackground(Ui.rounded(Ui.PANEL, 10, this));
    info.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
    overlay.addView(info, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));
    setContentView(root);
    worker = new FusionWorker(getAssets(), new Params(), true, parts -> { if (meshes != null) meshes.enqueue(parts); });
    feeder = new Thread(this::feed, "simulación");
    feeder.start();
  }

  /** Barrido de -1,4 a 1,4 rad en 6 s a 15 Hz, a 1,3 m de altura, mirando un poco hacia abajo. */
  private void feed() {
    long start = System.currentTimeMillis();
    while (feeding) {
      double tt = (System.currentTimeMillis() - start) / 6000.0;
      if (tt > 1) break;
      double yaw = -1.4 + 2.8 * tt, pitch = -0.3 + 0.15 * Math.sin(tt * 12);
      DepthFrame f = TestScene.room(Mat4.pose(yaw, pitch, 0, 1.3, 0));
      current = f;
      if (meshes != null) meshes.setLive(f);
      submitted++;
      if (worker.submit(f)) accepted++;
      try { Thread.sleep(66); } catch (InterruptedException e) { return; }
    }
    sweepEndMs = System.currentTimeMillis();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    feeding = false;
    worker.release();
  }

  @Override protected void onPause() { super.onPause(); gl.onPause(); }
  @Override protected void onResume() { super.onResume(); gl.onResume(); }

  @Override
  public void onSurfaceCreated(GL10 unused, EGLConfig config) {
    meshes = new MeshRenderer(getAssets());
    t0 = System.currentTimeMillis();
  }

  @Override
  public void onSurfaceChanged(GL10 unused, int width, int height) {
    w = width; h = height;
    GLES20.glViewport(0, 0, w, h);
  }

  private float[] viewProj(float[] pose) {
    float[] view = Mat4.invertRigid(pose), proj = new float[16], vp = new float[16];
    Matrix.perspectiveM(proj, 0, 60, w / (float) h, 0.05f, 30f);
    Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
    return vp;
  }

  /** {verde, rojo} como fracción de los píxeles no de fondo. */
  private double[] measure() {
    ByteBuffer px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
    GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
    int green = 0, red = 0, any = 0;
    for (int i = 0; i < w * h; i += 7) {
      int r = px.get(i * 4) & 255, g = px.get(i * 4 + 1) & 255, b = px.get(i * 4 + 2) & 255;
      if (r + g + b < 40) continue; // fondo
      any++;
      if (g > r * 1.6 && g > b * 1.6) green++;
      else if (r > g * 1.6 && r > b * 1.6) red++;
    }
    return new double[] {green / (double) Math.max(1, any), red / (double) Math.max(1, any)};
  }

  @Override
  public void onDrawFrame(GL10 unused) {
    GLES20.glClearColor(0, 0, 0, 1);
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
    DepthFrame f = current;
    if (f == null || meshes == null) return;
    long now = System.currentTimeMillis();
    FusionWorker.Stats st = worker.stats;
    float[] vp = viewProj(f.pose);
    String text;
    if (sweepEndMs == 0) {
      meshes.draw(vp, null, MeshRenderer.MODE_AR, 0.6f, 0);
      text = String.format(Locale.US, "Barrido (azul AR): %s %.1f ms · frames %d/%d · bloques %d · pend %d · tris %d",
          st.gpu ? "GPU" : "CPU", st.integrateMs, st.frames, submitted, st.blocks, st.pending, st.tris);
    } else if (now - sweepEndMs < 1000) {
      meshes.draw(vp, null, MeshRenderer.MODE_COLOR, 1f, 0);
      text = "Color real · esperando 1 s sin frames…";
    } else if (stage == 0) {
      pendingAfter = st.pending; // cola de mallado tras el último flush (el hilo sigue sincronizando cada 200 ms)
      stage = 1;
      meshes.draw(vp, null, MeshRenderer.MODE_COLOR, 1f, 0);
      text = "Color real";
    } else if (stage < 40) {
      // Semáforo con la pose correcta: la superficie coincide con la profundidad en vivo.
      meshes.draw(vp, null, MeshRenderer.MODE_SEMAFORO, 1f, 0.15f);
      if (++stage == 39) { double[] m = measure(); greenOk = m[0]; redOk = m[1]; }
      text = "Semáforo, pose correcta";
    } else if (stage < 80) {
      // Pose equivocada (girada 30° y movida 40 cm): debe dejar de coincidir.
      float[] wrong = Mat4.yaw(0.52, 0.4, 0, 0.3);
      meshes.draw(vp, wrong, MeshRenderer.MODE_SEMAFORO, 1f, 0.15f);
      if (++stage == 79) { double[] m = measure(); greenBad = m[0]; redBad = m[1]; }
      text = "Semáforo, pose equivocada";
    } else {
      meshes.draw(vp, null, MeshRenderer.MODE_SEMAFORO, 1f, 0.15f);
      if (stage++ == 80) {
        boolean pass = st.gpu && submitted >= 30 && st.frames >= submitted * 0.8 && pendingAfter == 0 && greenOk > 0.8 && greenBad < greenOk - 0.3;
        String json = String.format(Locale.US,
            "{\"test\":\"simulación en vivo\",\"pass\":%b,\"gpu\":%b,\"submitted\":%d,\"integrated\":%d,\"integrateMs\":%.2f,"
                + "\"pendingAfter1s\":%d,\"blocks\":%d,\"tris\":%d,\"semaforoVerdeOk\":%.3f,\"semaforoRojoOk\":%.3f,"
                + "\"semaforoVerdeMal\":%.3f,\"semaforoRojoMal\":%.3f,\"meshParts\":%d}",
            pass, st.gpu, submitted, st.frames, st.integrateMs, pendingAfter, st.blocks, st.tris, greenOk, redOk, greenBad, redBad, meshes.meshCount());
        Log.i(TAG, json);
        runOnUiThread(() -> info.setText(json.replace(",", ", ")));
      }
      return;
    }
    final String tx = text;
    runOnUiThread(() -> info.setText(tx));
  }
}
