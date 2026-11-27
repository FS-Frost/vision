package org.frost.vision.ui;

import android.app.Activity;
import android.opengl.GLES20;
import android.opengl.GLES31;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.CameraConfig;
import com.google.ar.core.Config;
import com.google.ar.core.Session;

import org.frost.vision.gpu.EglThread;
import org.frost.vision.store.ScanStore;

import java.io.File;
import java.util.Locale;

/** Diagnóstico del dispositivo (equivale a diag/ de la web). Salida también en logcat: VISION_DIAG. */
public final class DiagActivity extends Activity {
  static final String TAG = "VISION_DIAG";
  private TextView out;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    ScrollView sv = new ScrollView(this);
    sv.setBackgroundColor(Ui.BG);
    out = Ui.label(this, "", 12, Ui.TEXT, false);
    out.setTypeface(android.graphics.Typeface.MONOSPACE);
    int p = Ui.dp(this, 14);
    out.setPadding(p, Ui.dp(this, 36), p, p);
    sv.addView(out);
    Ui.insets(sv, 0);
    setContentView(sv);
    print("Dispositivo: " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
    print("ABI: " + android.text.TextUtils.join(", ", Build.SUPPORTED_ABIS));
    print("Núcleos: " + Runtime.getRuntime().availableProcessors() + " · memoria máx. Java " + Runtime.getRuntime().maxMemory() / 1048576 + " MB");
    EglThread t = new EglThread("diag").begin();
    t.post(() -> {
      print("GLES: " + t.gles / 10 + "." + t.gles % 10);
      print("GPU: " + GLES20.glGetString(GLES20.GL_RENDERER) + " · " + GLES20.glGetString(GLES20.GL_VERSION));
      if (t.gles >= 31) {
        int[] v = new int[3];
        GLES20.glGetIntegerv(GLES31.GL_MAX_SHADER_STORAGE_BLOCK_SIZE, v, 0);
        print("SSBO máx.: " + v[0] / 1048576 + " MB");
        GLES20.glGetIntegerv(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, v, 0);
        print("Invocaciones por grupo: " + v[0]);
        for (int i = 0; i < 3; i++) GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, i, v, i);
        print("Grupos máx.: " + v[0] + " x " + v[1] + " x " + v[2]);
      }
      t.quit();
      runOnUiThread(this::arcore);
    });
  }

  private void arcore() {
    try {
      ArCoreApk.Availability a = ArCoreApk.getInstance().checkAvailability(this);
      print("ARCore: " + a);
      if (!a.isSupported()) return;
      Session s = new Session(this);
      print("Profundidad raw: " + (s.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) ? "sí" : "no")
          + " · automática: " + (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC) ? "sí" : "no"));
      for (CameraConfig c : s.getSupportedCameraConfigs()) {
        print(String.format(Locale.US, "Cámara %s %s: CPU %s, textura %s, fps %s, profundidad %s",
            c.getCameraId(), c.getFacingDirection(), c.getImageSize(), c.getTextureSize(), c.getFpsRange(), c.getDepthSensorUsage()));
      }
      s.close();
    } catch (Exception e) {
      print("ARCore: error " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
    ScanStore st = new ScanStore(this);
    print("Escaneos guardados: " + st.list().size());
    long rec = 0;
    File[] fs = st.recordings.listFiles();
    if (fs != null) for (File f : fs) rec += f.length();
    print(String.format(Locale.US, "Grabaciones: %d archivos, %.1f MB", fs == null ? 0 : fs.length, rec / 1048576.0));
  }

  private void print(String s) {
    Log.i(TAG, s);
    runOnUiThread(() -> out.append(s + "\n"));
  }
}
