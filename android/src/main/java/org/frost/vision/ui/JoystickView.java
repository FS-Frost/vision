package org.frost.vision.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Joystick virtual para el paseo: x = lateral, y = adelante (-1..1). */
final class JoystickView extends View {
  volatile float jx, jy;
  private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG), knob = new Paint(Paint.ANTI_ALIAS_FLAG);

  JoystickView(Context c) {
    super(c);
    base.setColor(0x55FFFFFF);
    knob.setColor(0xCCFFFFFF);
  }

  @Override
  protected void onDraw(Canvas c) {
    float r = Math.min(getWidth(), getHeight()) / 2f;
    float cx = getWidth() / 2f, cy = getHeight() / 2f;
    c.drawCircle(cx, cy, r * 0.95f, base);
    c.drawCircle(cx + jx * r * 0.6f, cy - jy * r * 0.6f, r * 0.35f, knob);
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    float r = Math.min(getWidth(), getHeight()) / 2f * 0.6f;
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
      case MotionEvent.ACTION_MOVE: {
        float dx = (e.getX() - getWidth() / 2f) / r, dy = -(e.getY() - getHeight() / 2f) / r;
        float len = (float) Math.hypot(dx, dy);
        if (len > 1) { dx /= len; dy /= len; }
        jx = dx; jy = dy;
        break;
      }
      default:
        jx = 0; jy = 0;
    }
    invalidate();
    return true;
  }
}
