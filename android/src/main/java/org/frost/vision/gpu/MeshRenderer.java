package org.frost.vision.gpu;

import android.content.res.AssetManager;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.Matrix;

import org.frost.vision.core.DepthFrame;
import org.frost.vision.core.Mat4;
import org.frost.vision.core.MeshPart;

import java.nio.FloatBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Dibuja superceldas de malla (un VBO + IBO por supercelda). Las actualizaciones llegan de otro
 * hilo con {@link #enqueue} y se suben a la GPU en {@link #draw} (hilo de render).
 */
public final class MeshRenderer {
  public static final int MODE_AR = 0, MODE_COLOR = 1, MODE_SHADED = 2, MODE_SEMAFORO = 3, MODE_TEXTURE = 4;

  static final class Gpu {
    int vbo, ibo, count, image = -1;
  }

  private final Map<Long, Gpu> meshes = new HashMap<>();
  private final ConcurrentLinkedQueue<MeshPart> queue = new ConcurrentLinkedQueue<>();
  private volatile boolean clearRequested;
  private final int prog, uMvp, uModel, uMode, uAlpha, uLive, uLiveInv, uLiveK, uLiveSize, uTol, uTex, uHasTex;
  private volatile List<android.graphics.Bitmap> pendingImages;
  private int[] textures = new int[0];
  private int liveTex, liveW, liveH;
  private FloatBuffer liveUp;
  private DepthFrame liveFrame;
  private volatile DepthFrame pendingLive;
  public int triangles;

  public MeshRenderer(AssetManager am) {
    prog = Gl.program(am, "shaders/mesh.vert", "shaders/mesh.frag");
    uMvp = GLES20.glGetUniformLocation(prog, "uMvp");
    uModel = GLES20.glGetUniformLocation(prog, "uModel");
    uMode = GLES20.glGetUniformLocation(prog, "uMode");
    uAlpha = GLES20.glGetUniformLocation(prog, "uAlpha");
    uLive = GLES20.glGetUniformLocation(prog, "uLive");
    uLiveInv = GLES20.glGetUniformLocation(prog, "uLiveInv");
    uLiveK = GLES20.glGetUniformLocation(prog, "uLiveK");
    uLiveSize = GLES20.glGetUniformLocation(prog, "uLiveSize");
    uTol = GLES20.glGetUniformLocation(prog, "uTol");
    uTex = GLES20.glGetUniformLocation(prog, "uTex");
    uHasTex = GLES20.glGetUniformLocation(prog, "uHasTex");
  }

  /** Encola mallas (cualquier hilo). Una malla vacía borra su supercelda. */
  public void enqueue(Collection<MeshPart> parts) { queue.addAll(parts); }

  public void enqueue(MeshPart part) { queue.add(part); }

  /** Borra todo en el próximo dibujo (cualquier hilo). */
  public void clear() {
    queue.clear();
    clearRequested = true;
  }

  /** Profundidad en vivo para el semáforo (cualquier hilo). */
  public void setLive(DepthFrame f) { pendingLive = f; }

  public int meshCount() { return meshes.size(); }

  /** Fotos clave para MODE_TEXTURE, en el orden de MeshPart.image (cualquier hilo). No se reciclan: si el contexto GL se recrea, se vuelven a subir. */
  public void setImages(List<android.graphics.Bitmap> images) { pendingImages = images; }

  public boolean hasTextures() { return textures.length > 0 || pendingImages != null; }

  private void upload() {
    if (clearRequested) {
      clearRequested = false;
      for (Gpu g : meshes.values()) { Gl.deleteBuffer(g.vbo); Gl.deleteBuffer(g.ibo); }
      meshes.clear();
    }
    MeshPart m;
    int budget = 64;
    while (budget-- > 0 && (m = queue.poll()) != null) {
      Gpu g = meshes.get(m.key);
      if (m.isEmpty()) {
        if (g != null) { Gl.deleteBuffer(g.vbo); Gl.deleteBuffer(g.ibo); meshes.remove(m.key); }
        continue;
      }
      if (g == null) {
        g = new Gpu();
        g.vbo = Gl.genBuffer();
        g.ibo = Gl.genBuffer();
        meshes.put(m.key, g);
      }
      int nv = m.vertexCount();
      float[] inter = new float[nv * 11];
      for (int i = 0; i < nv; i++) {
        System.arraycopy(m.positions, i * 3, inter, i * 11, 3);
        System.arraycopy(m.normals, i * 3, inter, i * 11 + 3, 3);
        System.arraycopy(m.colors, i * 3, inter, i * 11 + 6, 3);
        if (m.textured()) System.arraycopy(m.uvs, i * 2, inter, i * 11 + 9, 2);
      }
      g.image = m.textured() ? m.image : -1;
      GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, g.vbo);
      GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, inter.length * 4, Gl.floats(inter), GLES20.GL_STATIC_DRAW);
      GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, g.ibo);
      GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, m.indices.length * 4, Gl.ints(m.indices), GLES20.GL_STATIC_DRAW);
      g.count = m.indices.length;
    }
    List<android.graphics.Bitmap> imgs = pendingImages;
    if (imgs != null) {
      pendingImages = null;
      for (int t : textures) Gl.deleteTexture(t);
      textures = new int[imgs.size()];
      for (int i = 0; i < imgs.size(); i++) {
        android.graphics.Bitmap b = imgs.get(i);
        if (b == null) continue;
        textures[i] = Gl.genTexture();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
      }
    }
    DepthFrame lf = pendingLive;
    if (lf != null && lf != liveFrame) {
      liveFrame = lf;
      if (lf.width != liveW || lf.height != liveH) {
        Gl.deleteTexture(liveTex);
        liveW = lf.width; liveH = lf.height;
        liveTex = Gl.texture2D(GLES30.GL_R32F, liveW, liveH);
        liveUp = Gl.bytes(liveW * liveH * 4).asFloatBuffer();
      }
      boolean[] valid = lf.validMask(0.08f);
      liveUp.position(0);
      for (int i = 0; i < liveW * liveH; i++) liveUp.put(valid[i] && lf.conf(i) >= 0.15f ? lf.depth[i] : 0f);
      liveUp.position(0);
      GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, liveTex);
      GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, liveW, liveH, GLES30.GL_RED, GLES20.GL_FLOAT, liveUp);
    }
  }

  /**
   * Dibuja todas las mallas.
   * @param viewProj proyección x vista (column-major)
   * @param model transformación del modelo (p. ej. guardado -> sesión al continuar) o null
   */
  public void draw(float[] viewProj, float[] model, int mode, float alpha, float tol) {
    upload();
    if (meshes.isEmpty()) return;
    float[] M = model != null ? model : Mat4.identity();
    float[] mvp = new float[16];
    Matrix.multiplyMM(mvp, 0, viewProj, 0, M, 0);
    GLES20.glUseProgram(prog);
    GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
    GLES20.glUniformMatrix4fv(uModel, 1, false, M, 0);
    GLES20.glUniform1i(uMode, mode);
    GLES20.glUniform1f(uAlpha, alpha);
    GLES20.glUniform1f(uTol, tol);
    if (mode == MODE_SEMAFORO && liveFrame != null) {
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, liveTex);
      GLES20.glUniform1i(uLive, 0);
      GLES20.glUniformMatrix4fv(uLiveInv, 1, false, Mat4.invertRigid(liveFrame.pose), 0);
      GLES20.glUniform4f(uLiveK, liveFrame.fx, liveFrame.fy, liveFrame.cx, liveFrame.cy);
      GLES20.glUniform2i(uLiveSize, liveW, liveH);
    } else {
      GLES20.glUniform2i(uLiveSize, 0, 0);
    }
    GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    GLES20.glDepthMask(true);
    if (alpha < 1f) {
      GLES20.glEnable(GLES20.GL_BLEND);
      GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
    } else {
      GLES20.glDisable(GLES20.GL_BLEND);
    }
    GLES20.glEnableVertexAttribArray(0);
    GLES20.glEnableVertexAttribArray(1);
    GLES20.glEnableVertexAttribArray(2);
    GLES20.glEnableVertexAttribArray(3);
    GLES20.glUniform1i(uTex, 1);
    int tris = 0;
    for (Gpu g : meshes.values()) {
      GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, g.vbo);
      GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 44, 0);
      GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, 44, 12);
      GLES20.glVertexAttribPointer(2, 3, GLES20.GL_FLOAT, false, 44, 24);
      GLES20.glVertexAttribPointer(3, 2, GLES20.GL_FLOAT, false, 44, 36);
      boolean tex = mode == MODE_TEXTURE && g.image >= 0 && g.image < textures.length && textures[g.image] != 0;
      GLES20.glUniform1i(uHasTex, tex ? 1 : 0);
      if (tex) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[g.image]);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
      }
      GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, g.ibo);
      GLES20.glDrawElements(GLES20.GL_TRIANGLES, g.count, GLES20.GL_UNSIGNED_INT, 0);
      tris += g.count / 3;
    }
    triangles = tris;
    GLES20.glDisableVertexAttribArray(0);
    GLES20.glDisableVertexAttribArray(1);
    GLES20.glDisableVertexAttribArray(2);
    GLES20.glDisableVertexAttribArray(3);
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
    GLES20.glDisable(GLES20.GL_BLEND);
  }
}
