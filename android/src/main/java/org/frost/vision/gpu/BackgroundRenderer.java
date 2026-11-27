package org.frost.vision.gpu;

import android.content.res.AssetManager;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;

import java.nio.FloatBuffer;

/** Imagen de la cámara de ARCore (textura externa OES) a pantalla completa. */
public final class BackgroundRenderer {
  public final int texture;
  private final int prog, uTex, uDim;
  private final FloatBuffer pos, uv;
  private static final float[] QUAD = {-1, -1, 1, -1, -1, 1, 1, 1};

  public BackgroundRenderer(AssetManager am) {
    texture = Gl.genTexture();
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    prog = Gl.program(am, "shaders/background.vert", "shaders/background.frag");
    uTex = GLES20.glGetUniformLocation(prog, "uTex");
    uDim = GLES20.glGetUniformLocation(prog, "uDim");
    pos = Gl.floats(QUAD);
    uv = Gl.floats(new float[8]);
  }

  public void draw(Frame frame, float dim) {
    if (frame.hasDisplayGeometryChanged()) {
      frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, pos,
          Coordinates2d.TEXTURE_NORMALIZED, uv);
      pos.position(0);
      uv.position(0);
    }
    if (frame.getTimestamp() == 0) return; // aún sin imagen
    GLES20.glDisable(GLES20.GL_DEPTH_TEST);
    GLES20.glDepthMask(false);
    GLES20.glUseProgram(prog);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
    GLES20.glUniform1i(uTex, 0);
    GLES20.glUniform1f(uDim, dim);
    GLES20.glEnableVertexAttribArray(0);
    GLES20.glEnableVertexAttribArray(1);
    GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, pos);
    GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 0, uv);
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    GLES20.glDisableVertexAttribArray(0);
    GLES20.glDisableVertexAttribArray(1);
    GLES20.glDepthMask(true);
  }
}
