package org.frost.vision.gpu;

import android.content.res.AssetManager;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Utilidades de OpenGL ES: shaders desde assets, programas y buffers directos. */
public final class Gl {
  public static final String TAG = "VISION";

  private Gl() {}

  public static String asset(AssetManager am, String path) {
    try (InputStream in = am.open(path)) {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
      return new String(bos.toByteArray(), "UTF-8");
    } catch (IOException e) {
      throw new RuntimeException("no se pudo leer el asset " + path, e);
    }
  }

  public static int shader(int type, String src, String name) {
    int s = GLES20.glCreateShader(type);
    GLES20.glShaderSource(s, src);
    GLES20.glCompileShader(s);
    int[] ok = new int[1];
    GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
    if (ok[0] == 0) {
      String log = GLES20.glGetShaderInfoLog(s);
      GLES20.glDeleteShader(s);
      throw new RuntimeException("shader " + name + ": " + log);
    }
    return s;
  }

  public static int link(String name, int... shaders) {
    int p = GLES20.glCreateProgram();
    for (int s : shaders) GLES20.glAttachShader(p, s);
    GLES20.glLinkProgram(p);
    int[] ok = new int[1];
    GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
    for (int s : shaders) GLES20.glDeleteShader(s);
    if (ok[0] == 0) {
      String log = GLES20.glGetProgramInfoLog(p);
      GLES20.glDeleteProgram(p);
      throw new RuntimeException("programa " + name + ": " + log);
    }
    return p;
  }

  public static int compute(AssetManager am, String path) {
    return link(path, shader(GLES31.GL_COMPUTE_SHADER, asset(am, path), path));
  }

  public static int program(AssetManager am, String vert, String frag) {
    return link(vert + "+" + frag,
        shader(GLES20.GL_VERTEX_SHADER, asset(am, vert), vert),
        shader(GLES20.GL_FRAGMENT_SHADER, asset(am, frag), frag));
  }

  public static int program(String name, String vertSrc, String fragSrc) {
    return link(name, shader(GLES20.GL_VERTEX_SHADER, vertSrc, name + ".vert"),
        shader(GLES20.GL_FRAGMENT_SHADER, fragSrc, name + ".frag"));
  }

  public static void check(String where) {
    int e;
    boolean bad = false;
    while ((e = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
      Log.e(TAG, "GL error 0x" + Integer.toHexString(e) + " en " + where);
      bad = true;
    }
    if (bad) throw new RuntimeException("error de GL en " + where);
  }

  public static ByteBuffer bytes(int n) {
    return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());
  }

  public static FloatBuffer floats(float[] a) {
    FloatBuffer b = bytes(a.length * 4).asFloatBuffer();
    b.put(a).position(0);
    return b;
  }

  public static IntBuffer ints(int[] a) {
    IntBuffer b = bytes(a.length * 4).asIntBuffer();
    b.put(a).position(0);
    return b;
  }

  public static int genBuffer() {
    int[] id = new int[1];
    GLES20.glGenBuffers(1, id, 0);
    return id[0];
  }

  public static int genTexture() {
    int[] id = new int[1];
    GLES20.glGenTextures(1, id, 0);
    return id[0];
  }

  public static void deleteBuffer(int id) {
    if (id != 0) GLES20.glDeleteBuffers(1, new int[] {id}, 0);
  }

  public static void deleteTexture(int id) {
    if (id != 0) GLES20.glDeleteTextures(1, new int[] {id}, 0);
  }

  /** Textura 2D sin filtrado (texelFetch) con almacenamiento inmutable. */
  public static int texture2D(int internalFormat, int w, int h) {
    int t = genTexture();
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t);
    GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, internalFormat, w, h);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    return t;
  }

  /** Versión de GLES del contexto actual como 30, 31, 32... */
  public static int version() {
    int[] v = new int[2];
    GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, v, 0);
    GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, v, 1);
    return v[0] * 10 + v[1];
  }
}
