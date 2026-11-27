package org.frost.vision.ui;

import android.app.Activity;
import android.opengl.GLES20;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import org.frost.vision.core.DepthFrame;
import org.frost.vision.core.FrameLog;
import org.frost.vision.core.Fusion;
import org.frost.vision.core.Mat4;
import org.frost.vision.core.MeshPart;
import org.frost.vision.core.Params;
import org.frost.vision.core.TestScene;
import org.frost.vision.core.Volume;
import org.frost.vision.gpu.EglThread;
import org.frost.vision.gpu.GpuFusion;
import org.frost.vision.store.ScanStore;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Pruebas en el dispositivo: integración GPU contra la referencia CPU sobre la escena sintética y
 * sobre la última grabación (.frames). Resultado en pantalla y en logcat (etiqueta VISION_TEST,
 * una línea JSON por prueba). Lanzar: adb shell am start -n org.frost.vision/.ui.TestsActivity
 *
 * Criterio (PLAN F1): ≥ 99 % de los vértices GPU a menos de 1 voxel de la malla CPU.
 */
public final class TestsActivity extends Activity {
  static final String TAG = "VISION_TEST";
  private TextView out;
  private EglThread thread;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    ScrollView sv = new ScrollView(this);
    sv.setBackgroundColor(Ui.BG);
    out = Ui.label(this, "Pruebas GPU contra CPU…\n", 12, Ui.TEXT, false);
    out.setTypeface(android.graphics.Typeface.MONOSPACE);
    int p = Ui.dp(this, 14);
    out.setPadding(p, Ui.dp(this, 36), p, p);
    sv.addView(out);
    Ui.insets(sv, 0);
    setContentView(sv);
    thread = new EglThread("pruebas").begin();
    thread.post(this::runAll);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    thread.quit();
  }

  private void print(String s) {
    Log.i(TAG, s);
    runOnUiThread(() -> out.append(s + "\n"));
  }

  private void runAll() {
    print("GLES " + thread.gles + (GpuFusion.supported(thread.gles) ? " (compute OK)" : " (sin compute: no hay prueba GPU)"));
    if (!GpuFusion.supported(thread.gles)) return;
    List<DepthFrame> synth = new ArrayList<>();
    for (double pitch : new double[] {-0.5, -0.15}) {
      for (double yaw = -1.4; yaw <= 1.4001; yaw += 0.2) {
        DepthFrame f = TestScene.room(Mat4.pose(yaw, pitch, 0, 1.3, 0));
        synth.add(f);
        synth.add(f);
      }
    }
    compare("sintética", synth, new Params());
    File[] logs = new ScanStore(this).recordingFiles(".frames");
    if (logs.length == 0) {
      print("{\"test\":\"grabación\",\"skip\":\"no hay grabaciones .frames (Grabar en Nuevo escaneo)\"}");
    } else {
      List<DepthFrame> rec = new ArrayList<>();
      try (FileInputStream in = new FileInputStream(logs[0])) {
        FrameLog.Reader r = new FrameLog.Reader(in);
        DepthFrame f;
        while ((f = r.next()) != null && rec.size() < 300) rec.add(f);
      } catch (Exception e) {
        print("{\"test\":\"grabación\",\"error\":\"" + e.getMessage() + "\"}");
      }
      if (!rec.isEmpty()) compare("grabación " + logs[0].getName(), rec, new Params());
    }
    print("FIN");
  }

  private void compare(String name, List<DepthFrame> frames, Params params) {
    Fusion gf = new Fusion(params), cf = new Fusion(params);
    GpuFusion gpu = new GpuFusion(getAssets(), gf, 2048);
    double gpuMs = 0, gpuMax = 0, cpuMs = 0, syncMs = 0, prepMs = 0;
    double[] tot = new double[frames.size()], touch = new double[frames.size()], slots = new double[frames.size()];
    int fi = 0;
    for (DepthFrame f : frames) {
      long t0 = System.nanoTime();
      gpu.integrate(f);
      GLES20.glFinish();
      double g = (System.nanoTime() - t0) / 1e6;
      gpuMs += g;
      prepMs += gpu.lastCpuMs;
      tot[fi] = g; touch[fi] = gpu.lastTouchMs; slots[fi] = gpu.lastSlotsMs; fi++;
      gpuMax = Math.max(gpuMax, g);
      long t1 = System.nanoTime();
      cf.integrateCpu(f);
      cpuMs += (System.nanoTime() - t1) / 1e6;
    }
    long t2 = System.nanoTime();
    gpu.sync();
    syncMs = (System.nanoTime() - t2) / 1e6;
    // Voxels: misma TSDF salvo redondeo de coma flotante.
    int both = 0, close = 0, onlyOne = 0;
    float maxDt = 0;
    for (Volume.Block cb : cf.volume.blocks.values()) {
      Volume.Block gb = gf.volume.get(cb.key);
      for (int i = 0; i < Volume.B3; i++) {
        boolean wc = cb.w[i] > 0, wg = gb != null && gb.w[i] > 0;
        if (wc && wg) {
          both++;
          float dt = Math.abs(cb.t[i] - gb.t[i]);
          maxDt = Math.max(maxDt, dt);
          if (dt < 1e-3f) close++;
        } else if (wc || wg) onlyOne++;
      }
    }
    List<MeshPart> gm = gf.allMeshes(), cm = cf.allMeshes();
    float voxel = params.voxel;
    HashMap<Long, List<float[]>> grid = new HashMap<>();
    int cv = 0;
    for (MeshPart m : cm) for (int i = 0; i < m.positions.length; i += 3) {
      float[] v = {m.positions[i], m.positions[i + 1], m.positions[i + 2]};
      grid.computeIfAbsent(cell(v, voxel), k -> new ArrayList<>()).add(v);
      cv++;
    }
    int gv = 0, near = 0;
    for (MeshPart m : gm) for (int i = 0; i < m.positions.length; i += 3) {
      gv++;
      float x = m.positions[i], y = m.positions[i + 1], z = m.positions[i + 2];
      int cx = (int) Math.floor(x / voxel), cy = (int) Math.floor(y / voxel), cz = (int) Math.floor(z / voxel);
      boolean ok = false;
      for (int dx = -1; dx <= 1 && !ok; dx++) for (int dy = -1; dy <= 1 && !ok; dy++) for (int dz = -1; dz <= 1 && !ok; dz++) {
        List<float[]> l = grid.get(Volume.key(cx + dx, cy + dy, cz + dz));
        if (l == null) continue;
        for (float[] v : l) {
          float ex = v[0] - x, ey = v[1] - y, ez = v[2] - z;
          if (ex * ex + ey * ey + ez * ez < voxel * voxel) { ok = true; break; }
        }
      }
      if (ok) near++;
    }
    double frac = gv > 0 ? near / (double) gv : 0;
    boolean pass = frac >= 0.99 && gv > 0 && Math.abs(gv - cv) <= 0.02 * cv;
    print(String.format(Locale.US,
        "{\"test\":\"%s\",\"frames\":%d,\"pass\":%b,\"gpuVertsNear\":%.4f,\"gpuVerts\":%d,\"cpuVerts\":%d,"
            + "\"voxelsBoth\":%d,\"voxelsClose\":%.4f,\"voxelsOnlyOne\":%d,\"maxDt\":%.5f,"
            + "\"gpuMsPerFrame\":%.2f,\"gpuMsMax\":%.2f,\"prepMsPerFrame\":%.2f,\"cpuMsPerFrame\":%.2f,\"syncMs\":%.1f,\"blocks\":%d,\"dropped\":%d}",
        name, frames.size(), pass, frac, gv, cv, both, both > 0 ? close / (double) both : 0, onlyOne, maxDt,
        gpuMs / frames.size(), gpuMax, prepMs / frames.size(), cpuMs / frames.size(), syncMs, gf.volume.size(), gpu.dropped));
    print(String.format(Locale.US, "{\"test\":\"%s tiempos\",\"medianMs\":%.2f,\"p90Ms\":%.2f,\"touchMedianMs\":%.2f,\"slotsMedianMs\":%.2f}",
        name, pct(tot, 0.5), pct(tot, 0.9), pct(touch, 0.5), pct(slots, 0.5)));
    gpu.release();
  }

  private static double pct(double[] a, double q) {
    double[] c = a.clone();
    java.util.Arrays.sort(c);
    return c.length == 0 ? 0 : c[Math.min(c.length - 1, (int) (q * c.length))];
  }

  private static long cell(float[] v, float voxel) {
    return Volume.key((int) Math.floor(v[0] / voxel), (int) Math.floor(v[1] / voxel), (int) Math.floor(v[2] / voxel));
  }
}
