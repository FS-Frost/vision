package org.frost.vision.gpu;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.util.Log;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Hilo propio con un contexto EGL de OpenGL ES 3 sobre un pbuffer de 1x1: aquí corre la fusión
 * en GPU (compute), separada del hilo de render. Si el dispositivo no da ES 3.1, {@link #gles}
 * queda < 31 y la fusión usa la referencia CPU en este mismo hilo.
 */
public final class EglThread extends Thread {
  private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
  private volatile boolean running = true;
  private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
  private EGLContext context = EGL14.EGL_NO_CONTEXT;
  private EGLSurface surface = EGL14.EGL_NO_SURFACE;
  /** Versión GLES (31 = 3.1) o 0 si no hay contexto. */
  public volatile int gles;
  private final Object ready = new Object();
  private boolean started;

  public EglThread(String name) {
    super(name);
  }

  /** Arranca el hilo y espera a que el contexto exista. */
  public EglThread begin() {
    start();
    synchronized (ready) {
      while (!started) {
        try { ready.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
      }
    }
    return this;
  }

  public void post(Runnable r) {
    if (running) queue.add(r);
  }

  public int pending() { return queue.size(); }

  public void quit() {
    running = false;
    queue.add(() -> {});
  }

  @Override
  public void run() {
    try {
      initEgl();
    } catch (RuntimeException e) {
      Log.e(Gl.TAG, "EGL no disponible: " + e.getMessage());
      gles = 0;
    }
    synchronized (ready) { started = true; ready.notifyAll(); }
    while (running) {
      Runnable r;
      try { r = queue.take(); } catch (InterruptedException e) { break; }
      try {
        r.run();
      } catch (Throwable t) {
        Log.e(Gl.TAG, "error en " + getName(), t);
      }
    }
    queue.clear();
    releaseEgl();
  }

  private void initEgl() {
    display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    int[] v = new int[2];
    if (!EGL14.eglInitialize(display, v, 0, v, 1)) throw new RuntimeException("eglInitialize");
    int[] attrs = {
        EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
        EGL14.EGL_NONE};
    EGLConfig[] cfg = new EGLConfig[1];
    int[] n = new int[1];
    if (!EGL14.eglChooseConfig(display, attrs, 0, cfg, 0, 1, n, 0) || n[0] == 0) throw new RuntimeException("eglChooseConfig");
    context = EGL14.eglCreateContext(display, cfg[0], EGL14.EGL_NO_CONTEXT,
        new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
    if (context == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext");
    surface = EGL14.eglCreatePbufferSurface(display, cfg[0], new int[] {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
    if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw new RuntimeException("eglMakeCurrent");
    gles = Gl.version();
    Log.i(Gl.TAG, getName() + ": GLES " + gles);
  }

  private void releaseEgl() {
    if (display == EGL14.EGL_NO_DISPLAY) return;
    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
    // Sin eglTerminate: la pantalla por defecto es compartida con el GLSurfaceView del proceso.
    display = EGL14.EGL_NO_DISPLAY;
  }
}
