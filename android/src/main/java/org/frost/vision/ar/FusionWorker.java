package org.frost.vision.ar;

import android.content.res.AssetManager;
import android.util.Log;

import org.frost.vision.core.DepthFrame;
import org.frost.vision.core.FrameLog;
import org.frost.vision.core.Fusion;
import org.frost.vision.core.MeshPart;
import org.frost.vision.core.Params;
import org.frost.vision.gpu.EglThread;
import org.frost.vision.gpu.Gl;
import org.frost.vision.gpu.GpuFusion;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Hilo de fusión: integra frames (GPU si hay GLES 3.1, si no la referencia CPU), sincroniza el
 * espejo, malla y entrega superceldas al render. Todo acceso a {@link #fusion} se hace en este
 * hilo con {@link #run}.
 */
public final class FusionWorker {
  public interface Listener {
    /** Superceldas re-malladas (llamado en el hilo de fusión). */
    void onMeshes(List<MeshPart> parts);
  }

  public static final class Stats {
    public volatile boolean gpu;
    public volatile int frames, blocks, pending, verts, tris, gpuBlocks, gpuCapacity, dropped;
    public volatile double integrateMs, cpuPrepMs, syncMs, meshMs;
  }

  public final Fusion fusion;
  public final Stats stats = new Stats();
  private final EglThread thread;
  private GpuFusion gpu;
  private final Listener listener;
  private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
  private volatile boolean busy;
  private volatile boolean paused;
  private long lastSync;
  private DataOutputStream log;
  /** Último frame recibido (marco de la sesión), para la mirilla y el semáforo. */
  public volatile DepthFrame latest;

  public FusionWorker(AssetManager am, Params params, boolean allowGpu, Listener listener) {
    this.fusion = new Fusion(params);
    // Mallado en paralelo: el g54 tiene 8 núcleos (2 grandes + 6 pequeños); el hilo de fusión espera.
    fusion.setMeshThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2)));
    this.listener = listener;
    thread = new EglThread("fusion").begin();
    final boolean[] done = {false};
    thread.post(() -> {
      if (allowGpu && GpuFusion.supported(thread.gles)) {
        try {
          gpu = new GpuFusion(am, fusion, 4096);
          stats.gpu = true;
        } catch (RuntimeException e) {
          Log.e(Gl.TAG, "GpuFusion no disponible, se usa la CPU: " + e.getMessage());
          gpu = null;
        }
      }
      synchronized (done) { done[0] = true; done.notifyAll(); }
    });
    synchronized (done) {
      while (!done[0]) {
        try { done.wait(); } catch (InterruptedException e) { break; }
      }
    }
    ticker.scheduleWithFixedDelay(() -> thread.post(this::tick), 200, 200, TimeUnit.MILLISECONDS);
  }

  public boolean usesGpu() { return gpu != null; }

  /** Ejecuta una tarea en el hilo de fusión. */
  public void run(Runnable r) { thread.post(r); }

  /** Pausa la integración (p. ej. durante la confirmación al continuar). */
  public void setPaused(boolean p) { paused = p; }
  public boolean isPaused() { return paused; }

  /** true si el hilo está libre para un frame nuevo (si no, el frame se descarta). */
  public boolean ready() { return !busy; }

  /** Empieza o termina de registrar los frames que se integran (null = terminar). */
  public void setLog(DataOutputStream out) {
    thread.post(() -> {
      if (log != null) {
        try { log.close(); } catch (IOException ignored) {}
      }
      log = out;
    });
  }

  /** Entrega un frame. Devuelve false si se descartó porque el hilo estaba ocupado. */
  public boolean submit(DepthFrame f) {
    latest = f;
    lastFrameMs = System.currentTimeMillis();
    if (busy || paused) return false;
    busy = true;
    thread.post(() -> {
      try {
        integrateNow(f);
      } finally {
        busy = false;
      }
    });
    return true;
  }

  /** Integra en este hilo (llamar desde run()). */
  public void integrateNow(DepthFrame f) {
    long t0 = System.nanoTime();
    if (gpu != null) {
      gpu.integrate(f);
      stats.cpuPrepMs = gpu.lastCpuMs;
    } else {
      fusion.integrateCpu(f);
    }
    stats.integrateMs = stats.integrateMs * 0.8 + 0.2 * (System.nanoTime() - t0) / 1e6;
    stats.frames++;
    if (log != null) {
      try { FrameLog.write(log, f); } catch (IOException e) { Log.e(Gl.TAG, "registro de frames", e); log = null; }
    }
    long now = System.currentTimeMillis();
    if (now - lastSync > 150) flush(20);
  }

  private volatile long lastFrameMs;
  private volatile boolean draining;

  /**
   * Cada 200 ms: con frames llegando, un tramo corto (no robar tiempo a la integración); parado
   * (> 150 ms sin frames), se malla sin pausa en tramos de 40 ms hasta vaciar la cola
   * (criterio F1: 0 bloques pendientes 1 s después de dejar de moverse).
   */
  private void tick() {
    if (gpu != null && gpu.gpuDirtyCount() == 0 && fusion.pendingCount() == 0) return;
    if (gpu == null && fusion.pendingCount() == 0) return;
    boolean idle = System.currentTimeMillis() - lastFrameMs > 150;
    flush(idle ? 40 : 20);
    if (idle && fusion.pendingCount() > 0 && !draining) {
      draining = true;
      thread.post(() -> { draining = false; tick(); });
    }
  }

  /** Sincroniza GPU -> espejo y malla con presupuesto (ms). */
  public void flush(double budgetMs) {
    lastSync = System.currentTimeMillis();
    long t0 = System.nanoTime();
    if (gpu != null) gpu.sync();
    long t1 = System.nanoTime();
    List<MeshPart> parts = fusion.extract(budgetMs);
    long t2 = System.nanoTime();
    stats.syncMs = (t1 - t0) / 1e6;
    stats.meshMs = (t2 - t1) / 1e6;
    int[] s = fusion.stats();
    stats.blocks = s[0]; stats.pending = s[2]; stats.verts = s[3]; stats.tris = s[4];
    if (gpu != null) { stats.gpuBlocks = gpu.usedBlocks(); stats.gpuCapacity = gpu.capacityBlocks(); stats.dropped = gpu.dropped; }
    if (!parts.isEmpty() && listener != null) listener.onMeshes(parts);
  }

  /** Sincroniza y malla todo (llamar desde run()). */
  public void flushAll() {
    if (gpu != null) gpu.sync();
    while (fusion.pendingCount() > 0) {
      List<MeshPart> parts = fusion.extract(50);
      if (!parts.isEmpty() && listener != null) listener.onMeshes(parts);
    }
    flush(0);
  }

  /** El volumen del espejo cambió (se cargó un escaneo): vaciar el pool de la GPU. */
  public void volumeReplaced() {
    if (gpu != null) gpu.reset();
  }

  public void release() {
    ticker.shutdownNow();
    thread.post(() -> {
      if (log != null) {
        try { log.close(); } catch (IOException ignored) {}
        log = null;
      }
      if (gpu != null) gpu.release();
      gpu = null;
      fusion.release();
    });
    thread.quit();
  }
}
