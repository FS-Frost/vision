package org.frost.vision.gpu;

import android.content.res.AssetManager;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;
import android.util.Log;

import org.frost.vision.core.DepthFrame;
import org.frost.vision.core.Fusion;
import org.frost.vision.core.Integrator;
import org.frost.vision.core.Params;
import org.frost.vision.core.Volume;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Integración TSDF en GPU (compute GLES 3.1) sobre un pool de bloques en SSBO.
 *
 * - La CPU decide qué bloques toca cada frame ({@link Integrator#touchedBlocks}) y les asigna un
 *   hueco del pool (los huecos no se liberan: un escaneo solo crece). Al asignar, sube el contenido
 *   del bloque del espejo CPU (vacío, o el volumen congelado del escaneo que se continúa).
 * - La GPU integra (assets/shaders/integrate.comp, misma aritmética que {@link Integrator}).
 * - {@link #sync()} copia a la CPU los bloques que la GPU cambió (gather.comp + mapeo) y los marca
 *   sucios en el espejo: el mallado (Surface Nets), el guardado y la relocalización trabajan sobre
 *   el espejo con el código de core/, igual que en la referencia CPU.
 *
 * Todo se llama desde el hilo que tiene el contexto EGL ({@link EglThread}).
 */
public final class GpuFusion {
  static final int BLOCK_BYTES = Volume.B3 * 4;
  static final int GATHER_MAX = 512;

  public final Fusion fusion;
  private final int progIntegrate, progGather;
  private int tBuf, wBuf, cBuf, activeBuf, slotsBuf, stagingBuf;
  private int capacity, used;
  private final int maxCapacity;
  private int activeCap;
  private int texDepth, texConf, texColor, texW, texH, colW, colH;
  private FloatBuffer depthUp, confUp;
  private ByteBuffer colorUp;
  private final LinkedHashSet<Long> gpuDirty = new LinkedHashSet<>();
  private final List<Volume.Block> slotted = new ArrayList<>();
  private final int uInv, uK, uCK, uSize, uColorSize, uHasColor, uP, uP2, uDepth, uConf, uColor;
  /** Bloques que no cupieron en el pool (diagnóstico). */
  public int dropped;
  /** Tiempos del último frame (ms): preparación en CPU, envío a GPU. */
  public double lastCpuMs, lastGpuMs;

  public static boolean supported(int gles) { return gles >= 31; }

  public GpuFusion(AssetManager am, Fusion fusion, int initialBlocks) {
    this.fusion = fusion;
    progIntegrate = Gl.compute(am, "shaders/integrate.comp");
    progGather = Gl.compute(am, "shaders/gather.comp");
    int[] v = new int[1];
    GLES20.glGetIntegerv(GLES31.GL_MAX_SHADER_STORAGE_BLOCK_SIZE, v, 0);
    int maxBytes = v[0] <= 0 ? (64 << 20) : v[0];
    maxCapacity = Math.max(1024, Math.min(maxBytes / BLOCK_BYTES, 32768));
    Log.i(Gl.TAG, "GpuFusion: SSBO max " + (maxBytes >> 20) + " MB -> " + maxCapacity + " bloques");
    capacity = Math.min(initialBlocks, maxCapacity);
    tBuf = alloc(capacity * BLOCK_BYTES);
    wBuf = alloc(capacity * BLOCK_BYTES);
    cBuf = alloc(capacity * BLOCK_BYTES);
    activeCap = 2048;
    activeBuf = alloc(activeCap * 16);
    slotsBuf = alloc(GATHER_MAX * 4);
    stagingBuf = alloc(GATHER_MAX * BLOCK_BYTES * 3);
    uInv = loc(progIntegrate, "uInv"); uK = loc(progIntegrate, "uK"); uCK = loc(progIntegrate, "uCK");
    uSize = loc(progIntegrate, "uSize"); uColorSize = loc(progIntegrate, "uColorSize");
    uHasColor = loc(progIntegrate, "uHasColor"); uP = loc(progIntegrate, "uP"); uP2 = loc(progIntegrate, "uP2");
    uDepth = loc(progIntegrate, "uDepth"); uConf = loc(progIntegrate, "uConf"); uColor = loc(progIntegrate, "uColor");
    Gl.check("GpuFusion init");
    // Si el espejo ya tiene bloques (escaneo continuado) se suben al tocarlos por primera vez.
    for (Volume.Block b : fusion.volume.blocks.values()) b.slot = -1;
  }

  private static int loc(int prog, String name) { return GLES20.glGetUniformLocation(prog, name); }

  private static int alloc(int bytes) {
    int id = Gl.genBuffer();
    GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, id);
    GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, null, GLES20.GL_DYNAMIC_DRAW);
    return id;
  }

  public int usedBlocks() { return used; }
  public int capacityBlocks() { return capacity; }

  /** Cambió el volumen del espejo (p. ej. se cargó un escaneo): se vacía el pool. */
  public void reset() {
    for (Volume.Block b : slotted) b.slot = -1;
    slotted.clear();
    for (Volume.Block b : fusion.volume.blocks.values()) b.slot = -1;
    used = 0;
    gpuDirty.clear();
  }

  private boolean grow(int need) {
    if (need <= capacity) return true;
    if (capacity >= maxCapacity) return false;
    int cap = capacity;
    while (cap < need) cap *= 2;
    cap = Math.min(cap, maxCapacity);
    int[] old = {tBuf, wBuf, cBuf};
    int[] nu = new int[3];
    for (int i = 0; i < 3; i++) {
      nu[i] = alloc(cap * BLOCK_BYTES);
      GLES20.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, old[i]);
      GLES20.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, nu[i]);
      GLES30.glCopyBufferSubData(GLES30.GL_COPY_READ_BUFFER, GLES30.GL_COPY_WRITE_BUFFER, 0, 0, used * BLOCK_BYTES);
      Gl.deleteBuffer(old[i]);
    }
    tBuf = nu[0]; wBuf = nu[1]; cBuf = nu[2];
    Log.i(Gl.TAG, "GpuFusion: pool " + capacity + " -> " + cap + " bloques");
    capacity = cap;
    return need <= capacity;
  }

  /** Asigna huecos a los bloques nuevos (contiguos) y sube su contenido del espejo. */
  private List<Volume.Block> slots(List<Long> keys) {
    Volume vol = fusion.volume;
    List<Volume.Block> out = new ArrayList<>(keys.size());
    List<Volume.Block> fresh = new ArrayList<>();
    for (long k : keys) {
      Volume.Block b = vol.getOrCreate(k);
      if (b.slot < 0) fresh.add(b);
      out.add(b);
    }
    if (!fresh.isEmpty()) {
      int room = grow(used + fresh.size()) ? fresh.size() : Math.max(0, capacity - used);
      if (room < fresh.size()) {
        dropped += fresh.size() - room;
        Log.w(Gl.TAG, "GpuFusion: pool lleno, se descartan " + (fresh.size() - room) + " bloques");
      }
      int first = used;
      FloatBuffer tf = Gl.bytes(room * BLOCK_BYTES).asFloatBuffer();
      FloatBuffer wf = Gl.bytes(room * BLOCK_BYTES).asFloatBuffer();
      IntBuffer cf = Gl.bytes(room * BLOCK_BYTES).asIntBuffer();
      for (int i = 0; i < room; i++) {
        Volume.Block b = fresh.get(i);
        b.slot = used++;
        slotted.add(b);
        tf.put(b.t); wf.put(b.w); cf.put(b.c);
      }
      tf.position(0); wf.position(0); cf.position(0);
      if (room > 0) {
        sub(tBuf, first, room, tf);
        sub(wBuf, first, room, wf);
        sub(cBuf, first, room, cf);
      }
    }
    List<Volume.Block> ok = new ArrayList<>(out.size());
    for (Volume.Block b : out) if (b.slot >= 0) ok.add(b);
    return ok;
  }

  private static void sub(int buf, int firstSlot, int n, java.nio.Buffer data) {
    GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf);
    GLES20.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, firstSlot * BLOCK_BYTES, n * BLOCK_BYTES, data);
  }

  private void textures(DepthFrame f, Params p) {
    if (f.width != texW || f.height != texH) {
      Gl.deleteTexture(texDepth); Gl.deleteTexture(texConf);
      texW = f.width; texH = f.height;
      texDepth = Gl.texture2D(GLES30.GL_R32F, texW, texH);
      texConf = Gl.texture2D(GLES30.GL_R32F, texW, texH);
      depthUp = Gl.bytes(texW * texH * 4).asFloatBuffer();
      confUp = Gl.bytes(texW * texH * 4).asFloatBuffer();
    }
    boolean[] valid = f.validMask(p.edgeJump);
    int n = texW * texH;
    depthUp.position(0); confUp.position(0);
    for (int i = 0; i < n; i++) {
      depthUp.put(valid[i] ? f.depth[i] : 0f);
      confUp.put(f.conf(i));
    }
    depthUp.position(0); confUp.position(0);
    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texDepth);
    GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, texW, texH, GLES30.GL_RED, GLES20.GL_FLOAT, depthUp);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texConf);
    GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, texW, texH, GLES30.GL_RED, GLES20.GL_FLOAT, confUp);
    if (f.color != null) {
      if (f.colorWidth != colW || f.colorHeight != colH) {
        Gl.deleteTexture(texColor);
        colW = f.colorWidth; colH = f.colorHeight;
        texColor = Gl.texture2D(GLES30.GL_RGBA8, colW, colH);
        colorUp = Gl.bytes(colW * colH * 4);
      }
      byte[] rgb = f.color;
      colorUp.position(0);
      for (int i = 0, m = colW * colH; i < m; i++) {
        colorUp.put(rgb[i * 3]); colorUp.put(rgb[i * 3 + 1]); colorUp.put(rgb[i * 3 + 2]); colorUp.put((byte) -1);
      }
      colorUp.position(0);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texColor);
      GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, colW, colH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, colorUp);
    }
  }

  /** Integra un frame de la sesión (se le aplica la alineación de la fusión). Devuelve bloques procesados. */
  public int integrate(DepthFrame frame) {
    long t0 = System.nanoTime();
    Params p = fusion.params;
    DepthFrame f = fusion.aligned(frame);
    List<Long> keys = Integrator.touchedBlocks(f, p, 2);
    List<Volume.Block> blocks = slots(keys);
    int n = blocks.size();
    if (n == 0) return 0;
    if (n > activeCap) {
      Gl.deleteBuffer(activeBuf);
      while (activeCap < n) activeCap *= 2;
      activeBuf = alloc(activeCap * 16);
    }
    IntBuffer act = Gl.bytes(n * 16).asIntBuffer();
    for (Volume.Block b : blocks) {
      act.put(b.bx).put(b.by).put(b.bz).put(b.slot);
      gpuDirty.add(b.key);
    }
    act.position(0);
    GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, activeBuf);
    GLES20.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, n * 16, act);
    textures(f, p);
    long t1 = System.nanoTime();

    GLES20.glUseProgram(progIntegrate);
    GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, tBuf);
    GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, wBuf);
    GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, cBuf);
    GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, activeBuf);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texDepth);
    GLES20.glUniform1i(uDepth, 0);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texConf);
    GLES20.glUniform1i(uConf, 1);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, f.color != null ? texColor : texDepth);
    GLES20.glUniform1i(uColor, 2);
    GLES20.glUniformMatrix4fv(uInv, 1, false, org.frost.vision.core.Mat4.invertRigid(f.pose), 0);
    GLES20.glUniform4f(uK, f.fx, f.fy, f.cx, f.cy);
    if (f.color != null) {
      float[] ck = f.colorK();
      GLES20.glUniform4f(uCK, ck[0], ck[1], ck[2], ck[3]);
      GLES20.glUniform2i(uColorSize, f.colorWidth, f.colorHeight);
    } else {
      GLES20.glUniform4f(uCK, 0, 0, 0, 0);
      GLES20.glUniform2i(uColorSize, 0, 0);
    }
    GLES20.glUniform2i(uSize, f.width, f.height);
    GLES20.glUniform1i(uHasColor, f.color != null ? 1 : 0);
    GLES20.glUniform4f(uP, p.voxel, p.trunc, p.minDepth, p.maxDepth);
    GLES20.glUniform4f(uP2, p.nearFull, p.maxWeight, p.minConfidence, 0);
    GLES31.glDispatchCompute(n, 1, 1);
    GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    long t2 = System.nanoTime();
    lastCpuMs = (t1 - t0) / 1e6;
    lastGpuMs = (t2 - t1) / 1e6;
    return n;
  }

  public int gpuDirtyCount() { return gpuDirty.size(); }

  /** Copia al espejo CPU los bloques cambiados en la GPU y los marca sucios (para mallar). */
  public int sync() {
    int total = 0;
    Volume vol = fusion.volume;
    while (!gpuDirty.isEmpty()) {
      List<Volume.Block> batch = new ArrayList<>(GATHER_MAX);
      java.util.Iterator<Long> it = gpuDirty.iterator();
      while (it.hasNext() && batch.size() < GATHER_MAX) {
        Volume.Block b = vol.get(it.next());
        it.remove();
        if (b != null && b.slot >= 0) batch.add(b);
      }
      int n = batch.size();
      if (n == 0) continue;
      IntBuffer sl = Gl.bytes(n * 4).asIntBuffer();
      for (Volume.Block b : batch) sl.put(b.slot);
      sl.position(0);
      GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, slotsBuf);
      GLES20.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, n * 4, sl);
      GLES20.glUseProgram(progGather);
      GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, tBuf);
      GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, wBuf);
      GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, cBuf);
      GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 4, slotsBuf);
      GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 5, stagingBuf);
      GLES31.glDispatchCompute(n, 1, 1);
      GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT | GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
      GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, stagingBuf);
      ByteBuffer mapped = (ByteBuffer) GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0,
          n * BLOCK_BYTES * 3, GLES30.GL_MAP_READ_BIT);
      if (mapped == null) {
        Gl.check("glMapBufferRange");
        throw new RuntimeException("glMapBufferRange devolvió null");
      }
      mapped.order(java.nio.ByteOrder.nativeOrder());
      FloatBuffer fb = mapped.asFloatBuffer();
      IntBuffer ib = mapped.asIntBuffer();
      for (int i = 0; i < n; i++) {
        Volume.Block b = batch.get(i);
        int base = i * Volume.B3 * 3;
        fb.position(base); fb.get(b.t);
        fb.position(base + Volume.B3); fb.get(b.w);
        ib.position(base + 2 * Volume.B3); ib.get(b.c);
        vol.markDirty(b.key);
      }
      GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER);
      total += n;
    }
    return total;
  }

  public void release() {
    Gl.deleteBuffer(tBuf); Gl.deleteBuffer(wBuf); Gl.deleteBuffer(cBuf);
    Gl.deleteBuffer(activeBuf); Gl.deleteBuffer(slotsBuf); Gl.deleteBuffer(stagingBuf);
    Gl.deleteTexture(texDepth); Gl.deleteTexture(texConf); Gl.deleteTexture(texColor);
    GLES20.glDeleteProgram(progIntegrate);
    GLES20.glDeleteProgram(progGather);
    reset();
  }
}
