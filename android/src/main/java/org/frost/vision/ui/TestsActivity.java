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
import org.frost.vision.core.Reloc;
import org.frost.vision.core.ScanMeta;
import org.frost.vision.core.Simplify;
import org.frost.vision.store.ScanSaver;
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
    Fusion gf = compare("sintética", synth, new Params());
    try {
      saveAndReload(gf);
    } catch (Exception e) {
      print("{\"test\":\"guardar\",\"pass\":false,\"error\":\"" + e + "\"}");
    }
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

  /** Guarda con el mismo camino que la app, relee de disco y simula una continuación con anclas. */
  private void saveAndReload(Fusion gf) throws Exception {
    ScanStore store = new ScanStore(this);
    ScanSaver.Input in = new ScanSaver.Input();
    in.name = "Escena de prueba (sintética)";
    in.startPose = Mat4.pose(-1.4, -0.5, 0, 1.3, 0);
    in.arFloor = 0;
    in.anchors = new ArrayList<>();
    float[][] pts = {{0.2f, 0.6f, -0.9f}, {0.7f, 0.6f, -0.9f}, {0.7f, 0.6f, -1.4f}};
    for (float[] q : pts) {
      ScanMeta.Anchor a = new ScanMeta.Anchor();
      a.p = q; a.cam = new float[] {0, 1.3f, 0}; a.camHeight = 1.3f;
      a.dist = (float) Math.sqrt(q[0] * q[0] + (q[1] - 1.3f) * (q[1] - 1.3f) + q[2] * q[2]);
      in.anchors.add(a);
    }
    long t0 = System.nanoTime();
    ScanMeta m = ScanSaver.save(store, gf, in);
    double saveMs = (System.nanoTime() - t0) / 1e6;
    List<MeshPart> mesh = store.meshes(m.id);
    int tris = 0;
    for (MeshPart pm : mesh) tris += pm.triangleCount();
    Fusion re = new Fusion(Params.withVoxel(m.voxel));
    re.setVolume(store.volume(m.id, re.params, true));
    int reVerts = 0, gfVerts = 0;
    for (MeshPart pm : re.allMeshes()) reVerts += pm.vertexCount();
    for (MeshPart pm : gf.allMeshes()) gfVerts += pm.vertexCount();
    ScanMeta back = store.meta(m.id);
    // Continuación: la sesión está girada 0,7 rad y desplazada; las anclas recuperan la pose.
    float[] A = Mat4.yaw(0.7, 0.3, 0, -0.2), Ainv = Mat4.invertRigid(A);
    List<float[]> sv = new ArrayList<>(), ss = new ArrayList<>();
    for (ScanMeta.Anchor a : back.anchors) {
      float[] q = new float[3];
      Mat4.transform(Ainv, a.p[0], a.p[1], a.p[2], q);
      sv.add(a.p); ss.add(q);
    }
    Reloc.AnchorSolution sol = Reloc.solveAnchors(sv, ss);
    boolean anchorsOk = Math.abs(Mat4.angleDiff(sol.yaw, 0.7)) < 1e-3 && sol.maxResidual < 1e-3;
    int simp = Simplify.triangles(Simplify.toBudget(mesh, m.voxel, tris / 3));
    boolean pass = tris == m.tris && back.anchors.size() == 3 && Math.abs(back.floorY) < 0.05f
        && Math.abs(reVerts - gfVerts) <= gfVerts * 0.01 && anchorsOk && simp <= tris / 3;
    print(String.format(Locale.US, "{\"test\":\"guardar, releer y continuar\",\"pass\":%b,\"id\":\"%s\",\"tris\":%d,\"saveMs\":%.0f,"
        + "\"floorY\":%.3f,\"reloadVerts\":%d,\"gpuVerts\":%d,\"anchorYaw\":%.4f,\"anchorResidual\":%.5f,\"simplifiedTris\":%d}",
        pass, m.id, tris, saveMs, back.floorY, reVerts, gfVerts, sol.yaw, sol.maxResidual, simp));
  }

  private Fusion compare(String name, List<DepthFrame> frames, Params params) {
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
    return gf;
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
